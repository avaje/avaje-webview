package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
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

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

/**
 * Windows WebView2 implementation via Win32 + Panama FFI.
 *
 * <p>Structure mirrors the reference C webview win32_edge backend:
 * three Win32 windows (main, widget, message-only), one combined COM handler
 * object for env+ctrl callbacks (matching reference's single webview2_com_handler),
 * and all init work deferred to run inside the message pump via msgWndProc so
 * that nested pumping for AddScriptToExecuteOnDocumentCreated completions works.
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
    var      loaderDll = false;

    try {
      final var lib = SymbolLookup.libraryLookup("WebView2Loader.dll", Arena.global());
      fn = Linker.nativeLinker().downcallHandle(
          lib.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      loaderDll = true;
      System.out.println("[wv2] Using WebView2Loader.dll (official loader)");
    } catch (final Exception ignored) {}

    if (fn == null) {
      final var ebWebViewPath = findEbWebViewFromRegistry();
      if (ebWebViewPath != null) {
        try (var a = Arena.ofConfined()) {
          final var hLib = (MemorySegment) Win32.LoadLibraryW.invokeExact(
              a.allocateFrom(ebWebViewPath, StandardCharsets.UTF_16LE));
          if (hLib.address() != 0) {
            final var fnAddr = (MemorySegment) Win32.GetProcAddress.invokeExact(
                hLib, a.allocateFrom("CreateWebViewEnvironmentWithOptionsInternal"));
            if (fnAddr.address() != 0) {
              fn = Linker.nativeLinker().downcallHandle(fnAddr,
                  FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
              System.out.println("[wv2] Using embedded loader: " + ebWebViewPath);
            }
          }
        } catch (final Throwable ignored) {}
      }
    }

    if (fn == null) throw new UnsatisfiedLinkError(
        "WebView2 not available — install Microsoft Edge or the WebView2 Runtime");
    CREATE_ENV_FN  = fn;
    USE_LOADER_DLL = loaderDll;
  }

  private static String findEbWebViewFromRegistry() {
    final var osArch = System.getProperty("os.arch", "");
    final var arch = "amd64".equals(osArch) || "x86_64".equals(osArch) ? "x64"
               : "x86".equals(osArch)   || "i386".equals(osArch)   ? "x86"
               : "arm64";
    for (final long root : new long[]{Win32.HKEY_LOCAL_MACHINE, Win32.HKEY_CURRENT_USER}) {
      final var ebWebView = Win32.regQueryString(root, EDGE_UPDATE_KEY, "EBWebView");
      if (ebWebView == null) continue;
      var p = ebWebView;
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
  /** Set true after all init tasks (settings, scripts, show) complete in msgWndProc. */
  private volatile boolean        webviewReady;
  private volatile boolean        closed;
  private          boolean        debugMode;

  private final List<String> scriptIds = new ArrayList<>();
  // FIFO queue pairing each nativeAddUserScript call to its completion.
  private final ConcurrentLinkedQueue<Runnable> scriptDoneCallbacks = new ConcurrentLinkedQueue<>();

  private volatile int minW, minH, maxW, maxH;

  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
  // Tracks how deep we are in nested pumps (nativeAddUserScript waits). When > 0,
  // WM_APP messages must not drain pending — only COM completion callbacks are wanted.
  private int nestedPumpDepth = 0;

  // Combined env+ctrl handler — one COM object for both, state-dispatched.
  // Mirrors reference webview2_com_handler which implements both interfaces.
  private MemorySegment combinedHandler;
  private volatile boolean ctrlPhase; // false=env callback, true=ctrl callback

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
    arenaStubs.close();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    dispatchImpl(this::doClose);
  }

  private void doClose() {
    if (controller != null) controller.close();
    if (hwnd != null && hwnd.address() != 0) {
      try { final var _ = (int) Win32.DestroyWindow.invokeExact(hwnd); } catch (final Throwable ignored) {}
    }
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
      final var _ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) { minW = width; minH = height; }

  @Override
  protected void setMaxSizeImpl(int width, int height) { maxW = width; maxH = height; }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try {
      final var style = (int) Win32.GetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE);
      final var _ = (int) Win32.SetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE,
          style & ~(Win32.WS_THICKFRAME | Win32.WS_MAXIMIZEBOX));
      Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
        0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setHtmlImpl(String html) {
    webView2.navigateToString(html);
  }

  @Override
  protected void evalImpl(String js) {
    if (closed) return;
    webView2.executeScript(js);
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    pending.add(r);
    if (hwndMsg != null && hwndMsg.address() != 0) {
      try { final var _ = (int) Win32.PostMessageW.invokeExact(hwndMsg, Win32.WM_APP, 0L, 0L); }
      catch (final Throwable t) { throw new RuntimeException(t); }
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    final boolean[] done = {false};
    scriptDoneCallbacks.add(() -> done[0] = true);
    final var handler = buildScriptAddedHandler();
    final var hr = webView2.addScriptToExecuteOnDocumentCreated(js, handler);
    if (hr == 0) {
      nestedPumpDepth++;
      try {
        pumpLoopDebug(() -> done[0]);
      } finally {
        nestedPumpDepth--;
      }
    } else {
      scriptDoneCallbacks.poll();
      System.out.println("[wv2] nativeAddUserScript: addScriptOnDoc failed hr=0x" + Integer.toHexString(hr));
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    for (final String id : scriptIds) webView2.removeScriptToExecuteOnDocumentCreated(id);
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
  public void setIcon(Path path) { dispatchImpl(() -> Win32.setIcon(hwnd, path)); }

  @Override
  public void setIcon(URI uri) {
    try { setIcon(Path.of(uri)); } catch (final Exception ignored) {}
  }

  // -------------------------------------------------------------------------
  // WndProc upcall stubs
  // -------------------------------------------------------------------------

  private MemorySegment mainWndProcStub;
  private MemorySegment msgWndProcStub;
  private MemorySegment widgetWndProcStub;

  private void buildWndProcStubs() {
    final var linker = Linker.nativeLinker();
    final var fd = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    try {
      final var lookup = MethodHandles.lookup();
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
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
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
            final var area = MemorySegment.ofAddress(lParam).reinterpret(256 * 2)
                .getString(0, StandardCharsets.UTF_16LE);
            if ("ImmersiveColorSet".equals(area)) Win32.applyDarkMode(hWnd, isDarkTheme());
          } catch (final Exception ignored) {}
        }
        return 0;
      }
    }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (final Throwable t) { return 0; }
  }

  @SuppressWarnings("unused")
  public long msgWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    if (msg == Win32.WM_APP) {
      // Don't drain pending while inside a nested pump: those pumps only need COM
      // completion callbacks, not general task dispatch. Tasks remain in pending and
      // are processed when the outer pump sees a WM_APP with nestedPumpDepth == 0.
      if (nestedPumpDepth == 0) {
        Runnable r;
        while ((r = pending.poll()) != null) r.run();
      }
      return 0;
    }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (final Throwable t) { return 0; }
  }

  @SuppressWarnings("unused")
  public long widgetWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    if (msg == Win32.WM_SIZE) { resizeWebView2(hWnd); return 0; }
    try { return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam); }
    catch (final Throwable t) { return 0; }
  }

  // -------------------------------------------------------------------------
  // Window creation
  // -------------------------------------------------------------------------

  private void createWindows(int width, int height) {
    try (var a = Arena.ofConfined()) {
      final var hInstance = Win32.getModuleHandle();

      final var mainCls = "AvajeWebView_" + System.identityHashCode(this);
      registerClass(a, hInstance, mainCls, mainWndProcStub);
      hwnd = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0, a.allocateFrom(mainCls, StandardCharsets.UTF_16LE),
          a.allocateFrom("", StandardCharsets.UTF_16LE),
          Win32.WS_OVERLAPPEDWINDOW,
          Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, width, height,
          MemorySegment.NULL, MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwnd == null || hwnd.address() == 0) throw new RuntimeException("CreateWindowExW (main) failed");

      final var widgetCls = "AvajeWebView_Widget_" + System.identityHashCode(this);
      registerClass(a, hInstance, widgetCls, widgetWndProcStub);
      hwndWidget = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0x10000 /* WS_EX_CONTROLPARENT */,
          a.allocateFrom(widgetCls, StandardCharsets.UTF_16LE),
          MemorySegment.NULL, Win32.WS_CHILD, 0, 0, 0, 0,
          hwnd, MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwndWidget == null || hwndWidget.address() == 0) throw new RuntimeException("CreateWindowExW (widget) failed");

      final var msgCls = "AvajeWebView_Msg_" + System.identityHashCode(this);
      registerClass(a, hInstance, msgCls, msgWndProcStub);
      hwndMsg = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0, a.allocateFrom(msgCls, StandardCharsets.UTF_16LE),
          MemorySegment.NULL, 0, 0, 0, 0, 0,
          MemorySegment.ofAddress(-3L) /* HWND_MESSAGE */,
          MemorySegment.NULL, hInstance, MemorySegment.NULL);
      if (hwndMsg == null || hwndMsg.address() == 0) throw new RuntimeException("CreateWindowExW (message) failed");

    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  private static void registerClass(Arena a, MemorySegment hInstance, String cls, MemorySegment proc) {
    final var wce = a.allocate(80);
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
    try { final var _ = (short) Win32.RegisterClassExW.invokeExact(wce); }
    catch (final Throwable t) { throw new RuntimeException(t); }
  }

  // -------------------------------------------------------------------------
  // WebView2 async init chain
  // -------------------------------------------------------------------------

  private void embedWebView2(boolean debug) {
    debugMode = debug;
    combinedHandler = buildCombinedHandler();
    try {
      var userData = System.getenv("APPDATA");
      if (userData == null) userData = System.getProperty("user.home");
      userData += "\\avaje-webview";
      int hr;
      if (USE_LOADER_DLL) {
        try (var a = Arena.ofConfined()) {
          hr = (int) CREATE_ENV_FN.invokeExact(
              MemorySegment.NULL,
              a.allocateFrom(userData, StandardCharsets.UTF_16LE),
              MemorySegment.NULL,
              combinedHandler);
        }
      } else {
        try (var a = Arena.ofConfined()) {
          hr = (int) CREATE_ENV_FN.invokeExact(
              1 /* isBuiltin */, 0 /* installed */,
              a.allocateFrom(userData, StandardCharsets.UTF_16LE),
              MemorySegment.NULL,
              combinedHandler);
        }
      }
      if (hr != 0) throw new RuntimeException("CreateCoreWebView2Environment failed: 0x" + Integer.toHexString(hr));
    } catch (final Throwable t) { throw new RuntimeException(t); }

    // Pump until webviewReady — this exits AFTER the deferred init task in msgWndProc
    // completes (settings + scripts + show). The pump is still running when addScriptOnDoc
    // is called (via setupJsBridge → nativeAddUserScript from within msgWndProc), so nested
    // pumping for completion delivery works correctly.
    pumpLoop(() -> webviewReady);
    if (webView2 == null) throw new RuntimeException("WebView2 initialization failed");
  }

  // -------------------------------------------------------------------------
  // Combined env+ctrl handler — one COM object for both phases.
  // Mirrors reference webview2_com_handler which implements both interfaces
  // on a single object, allowing env->CreateCoreWebView2Controller(wnd, this).
  // -------------------------------------------------------------------------

  private MemorySegment buildCombinedHandler() {
    try {
      final var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onCombinedInvoke",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      return buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  /**
   * Dispatches to env-completed or ctrl-completed phase based on {@link #ctrlPhase}.
   * Called by WebView2 for both CreateCoreWebView2EnvironmentCompletedHandler::Invoke
   * and CreateCoreWebView2ControllerCompletedHandler::Invoke — identical native signature.
   */
  @SuppressWarnings("unused")
  public int onCombinedInvoke(MemorySegment self, int hr, MemorySegment ptr) {
    if (!ctrlPhase) {
      return onEnvCompleted(hr, ptr);
    }
    return onCtrlCompleted(hr, ptr);
  }

  private int onEnvCompleted(int hr, MemorySegment env) {
    System.out.println("[wv2] onEnvCompleted hr=0x" + Integer.toHexString(hr));
    if (hr != 0) { webviewReady = true; return hr; }
    // Pass combinedHandler as the ctrl handler — same object, mirrors reference's "this".
    // When WebView2 calls QI on it for ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
    // our QI returns self, which is this same handler.
    ctrlPhase = true;
    final var createCtrl = vtableFn(env, 3);
    try {
      final var mh = Linker.nativeLinker().downcallHandle(createCtrl,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      final var createHr = (int) mh.invokeExact(env, hwndWidget, combinedHandler);
      System.out.println("[wv2] CreateCoreWebView2Controller hr=0x" + Integer.toHexString(createHr));
      if (createHr != 0) { webviewReady = true; }
    } catch (final Throwable t) { webviewReady = true; throw new RuntimeException(t); }
    return 0;
  }

  private int onCtrlCompleted(int hr, MemorySegment ctrlPtr) {
    System.out.println("[wv2] onCtrlCompleted hr=0x" + Integer.toHexString(hr)
        + " ctrlPtr=0x" + Long.toHexString(ctrlPtr.address()));
    if (hr != 0) { webviewReady = true; return hr; }

    // AddRef the controller before this callback returns. Without this, WebView2
    // releases its reference on return, freeing the COM object and causing every
    // subsequent call through controller.ptr to fail with 0x8007139f.
    nativeAddRef(ctrlPtr);

    controller = new ComController(ctrlPtr);
    final var wv2Ptr = controller.getCoreWebView2();
    if (wv2Ptr.address() == 0 || (wv2Ptr.address() & 7) != 0) {
      System.out.println("[wv2] ERROR: bad wv2Ptr");
      webviewReady = true; return -1;
    }
    webView2 = new ComWebView2(wv2Ptr);

    // Register event handlers while still inside the controller callback (this works;
    // these are local COM ops that don't require the IPC channel).
    addWebMessageHandler();
    addPermissionHandler();
    addProcessFailedHandler();

    // Defer the IPC-requiring work (settings, scripts, resize, show) to a pending task
    // that will run inside msgWndProc — a clean stack frame outside any WebView2 callback.
    // This is the critical fix: addScriptToExecuteOnDocumentCreated needs the pump to be
    // active AND must not be called from within a WebView2 callback completion chain.
    pending.add(this::doInitTasks);
    try { final var _ = (int) Win32.PostMessageW.invokeExact(hwndMsg, Win32.WM_APP, 0L, 0L); }
    catch (final Throwable ignored) {}

    return 0;
  }

  /**
   * Runs after the controller callback returns — called from msgWndProc (inside DispatchMessageW,
   * part of the init pump). At this point we are NOT inside any WebView2 callback, so:
   * <ol>
   *   <li>addScriptToExecuteOnDocumentCreated succeeds (IPC is available).</li>
   *   <li>Nested pumping inside nativeAddUserScript works (GetMessageW picks up completions).</li>
   * </ol>
   * Mirrors reference embed() sequence: settings → add_init_script → resize → put_IsVisible → show.
   */
  private void doInitTasks() {
    applySettings(debugMode);
    setupJsBridge(POST_FN);  // nativeAddUserScript uses nested pump; works from msgWndProc context
    resizeWidget(hwnd);
    try {
      final var hr = controller.putIsVisible(true);
      System.out.println("[wv2] putIsVisible(true) hr=0x" + Integer.toHexString(hr));
      Win32.ShowWindow.invokeExact(hwndWidget, Win32.SW_SHOW);
      Win32.UpdateWindow.invokeExact(hwndWidget);
      Win32.ShowWindow.invokeExact(hwnd, Win32.SW_SHOW);
      Win32.UpdateWindow.invokeExact(hwnd);
      Win32.SetFocus.invokeExact(hwnd);
    } catch (final Throwable t) { throw new RuntimeException(t); }
    focusWebView2();
    // Signal the embedWebView2 pumpLoop to exit.
    webviewReady = true;
  }

  // -------------------------------------------------------------------------
  // WebMessage handler (JS → Java)
  // -------------------------------------------------------------------------

  private void addWebMessageHandler() {
    try {
      final var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onWebMessage",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      webView2.addWebMessageReceived(buildComObject(
          Linker.nativeLinker().upcallStub(mh,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onWebMessage(MemorySegment self, MemorySegment sender, MemorySegment args) {
    // ICoreWebView2WebMessageReceivedEventArgs::TryGetWebMessageAsString — vtable[5]
    try (var a = Arena.ofConfined()) {
      final var pStr = a.allocate(ADDRESS);
      final var tryGet = Linker.nativeLinker().downcallHandle(vtableFn(args, 5),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      final var hr = (int) tryGet.invokeExact(args, pStr);
      if (hr == 0) {
        final var strPtr = pStr.get(ADDRESS, 0);
        if (strPtr.address() != 0) {
          final var json = strPtr.reinterpret(65536).getString(0, StandardCharsets.UTF_16LE);
          Win32.coTaskMemFree(strPtr);
          onMessage(json);
        }
      }
    } catch (final Throwable ignored) {}
    return 0;
  }

  // -------------------------------------------------------------------------
  // Permission handler
  // -------------------------------------------------------------------------

  private void addPermissionHandler() {
    try {
      final var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onPermissionRequested",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      webView2.addPermissionRequested(buildComObject(
          Linker.nativeLinker().upcallStub(mh,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onPermissionRequested(MemorySegment self, MemorySegment sender, MemorySegment args) {
    try (var a = Arena.ofConfined()) {
      final var pKind = a.allocate(JAVA_INT);
      final var getKind = Linker.nativeLinker().downcallHandle(vtableFn(args, 4),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      final var _ = (int) getKind.invokeExact(args, pKind);
      if (pKind.get(JAVA_INT, 0) == Win32.COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ) {
        final var putState = Linker.nativeLinker().downcallHandle(vtableFn(args, 7),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        putState.invokeExact(args, Win32.COREWEBVIEW2_PERMISSION_STATE_ALLOW);
      }
    } catch (final Throwable ignored) {}
    return 0;
  }

  // -------------------------------------------------------------------------
  // ProcessFailed handler (diagnostic: detect browser process crashes)
  // -------------------------------------------------------------------------

  private void addProcessFailedHandler() {
    try {
      final var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onProcessFailed",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      webView2.addProcessFailed(buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onProcessFailed(MemorySegment self, MemorySegment sender, MemorySegment args) {
    System.out.println("[wv2] BROWSER PROCESS FAILED — WebView2 renderer/browser crashed!");
    return 0;
  }

  // -------------------------------------------------------------------------
  // AddScriptToExecuteOnDocumentCreated completed handler
  // -------------------------------------------------------------------------

  private MemorySegment buildScriptAddedHandler() {
    try {
      final var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onScriptAdded",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      return buildComObject(Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onScriptAdded(MemorySegment self, int hr, MemorySegment idStr) {
    System.out.println("[wv2] onScriptAdded hr=0x" + Integer.toHexString(hr)
        + " idStr=0x" + Long.toHexString(idStr.address()));
    if (hr == 0 && idStr.address() != 0) {
      try {
        // idStr is an [in] LPCWSTR owned by WebView2 for the duration of this callback.
        // Copy the string; do NOT call CoTaskMemFree on it.
        final var id = idStr.reinterpret(1024).getString(0, StandardCharsets.UTF_16LE);
        scriptIds.add(id);
        System.out.println("[wv2] script id=\"" + id + "\"");
      } catch (final Exception e) { System.out.println("[wv2] onScriptAdded getString failed: " + e); }
    }
    // Unblock the nativeAddUserScript that submitted this script (FIFO order).
    final var cb = scriptDoneCallbacks.poll();
    if (cb != null) cb.run();
    return 0;
  }

  // -------------------------------------------------------------------------
  // Settings
  // -------------------------------------------------------------------------

  private void applySettings(boolean debug) {
    final var settings = webView2.getSettings();
    if (settings == null) return;
    settings.putIsStatusBarEnabled(false);
    settings.putAreDevToolsEnabled(debug);
  }

  // -------------------------------------------------------------------------
  // Layout helpers
  // -------------------------------------------------------------------------

  private void resizeWidget(MemorySegment hWnd) {
    if (hwndWidget == null || hwndWidget.address() == 0) return;
    try (var a = Arena.ofConfined()) {
      final var rect = Win32.getClientRect(hWnd, a);
      final var left   = rect.get(JAVA_INT,  0);
      final var top    = rect.get(JAVA_INT,  4);
      final var right  = rect.get(JAVA_INT,  8);
      final var bottom = rect.get(JAVA_INT, 12);
      final var _ = (int) Win32.MoveWindow.invokeExact(hwndWidget, left, top, right - left, bottom - top, 1);
    } catch (final Throwable ignored) {}
  }

  private void resizeWebView2(MemorySegment hWnd) {
    if (controller == null) return;
    try (var a = Arena.ofConfined()) {
      final var rect = Win32.getClientRect(hWnd, a);
      final int l = rect.get(JAVA_INT, 0), t = rect.get(JAVA_INT, 4),
          r = rect.get(JAVA_INT, 8), b = rect.get(JAVA_INT, 12);
      if (r - l <= 0 || b - t <= 0) return;
      System.out.println("[wv2] resizeWebView2 bounds=(" + l + "," + t + "," + r + "," + b + ")");
      controller.putBounds(rect);
    }
  }

  private void focusWebView2() {
    if (controller != null) controller.moveFocus(Win32.COREWEBVIEW2_MOVE_FOCUS_PROGRAMMATIC);
  }

  private void applyMinMaxInfo(long lParam) {
    final var mmi = MemorySegment.ofAddress(lParam).reinterpret(40);
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
    final var val = Win32.regQueryString(Win32.HKEY_CURRENT_USER,
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme");
    return "0".equals(val);
  }

  // -------------------------------------------------------------------------
  // Message loop
  // -------------------------------------------------------------------------

  private void pumpLoop(java.util.function.BooleanSupplier done) {
    try (var a = Arena.ofConfined()) {
      final var msg = a.allocate(Win32.MSG_LAYOUT);
      for (;;) {
        if (done.getAsBoolean()) break;
        final var r = (int) Win32.GetMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0);
        if (r <= 0 || msg.get(JAVA_INT, 8) == Win32.WM_QUIT) break;
        final var _ = (int) Win32.TranslateMessage.invokeExact(msg);
        Win32.DispatchMessageW.invokeExact(msg);
        if (done.getAsBoolean()) break;
      }
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  /** Debug variant of pumpLoop: logs the first 20 messages received. */
  private void pumpLoopDebug(java.util.function.BooleanSupplier done) {
    var count = 0;
    try (var a = Arena.ofConfined()) {
      final var msg = a.allocate(Win32.MSG_LAYOUT);
      for (;;) {
        if (done.getAsBoolean()) break;
        final var r = (int) Win32.GetMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0);
        if (count < 20) {
          final var msgId = msg.get(JAVA_INT, 8);
          final var hwndMsg2 = msg.get(ADDRESS, 0).address();
          System.out.printf("[wv2] pumpLoopDebug[%d] GetMessage r=%d msg=0x%x hwnd=0x%x%n",
              count, r, msgId, hwndMsg2);
        }
        count++;
        if (r <= 0) { System.out.println("[wv2] pumpLoopDebug: r=" + r + " exiting"); break; }
        if (msg.get(JAVA_INT, 8) == Win32.WM_QUIT) { System.out.println("[wv2] pumpLoopDebug: WM_QUIT"); break; }
        final var _ = (int) Win32.TranslateMessage.invokeExact(msg);
        Win32.DispatchMessageW.invokeExact(msg);
        if (done.getAsBoolean()) break;
      }
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  // -------------------------------------------------------------------------
  // Typed COM interface wrappers
  // -------------------------------------------------------------------------

  /**
   * ICoreWebView2Controller vtable indices (verified against WebView2.h).
   * <pre>
   *  3  get_IsVisible    4  put_IsVisible    5  get_Bounds       6  put_Bounds
   * 12  MoveFocus       24  Close           25  get_CoreWebView2
   * </pre>
   */
  private static final class ComController {
    private final MemorySegment ptr;
    private final MethodHandle  putIsVisible;
    private final MethodHandle  putBounds;
    private final MethodHandle  moveFocus;
    private final MethodHandle  close;
    private final MethodHandle  getCoreWebView2;

    ComController(MemorySegment ptr) {
      this.ptr        = ptr;
      putIsVisible    = resolve(ptr,  4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      putBounds       = resolve(ptr,  6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      moveFocus       = resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      close           = resolve(ptr, 24, FunctionDescriptor.of(JAVA_INT, ADDRESS));
      getCoreWebView2 = resolve(ptr, 25, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    }

    int putIsVisible(boolean visible) {
      try { return (int) putIsVisible.invokeExact(ptr, visible ? 1 : 0); }
      catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void putBounds(MemorySegment rect) {
      try {
        final var hr = (int) putBounds.invokeExact(ptr, rect);
        System.out.println("[wv2] putBounds hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void moveFocus(int reason) {
      try {
        final var hr = (int) moveFocus.invokeExact(ptr, reason);
        System.out.println("[wv2] moveFocus hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void close() {
      try { final var _ = (int) close.invokeExact(ptr); } catch (final Throwable ignored) {}
    }

    MemorySegment getCoreWebView2() {
      try (var a = Arena.ofConfined()) {
        final var pWv2 = a.allocate(ADDRESS);
        final var hr = (int) getCoreWebView2.invokeExact(ptr, pWv2);
        final var wv2 = pWv2.get(ADDRESS, 0);
        System.out.println("[wv2] get_CoreWebView2 hr=0x" + Integer.toHexString(hr)
            + " ptr=0x" + Long.toHexString(wv2.address()));
        return wv2;
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }
  }

  /**
   * ICoreWebView2 vtable indices (verified against WebView2.h).
   * <pre>
   *  3  get_Settings     5  Navigate         6  NavigateToString
   * 23  add_PermissionRequested
   * 27  AddScriptToExecuteOnDocumentCreated
   * 28  RemoveScriptToExecuteOnDocumentCreated
   * 29  ExecuteScript
   * 34  add_WebMessageReceived
   * </pre>
   */
  private static final class ComWebView2 {
    private final MemorySegment ptr;
    private final MethodHandle  getSettings;
    private final MethodHandle  navigate;
    private final MethodHandle  navigateToString;
    private final MethodHandle  addWebMessageRcvd;
    private final MethodHandle  addPermissionReq;
    private final MethodHandle  addScriptOnDoc;
    private final MethodHandle  removeScript;
    private final MethodHandle  executeScript;

    ComWebView2(MemorySegment ptr) {
      this.ptr          = ptr;
      getSettings       = resolve(ptr,  3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      navigate          = resolve(ptr,  5, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      navigateToString  = resolve(ptr,  6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      addPermissionReq  = resolve(ptr, 23, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      addScriptOnDoc    = resolve(ptr, 27, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      removeScript      = resolve(ptr, 28, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      executeScript     = resolve(ptr, 29, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      addWebMessageRcvd = resolve(ptr, 34, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    }

    ComWebView2Settings getSettings() {
      try (var a = Arena.ofConfined()) {
        final var pSettings = a.allocate(ADDRESS);
        final var hr = (int) getSettings.invokeExact(ptr, pSettings);
        final var s = pSettings.get(ADDRESS, 0);
        System.out.println("[wv2] getSettings hr=0x" + Integer.toHexString(hr)
            + " ptr=0x" + Long.toHexString(s.address()));
        return s.address() != 0 ? new ComWebView2Settings(s) : null;
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void navigate(String url) {
      try (var a = Arena.ofConfined()) {
        final var hr = (int) navigate.invokeExact(ptr, a.allocateFrom(url, StandardCharsets.UTF_16LE));
        System.out.println("[wv2] navigate(\"" + url + "\") hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void navigateToString(String html) {
      try (var a = Arena.ofConfined()) {
        final var hr = (int) navigateToString.invokeExact(ptr, a.allocateFrom(html, StandardCharsets.UTF_16LE));
        System.out.println("[wv2] navigateToString(len=" + html.length() + ") hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void addWebMessageReceived(MemorySegment handler) {
      try (var a = Arena.ofConfined()) {
        final var pToken = a.allocate(JAVA_LONG);
        final var hr = (int) addWebMessageRcvd.invokeExact(ptr, handler, pToken);
        System.out.println("[wv2] addWebMessageReceived hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void addPermissionRequested(MemorySegment handler) {
      try (var a = Arena.ofConfined()) {
        final var pToken = a.allocate(JAVA_LONG);
        final var hr = (int) addPermissionReq.invokeExact(ptr, handler, pToken);
        System.out.println("[wv2] addPermissionRequested hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    // ICoreWebView2::add_ProcessFailed — vtable[25]
    void addProcessFailed(MemorySegment handler) {
      try (var a = Arena.ofConfined()) {
        final var pToken = a.allocate(JAVA_LONG);
        final var mh = resolve(ptr, 25, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        final var hr = (int) mh.invokeExact(ptr, handler, pToken);
        System.out.println("[wv2] add_ProcessFailed hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    int addScriptToExecuteOnDocumentCreated(String js, MemorySegment handler) {
      try (var a = Arena.ofConfined()) {
        final var hr = (int) addScriptOnDoc.invokeExact(ptr,
            a.allocateFrom(js, StandardCharsets.UTF_16LE), handler);
        System.out.println("[wv2] addScriptOnDoc hr=0x" + Integer.toHexString(hr));
        return hr;
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void removeScriptToExecuteOnDocumentCreated(String id) {
      try (var a = Arena.ofConfined()) {
        final var _ = (int) removeScript.invokeExact(ptr, a.allocateFrom(id, StandardCharsets.UTF_16LE));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void executeScript(String js) {
      try (var a = Arena.ofConfined()) {
        final var hr = (int) executeScript.invokeExact(ptr,
            a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
        System.out.println("[wv2] executeScript hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }
  }

  /**
   * ICoreWebView2Settings vtable indices.
   * <pre>
   *  9  get_IsStatusBarEnabled  10  put_IsStatusBarEnabled
   * 11  get_AreDevToolsEnabled  12  put_AreDevToolsEnabled
   * </pre>
   */
  private static final class ComWebView2Settings {
    private final MemorySegment ptr;
    private final MethodHandle  putIsStatusBarEnabled;
    private final MethodHandle  putAreDevToolsEnabled;

    ComWebView2Settings(MemorySegment ptr) {
      this.ptr = ptr;
      putIsStatusBarEnabled = resolve(ptr, 10, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      putAreDevToolsEnabled = resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    }

    void putIsStatusBarEnabled(boolean enabled) {
      try {
        final var hr = (int) putIsStatusBarEnabled.invokeExact(ptr, enabled ? 1 : 0);
        System.out.println("[wv2] putIsStatusBarEnabled(" + enabled + ") hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }

    void putAreDevToolsEnabled(boolean enabled) {
      try {
        final var hr = (int) putAreDevToolsEnabled.invokeExact(ptr, enabled ? 1 : 0);
        System.out.println("[wv2] putAreDevToolsEnabled(" + enabled + ") hr=0x" + Integer.toHexString(hr));
      } catch (final Throwable t) { throw new RuntimeException(t); }
    }
  }

  // -------------------------------------------------------------------------
  // COM vtable resolution helpers
  // -------------------------------------------------------------------------

  private static MethodHandle resolve(MemorySegment comObj, int idx, FunctionDescriptor fd) {
    return Linker.nativeLinker().downcallHandle(vtableFn(comObj, idx), fd);
  }

  private static MemorySegment vtableFn(MemorySegment comObj, int idx) {
    final var vtable = comObj.reinterpret(ADDRESS.byteSize())
        .get(ADDRESS, 0)
        .reinterpret((idx + 1) * ADDRESS.byteSize());
    return vtable.getAtIndex(ADDRESS, idx);
  }

  // -------------------------------------------------------------------------
  // COM object construction (IUnknown + Invoke vtable — 4 slots)
  // -------------------------------------------------------------------------

  private MemorySegment buildComObject(MemorySegment invokeStub) {
    final var vtable = arenaStubs.allocate(ADDRESS, 4);
    vtable.setAtIndex(ADDRESS, 0, qiStub());
    vtable.setAtIndex(ADDRESS, 1, addRefStub());
    vtable.setAtIndex(ADDRESS, 2, releaseStub());
    vtable.setAtIndex(ADDRESS, 3, invokeStub);
    final var obj = arenaStubs.allocate(ADDRESS);
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
      } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
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
      } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
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
      } catch (final ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    return cachedRelease;
  }

  // -------------------------------------------------------------------------
  // Calls IUnknown::AddRef (vtable[1]) on a native COM object to increment
  // its reference count, keeping the object alive beyond the current callback.
  // -------------------------------------------------------------------------
  private static void nativeAddRef(MemorySegment comObj) {
    try {
      final var addRef = Linker.nativeLinker().downcallHandle(
          vtableFn(comObj, 1), // IUnknown::AddRef
          FunctionDescriptor.of(JAVA_INT, ADDRESS));
      final var _ = (int) addRef.invokeExact(comObj);
    } catch (final Throwable ignored) {}
  }
}
