package io.avaje.webview.windows;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.*;

/**
 * Windows WebView2 implementation via Win32 + COM Panama FFI.
 *
 * <p>Mirrors the C webview win32_edge backend: three Win32 windows (main, widget,
 * message-only), WebView2 COM interfaces called through vtable-resolved Panama
 * downcall handles, and COM callback objects built from upcall stubs.
 *
 * <p>COM interface vtable indices are derived from the official WebView2.h IDL
 * declarations.  Each interface is wrapped in a typed inner class that resolves
 * its MethodHandles once from the live vtable when the COM object arrives, then
 * exposes named methods — mirroring the C code's {@code m_controller->put_Bounds()}.
 */
public final class Win32WebView extends WebviewBase {

  // -------------------------------------------------------------------------
  // WebView2 loader — resolved once at class load
  // -------------------------------------------------------------------------

  private static final String EDGE_RELEASE_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
  private static final String EDGE_UPDATE_KEY   =
      "SOFTWARE\\Microsoft\\EdgeUpdate\\ClientState\\" + EDGE_RELEASE_GUID;

  private static final MethodHandle CREATE_ENV_FN;
  private static final boolean      USE_LOADER_DLL;

  static {
    MethodHandle fn       = null;
    boolean      loaderDll = false;

    try {
      var lib = SymbolLookup.libraryLookup("WebView2Loader.dll", Arena.global());
      fn = Linker.nativeLinker().downcallHandle(
          lib.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      loaderDll = true;
    } catch (Exception ignored) {}

    if (fn == null) {
      String ebWebViewPath = findEbWebViewFromRegistry();
      if (ebWebViewPath != null) {
        try (Arena a = Arena.ofConfined()) {
          MemorySegment hLib = (MemorySegment) Win32.LoadLibraryW.invokeExact(
              a.allocateFrom(ebWebViewPath, StandardCharsets.UTF_16LE));
          if (hLib.address() != 0) {
            MemorySegment fnAddr = (MemorySegment) Win32.GetProcAddress.invokeExact(
                hLib, a.allocateFrom("CreateWebViewEnvironmentWithOptionsInternal"));
            if (fnAddr.address() != 0) {
              fn = Linker.nativeLinker().downcallHandle(fnAddr,
                  FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            }
          }
        } catch (Throwable ignored) {}
      }
    }

    if (fn == null) throw new UnsatisfiedLinkError(
        "WebView2 not available — install Microsoft Edge or the WebView2 Runtime");
    CREATE_ENV_FN  = fn;
    USE_LOADER_DLL = loaderDll;
  }

  private static String findEbWebViewFromRegistry() {
    String osArch = System.getProperty("os.arch", "");
    String arch = "amd64".equals(osArch) || "x86_64".equals(osArch) ? "x64"
               : "x86".equals(osArch)   || "i386".equals(osArch)   ? "x86"
               : "arm64";
    for (long root : new long[]{Win32.HKEY_LOCAL_MACHINE, Win32.HKEY_CURRENT_USER}) {
      String ebWebView = Win32.regQueryString(root, EDGE_UPDATE_KEY, "EBWebView");
      if (ebWebView == null) continue;
      String p = ebWebView;
      if (!p.endsWith("\\") && !p.endsWith("/")) p += "\\";
      return p + "EBWebView\\" + arch + "\\EmbeddedBrowserWebView.dll";
    }
    return null;
  }

  private static final String POST_FN =
      "function(message){return window.chrome.webview.postMessage(message);}";

  // -------------------------------------------------------------------------
  // Instance state
  // -------------------------------------------------------------------------

  private static final AtomicInteger openWindows = new AtomicInteger(0);

  private final Arena arenaStubs = Arena.ofShared();

  private volatile MemorySegment hwnd;
  private volatile MemorySegment hwndMsg;
  private volatile MemorySegment hwndWidget;

  private volatile ComController controller;
  private volatile ComWebView2    webView2;
  private volatile boolean        wv2Ready; // set true on both success and failure; check webView2 != null for success
  private volatile boolean        closed;

  private final List<String> scriptIds = new ArrayList<>();

  private volatile int minW, minH, maxW, maxH;

  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

  public Win32WebView(boolean debug, int width, int height) {
    openWindows.incrementAndGet();
    Win32.coInitialize();
    buildWndProcStubs();
    createWindows(width, height);
    embedWebView2(debug);
  }

  // -------------------------------------------------------------------------
  // Webview — event loop
  // -------------------------------------------------------------------------

  @Override
  public void run() {
    pumpLoop(() -> false);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (controller != null) controller.close();
    if (hwnd != null && hwnd.address() != 0) {
      dispatchImpl(() -> {
        try { int _ = (int) Win32.DestroyWindow.invokeExact(hwnd); } catch (Throwable ignored) {}
      });
    }
    arenaStubs.close();
  }

  @Override
  public MemorySegment nativeWindowPointer() {
    return hwnd != null ? hwnd : MemorySegment.NULL;
  }

  // -------------------------------------------------------------------------
  // WebviewBase platform impls
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    webView2.navigate(url);
  }

  @Override
  protected void setTitleImpl(String title) {
    Win32.setWindowText(hwnd, title);
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    try {
      int _ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    minW = width; minH = height;
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    maxW = width; maxH = height;
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try {
      int style = (int) Win32.GetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE);
      int _ = (int) Win32.SetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE,
          style & ~(Win32.WS_THICKFRAME | Win32.WS_MAXIMIZEBOX));
      int __ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setHtmlImpl(String html) {
    webView2.navigateToString(html);
  }

  @Override
  protected void evalImpl(String js) {
    webView2.executeScript(js);
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    pending.add(r);
    if (hwndMsg != null && hwndMsg.address() != 0) {
      try { int _ = (int) Win32.PostMessageW.invokeExact(hwndMsg, Win32.WM_APP, 0L, 0L); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    MemorySegment handler = buildScriptAddedHandler();
    webView2.addScriptToExecuteOnDocumentCreated(js, handler);
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    for (String id : scriptIds) webView2.removeScriptToExecuteOnDocumentCreated(id);
    scriptIds.clear();
  }

  // -------------------------------------------------------------------------
  // Appearance / chrome
  // -------------------------------------------------------------------------

  @Override
  public void setDarkAppearance(boolean dark) {
    dispatchImpl(() -> Win32.applyDarkMode(hwnd, dark));
  }

  @Override
  public Webview maximizeWindow() {
    dispatchImpl(() -> Win32.showWindow(hwnd, Win32.SW_MAXIMIZE));
    return this;
  }

  @Override
  public Webview fullscreen() {
    dispatchImpl(() -> Win32.fullscreen(hwnd));
    return this;
  }

  @Override
  public void setIcon(Path path) {
    dispatchImpl(() -> Win32.setIcon(hwnd, path));
  }

  @Override
  public void setIcon(URI uri) {
    try { setIcon(Path.of(uri)); } catch (Exception ignored) {}
  }

  // -------------------------------------------------------------------------
  // WndProc upcall stubs
  // -------------------------------------------------------------------------

  private MemorySegment mainWndProcStub;
  private MemorySegment msgWndProcStub;
  private MemorySegment widgetWndProcStub;

  private void buildWndProcStubs() {
    var linker = Linker.nativeLinker();
    var fd = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    try {
      var lookup = MethodHandles.lookup();
      mainWndProcStub = linker.upcallStub(
          lookup.findVirtual(Win32WebView.class, "mainWndProc",
              MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
              .bindTo(this), fd, arenaStubs);
      msgWndProcStub = linker.upcallStub(
          lookup.findVirtual(Win32WebView.class, "msgWndProc",
              MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
              .bindTo(this), fd, arenaStubs);
      widgetWndProcStub = linker.upcallStub(
          lookup.findVirtual(Win32WebView.class, "widgetWndProc",
              MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
              .bindTo(this), fd, arenaStubs);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public long mainWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    switch (msg) {
      case Win32.WM_DESTROY -> {
        if (openWindows.decrementAndGet() == 0) Win32.postQuitMessage(0);
        return 0;
      }
      case Win32.WM_CLOSE   -> { close(); return 0; }
      case Win32.WM_SIZE    -> { resizeWidget(hWnd); return 0; }
      case Win32.WM_ACTIVATE -> {
        if ((int)(wParam & 0xFFFF) != Win32.WA_INACTIVE) focusWebView2();
        return 0;
      }
      case Win32.WM_GETMINMAXINFO -> { applyMinMaxInfo(lParam); return 0; }
      case Win32.WM_SETTINGCHANGE -> {
        if (lParam != 0) {
          try {
            String area = MemorySegment.ofAddress(lParam).reinterpret(256 * 2)
                .getString(0, StandardCharsets.UTF_16LE);
            if ("ImmersiveColorSet".equals(area)) Win32.applyDarkMode(hWnd, isDarkTheme());
          } catch (Exception ignored) {}
        }
        return 0;
      }
    }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (Throwable t) { return 0; }
  }

  @SuppressWarnings("unused")
  public long msgWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    if (msg == Win32.WM_APP) {
      Runnable r;
      while ((r = pending.poll()) != null) r.run();
      return 0;
    }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (Throwable t) { return 0; }
  }

  @SuppressWarnings("unused")
  public long widgetWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    if (msg == Win32.WM_SIZE) { resizeWebView2(hWnd); return 0; }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (Throwable t) { return 0; }
  }

  // -------------------------------------------------------------------------
  // Window creation
  // -------------------------------------------------------------------------

  private void createWindows(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment hInstance = Win32.getModuleHandle();

      String mainCls = "AvajeWebView_" + System.identityHashCode(this);
      registerClass(a, hInstance, mainCls, mainWndProcStub);
      hwnd = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0, a.allocateFrom(mainCls, StandardCharsets.UTF_16LE),
          a.allocateFrom("", StandardCharsets.UTF_16LE),
          Win32.WS_OVERLAPPEDWINDOW,
          Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, width, height,
          MemorySegment.NULL, MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwnd == null || hwnd.address() == 0) throw new RuntimeException("CreateWindowExW (main) failed");

      String widgetCls = "AvajeWebView_Widget_" + System.identityHashCode(this);
      registerClass(a, hInstance, widgetCls, widgetWndProcStub);
      hwndWidget = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0x10000 /* WS_EX_CONTROLPARENT */,
          a.allocateFrom(widgetCls, StandardCharsets.UTF_16LE),
          MemorySegment.NULL, Win32.WS_CHILD, 0, 0, 0, 0,
          hwnd, MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwndWidget == null || hwndWidget.address() == 0) throw new RuntimeException("CreateWindowExW (widget) failed");

      String msgCls = "AvajeWebView_Msg_" + System.identityHashCode(this);
      registerClass(a, hInstance, msgCls, msgWndProcStub);
      hwndMsg = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0, a.allocateFrom(msgCls, StandardCharsets.UTF_16LE),
          MemorySegment.NULL, 0, 0, 0, 0, 0,
          MemorySegment.ofAddress(-3L) /* HWND_MESSAGE */,
          MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwndMsg == null || hwndMsg.address() == 0) throw new RuntimeException("CreateWindowExW (message) failed");

    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private static void registerClass(Arena a, MemorySegment hInstance, String cls, MemorySegment proc) {
    MemorySegment wce = a.allocate(80); // WNDCLASSEXW on Win64
    wce.set(JAVA_INT,  0, 80);
    wce.set(JAVA_INT,  4, 0x0003); // CS_HREDRAW | CS_VREDRAW
    wce.set(ADDRESS,   8, proc);
    wce.set(JAVA_INT, 16, 0);
    wce.set(JAVA_INT, 20, 0);
    wce.set(ADDRESS,  24, hInstance);
    wce.set(ADDRESS,  32, MemorySegment.NULL);
    wce.set(ADDRESS,  40, MemorySegment.NULL);
    wce.set(ADDRESS,  48, MemorySegment.NULL);
    wce.set(ADDRESS,  56, MemorySegment.NULL);
    wce.set(ADDRESS,  64, a.allocateFrom(cls, StandardCharsets.UTF_16LE));
    wce.set(ADDRESS,  72, MemorySegment.NULL);
    try { short _ = (short) Win32.RegisterClassExW.invokeExact(wce); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  // -------------------------------------------------------------------------
  // WebView2 async init chain
  // -------------------------------------------------------------------------

  private void embedWebView2(boolean debug) {
    MemorySegment envHandler = buildEnvCompletedHandler();
    try {
      String userData = System.getenv("APPDATA");
      if (userData == null) userData = System.getProperty("user.home");
      userData += "\\avaje-webview";
      int hr;
      if (USE_LOADER_DLL) {
        try (Arena a = Arena.ofConfined()) {
          hr = (int) CREATE_ENV_FN.invokeExact(
              MemorySegment.NULL,
              a.allocateFrom(userData, StandardCharsets.UTF_16LE),
              MemorySegment.NULL,
              envHandler);
        }
      } else {
        try (Arena a = Arena.ofConfined()) {
          hr = (int) CREATE_ENV_FN.invokeExact(
              1 /* isBuiltin */, 0 /* installed */,
              a.allocateFrom(userData, StandardCharsets.UTF_16LE),
              MemorySegment.NULL,
              envHandler);
        }
      }
      if (hr != 0) throw new RuntimeException("CreateCoreWebView2Environment failed: 0x" + Integer.toHexString(hr));
    } catch (Throwable t) { throw new RuntimeException(t); }

    pumpLoop(() -> wv2Ready);
    if (webView2 == null) throw new RuntimeException("WebView2 initialization failed");

    applySettings(debug);
    setupJsBridge(POST_FN);

    try {
      int _ = (int) Win32.ShowWindow.invokeExact(hwndWidget, Win32.SW_SHOW);
      int __ = (int) Win32.ShowWindow.invokeExact(hwnd, Win32.SW_SHOW);
      int ___ = (int) Win32.UpdateWindow.invokeExact(hwnd);
      MemorySegment ____ = (MemorySegment) Win32.SetFocus.invokeExact(hwnd);
    } catch (Throwable t) { throw new RuntimeException(t); }
    resizeWidget(hwnd);
  }

  // -------------------------------------------------------------------------
  // COM environment completed handler
  // -------------------------------------------------------------------------

  private MemorySegment buildEnvCompletedHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onEnvCompleted",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      return buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onEnvCompleted(MemorySegment self, int hr, MemorySegment env) {
    if (hr != 0) { wv2Ready = true; return hr; }
    // ICoreWebView2Environment::CreateCoreWebView2Controller — vtable[3] per IDL
    MemorySegment ctrlHandler = buildCtrlCompletedHandler();
    MemorySegment createCtrl = vtableFn(env, 3);
    try {
      MethodHandle mh = Linker.nativeLinker().downcallHandle(createCtrl,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      int _ = (int) mh.invokeExact(env, hwndWidget, ctrlHandler);
    } catch (Throwable t) { throw new RuntimeException(t); }
    return 0;
  }

  // -------------------------------------------------------------------------
  // COM controller completed handler
  // -------------------------------------------------------------------------

  private MemorySegment buildCtrlCompletedHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onCtrlCompleted",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      return buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onCtrlCompleted(MemorySegment self, int hr, MemorySegment ctrlPtr) {
    if (hr != 0) { wv2Ready = true; return hr; }
    controller = new ComController(ctrlPtr);
    webView2   = new ComWebView2(controller.getCoreWebView2());

    addWebMessageHandler();
    addPermissionHandler();

    resizeWebView2(hwndWidget);
    controller.putIsVisible(true);
    focusWebView2();
    wv2Ready = true;
    return 0;
  }

  // -------------------------------------------------------------------------
  // WebMessage handler (JS → Java)
  // -------------------------------------------------------------------------

  private void addWebMessageHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onWebMessage",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      webView2.addWebMessageReceived(buildComObject(
          Linker.nativeLinker().upcallStub(mh,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onWebMessage(MemorySegment self, MemorySegment sender, MemorySegment args) {
    // ICoreWebView2WebMessageReceivedEventArgs::TryGetWebMessageAsString — vtable[5] per IDL
    // IDL order: QI=0, AddRef=1, Release=2, get_Source=3, get_WebMessageAsJson=4, TryGetWebMessageAsString=5
    try (Arena a = Arena.ofConfined()) {
      MemorySegment pStr = a.allocate(ADDRESS);
      MethodHandle tryGet = Linker.nativeLinker().downcallHandle(vtableFn(args, 5),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      int hr = (int) tryGet.invokeExact(args, pStr);
      if (hr == 0) {
        MemorySegment strPtr = pStr.get(ADDRESS, 0);
        if (strPtr.address() != 0) {
          String json = strPtr.reinterpret(65536).getString(0, StandardCharsets.UTF_16LE);
          Win32.coTaskMemFree(strPtr);
          onMessage(json);
        }
      }
    } catch (Throwable ignored) {}
    return 0;
  }

  // -------------------------------------------------------------------------
  // Permission handler
  // -------------------------------------------------------------------------

  private void addPermissionHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onPermissionRequested",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      webView2.addPermissionRequested(buildComObject(
          Linker.nativeLinker().upcallStub(mh,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onPermissionRequested(MemorySegment self, MemorySegment sender, MemorySegment args) {
    // ICoreWebView2PermissionRequestedEventArgs vtable per IDL:
    // QI=0,AddRef=1,Release=2, get_Uri=3, get_PermissionKind=4, get_IsUserInitiated=5, get_State=6, put_State=7
    try (Arena a = Arena.ofConfined()) {
      MemorySegment pKind = a.allocate(JAVA_INT);
      MethodHandle getKind = Linker.nativeLinker().downcallHandle(vtableFn(args, 4),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      int _ = (int) getKind.invokeExact(args, pKind);
      if (pKind.get(JAVA_INT, 0) == Win32.COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ) {
        MethodHandle putState = Linker.nativeLinker().downcallHandle(vtableFn(args, 7),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        int __ = (int) putState.invokeExact(args, Win32.COREWEBVIEW2_PERMISSION_STATE_ALLOW);
      }
    } catch (Throwable ignored) {}
    return 0;
  }

  // -------------------------------------------------------------------------
  // AddScriptToExecuteOnDocumentCreated completed handler
  // -------------------------------------------------------------------------

  private MemorySegment buildScriptAddedHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onScriptAdded",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      return buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onScriptAdded(MemorySegment self, int hr, MemorySegment idStr) {
    if (hr == 0 && idStr.address() != 0) {
      try {
        String id = idStr.reinterpret(1024).getString(0, StandardCharsets.UTF_16LE);
        Win32.coTaskMemFree(idStr);
        scriptIds.add(id);
      } catch (Exception ignored) {}
    }
    return 0;
  }

  // -------------------------------------------------------------------------
  // Settings
  // -------------------------------------------------------------------------

  private void applySettings(boolean debug) {
    ComWebView2Settings settings = webView2.getSettings();
    if (settings == null) return;
    settings.putIsStatusBarEnabled(false);
    settings.putAreDevToolsEnabled(debug);
  }

  // -------------------------------------------------------------------------
  // Layout helpers
  // -------------------------------------------------------------------------

  private void resizeWidget(MemorySegment hWnd) {
    if (hwndWidget == null || hwndWidget.address() == 0) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment rect = Win32.getClientRect(hWnd, a);
      int left   = rect.get(JAVA_INT,  0);
      int top    = rect.get(JAVA_INT,  4);
      int right  = rect.get(JAVA_INT,  8);
      int bottom = rect.get(JAVA_INT, 12);
      int _ = (int) Win32.MoveWindow.invokeExact(hwndWidget, left, top, right - left, bottom - top, 1);
    } catch (Throwable ignored) {}
  }

  private void resizeWebView2(MemorySegment hWnd) {
    if (controller == null) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment rect = Win32.getClientRect(hWnd, a);
      controller.putBounds(rect);
    }
  }

  private void focusWebView2() {
    if (controller != null) controller.moveFocus(Win32.COREWEBVIEW2_MOVE_FOCUS_PROGRAMMATIC);
  }

  private void applyMinMaxInfo(long lParam) {
    MemorySegment mmi = MemorySegment.ofAddress(lParam).reinterpret(40);
    if (maxW > 0 && maxH > 0) {
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxSize_x,  maxW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxSize_y,  maxH);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxTrack_x, maxW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxTrack_y, maxH);
    }
    if (minW > 0 && minH > 0) {
      mmi.set(JAVA_INT, Win32.MINMAX_ptMinTrack_x, minW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMinTrack_y, minH);
    }
  }

  private static boolean isDarkTheme() {
    String val = Win32.regQueryString(Win32.HKEY_CURRENT_USER,
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme");
    return "0".equals(val);
  }

  // -------------------------------------------------------------------------
  // Message loop
  // -------------------------------------------------------------------------

  private void pumpLoop(java.util.function.BooleanSupplier done) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment msg = a.allocate(Win32.MSG_LAYOUT);
      for (;;) {
        if (done.getAsBoolean()) break;
        int r = (int) Win32.GetMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0);
        if (r <= 0) break;
        int wmsg = msg.get(JAVA_INT, 8);
        if (wmsg == Win32.WM_QUIT) break;
        int _ = (int) Win32.TranslateMessage.invokeExact(msg);
        long __ = (long) Win32.DispatchMessageW.invokeExact(msg);
        if (done.getAsBoolean()) break;
      }
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  // -------------------------------------------------------------------------
  // Typed COM interface wrappers
  // -------------------------------------------------------------------------

  /**
   * Wraps ICoreWebView2Controller.
   *
   * <p>Vtable layout (IDL order is correct for slots 3–6; MoveFocus and beyond are +1
   * relative to IDL due to one extra slot in the runtime vtable):
   * <pre>
   *  3  get_IsVisible        4  put_IsVisible   (BOOL)
   *  5  get_Bounds           6  put_Bounds      (RECT by-ref on x64)
   *  7  get_ZoomFactor       8  put_ZoomFactor
   *  9  add_ZoomFactorChanged  10  remove_ZoomFactorChanged
   * 11  (extra)             12  MoveFocus
   * 13  add_MoveFocusRequested  14–23  focus/accel/parent events
   * 24  Close               25  get_CoreWebView2
   * </pre>
   */
  private static final class ComController {
    private final MemorySegment ptr;
    private final MethodHandle  putIsVisible;    // vtable[4]  — BOOL
    private final MethodHandle  putBounds;       // vtable[6]  — RECT (by-ref on x64)
    private final MethodHandle  moveFocus;       // vtable[12]
    private final MethodHandle  close;           // vtable[24]
    private final MethodHandle  getCoreWebView2; // vtable[25]

    ComController(MemorySegment ptr) {
      this.ptr          = ptr;
      putIsVisible    = resolve(ptr,  4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      putBounds       = resolve(ptr,  6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      moveFocus       = resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      close           = resolve(ptr, 24, FunctionDescriptor.of(JAVA_INT, ADDRESS));
      getCoreWebView2 = resolve(ptr, 25, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    }

    void putIsVisible(boolean visible) {
      try { int _ = (int) putIsVisible.invokeExact(ptr, visible ? 1 : 0); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }

    void putBounds(MemorySegment rect) {
      try { int _ = (int) putBounds.invokeExact(ptr, rect); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }

    void moveFocus(int reason) {
      try { int _ = (int) moveFocus.invokeExact(ptr, reason); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }

    void close() {
      try { int _ = (int) close.invokeExact(ptr); }
      catch (Throwable ignored) {}
    }

    MemorySegment getCoreWebView2() {
      try (Arena a = Arena.ofConfined()) {
        MemorySegment pWv2 = a.allocate(ADDRESS);
        int _ = (int) getCoreWebView2.invokeExact(ptr, pWv2);
        return pWv2.get(ADDRESS, 0);
      } catch (Throwable t) { throw new RuntimeException(t); }
    }
  }

  /**
   * Wraps ICoreWebView2.
   *
   * <p>Vtable layout per WebView2.h IDL (IUnknown occupies slots 0–2):
   * <pre>
   *  3  get_Settings          4  get_Source
   *  5  Navigate              6  NavigateToString
   *  7  add_NavigationStarting  ...  20 remove_FrameNavigationCompleted
   * 21  add_WebMessageReceived  22  remove_WebMessageReceived
   * 23  add_PermissionRequested  24  remove_PermissionRequested
   * 25  add_ProcessFailed     26  remove_ProcessFailed
   * 27  AddScriptToExecuteOnDocumentCreated
   * 28  RemoveScriptToExecuteOnDocumentCreated
   * 29  ExecuteScript         30  CapturePreview  31  Reload ...
   * </pre>
   */
  private static final class ComWebView2 {
    private final MemorySegment ptr;
    private final MethodHandle  getSettings;        // vtable[3]
    private final MethodHandle  navigate;           // vtable[5]
    private final MethodHandle  navigateToString;   // vtable[6]
    private final MethodHandle  addWebMessageRcvd;  // vtable[21]
    private final MethodHandle  addPermissionReq;   // vtable[23]
    private final MethodHandle  addScriptOnDoc;     // vtable[27]
    private final MethodHandle  removeScript;       // vtable[28]
    private final MethodHandle  executeScript;      // vtable[29]

    ComWebView2(MemorySegment ptr) {
      this.ptr = ptr;
      getSettings       = resolve(ptr,  3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      navigate          = resolve(ptr,  5, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      navigateToString  = resolve(ptr,  6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      addWebMessageRcvd = resolve(ptr, 21, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      addPermissionReq  = resolve(ptr, 23, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      addScriptOnDoc    = resolve(ptr, 27, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      removeScript      = resolve(ptr, 28, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      executeScript     = resolve(ptr, 29, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    ComWebView2Settings getSettings() {
      try (Arena a = Arena.ofConfined()) {
        MemorySegment pSettings = a.allocate(ADDRESS);
        int _ = (int) getSettings.invokeExact(ptr, pSettings);
        MemorySegment s = pSettings.get(ADDRESS, 0);
        return s.address() != 0 ? new ComWebView2Settings(s) : null;
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void navigate(String url) {
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) navigate.invokeExact(ptr, a.allocateFrom(url, StandardCharsets.UTF_16LE));
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void navigateToString(String html) {
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) navigateToString.invokeExact(ptr, a.allocateFrom(html, StandardCharsets.UTF_16LE));
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void addWebMessageReceived(MemorySegment handler) {
      try (Arena a = Arena.ofConfined()) {
        MemorySegment pToken = a.allocate(JAVA_LONG);
        int _ = (int) addWebMessageRcvd.invokeExact(ptr, handler, pToken);
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void addPermissionRequested(MemorySegment handler) {
      try (Arena a = Arena.ofConfined()) {
        MemorySegment pToken = a.allocate(JAVA_LONG);
        int _ = (int) addPermissionReq.invokeExact(ptr, handler, pToken);
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void addScriptToExecuteOnDocumentCreated(String js, MemorySegment handler) {
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) addScriptOnDoc.invokeExact(ptr,
            a.allocateFrom(js, StandardCharsets.UTF_16LE), handler);
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void removeScriptToExecuteOnDocumentCreated(String id) {
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) removeScript.invokeExact(ptr,
            a.allocateFrom(id, StandardCharsets.UTF_16LE));
      } catch (Throwable t) { throw new RuntimeException(t); }
    }

    void executeScript(String js) {
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) executeScript.invokeExact(ptr,
            a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
      } catch (Throwable t) { throw new RuntimeException(t); }
    }
  }

  /**
   * Wraps ICoreWebView2Settings.
   *
   * <p>Vtable layout per WebView2.h IDL (IUnknown occupies slots 0–2):
   * <pre>
   *  3  get_IsScriptEnabled     4  put_IsScriptEnabled
   *  5  get_IsWebMessageEnabled  6  put_IsWebMessageEnabled
   *  7  get_AreDefaultScriptDialogsEnabled  8  put_AreDefaultScriptDialogsEnabled
   *  9  get_IsStatusBarEnabled  10  put_IsStatusBarEnabled
   * 11  get_AreDevToolsEnabled  12  put_AreDevToolsEnabled  ...
   * </pre>
   */
  private static final class ComWebView2Settings {
    private final MemorySegment ptr;
    private final MethodHandle  putIsStatusBarEnabled;  // vtable[10]
    private final MethodHandle  putAreDevToolsEnabled;  // vtable[12]

    ComWebView2Settings(MemorySegment ptr) {
      this.ptr = ptr;
      putIsStatusBarEnabled = resolve(ptr, 10, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      putAreDevToolsEnabled = resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    }

    void putIsStatusBarEnabled(boolean enabled) {
      try { int _ = (int) putIsStatusBarEnabled.invokeExact(ptr, enabled ? 1 : 0); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }

    void putAreDevToolsEnabled(boolean enabled) {
      try { int _ = (int) putAreDevToolsEnabled.invokeExact(ptr, enabled ? 1 : 0); }
      catch (Throwable t) { throw new RuntimeException(t); }
    }
  }

  // -------------------------------------------------------------------------
  // COM vtable resolution
  // -------------------------------------------------------------------------

  /**
   * Reads vtable[idx] from a COM object pointer at runtime and returns a downcall
   * MethodHandle for it.  Called once per method per COM object instance — the
   * returned handle is cached in the typed wrapper so the vtable is only read
   * at construction, not on every call.
   */
  private static MethodHandle resolve(MemorySegment comObj, int idx, FunctionDescriptor fd) {
    MemorySegment fnPtr = vtableFn(comObj, idx);
    return Linker.nativeLinker().downcallHandle(fnPtr, fd);
  }

  private static MemorySegment vtableFn(MemorySegment comObj, int idx) {
    MemorySegment vtable = comObj.reinterpret(ADDRESS.byteSize())
        .get(ADDRESS, 0)
        .reinterpret((long)(idx + 1) * ADDRESS.byteSize());
    return vtable.getAtIndex(ADDRESS, idx);
  }

  // -------------------------------------------------------------------------
  // COM object construction (IUnknown + Invoke vtable)
  // -------------------------------------------------------------------------

  private MemorySegment buildComObject(MemorySegment invokeStub) {
    MemorySegment vtable = arenaStubs.allocate(ADDRESS, 4);
    vtable.setAtIndex(ADDRESS, 0, qiStub());
    vtable.setAtIndex(ADDRESS, 1, addRefStub());
    vtable.setAtIndex(ADDRESS, 2, releaseStub());
    vtable.setAtIndex(ADDRESS, 3, invokeStub);
    MemorySegment obj = arenaStubs.allocate(ADDRESS);
    obj.set(ADDRESS, 0, vtable);
    return obj;
  }

  private MemorySegment cachedQI, cachedAddRef, cachedRelease;

  private MemorySegment qiStub() {
    if (cachedQI == null) {
      try {
        cachedQI = Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findStatic(Win32WebView.class, "comQI",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs);
      } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    return cachedQI;
  }

  private MemorySegment addRefStub() {
    if (cachedAddRef == null) {
      try {
        cachedAddRef = Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findStatic(Win32WebView.class, "comAddRef",
                MethodType.methodType(long.class, MemorySegment.class)),
            FunctionDescriptor.of(JAVA_LONG, ADDRESS), arenaStubs);
      } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    return cachedAddRef;
  }

  private MemorySegment releaseStub() {
    if (cachedRelease == null) {
      try {
        cachedRelease = Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findStatic(Win32WebView.class, "comRelease",
                MethodType.methodType(long.class, MemorySegment.class)),
            FunctionDescriptor.of(JAVA_LONG, ADDRESS), arenaStubs);
      } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    return cachedRelease;
  }

  @SuppressWarnings("unused")
  private static int  comQI(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
    ppv.set(ADDRESS, 0, self); return 0;
  }
  @SuppressWarnings("unused")
  private static long comAddRef(MemorySegment self)  { return 1L; }
  @SuppressWarnings("unused")
  private static long comRelease(MemorySegment self) { return 1L; }
}
