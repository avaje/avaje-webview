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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.*;

/**
 * Windows WebView2 implementation via Win32 + COM Panama FFI.
 *
 * <p>Requires Microsoft Edge WebView2 Runtime to be installed and
 * {@code WebView2Loader.dll} to be on the library path.
 */
final class Win32WebView extends WebviewBase {

  // WM_APP + 1 — custom message to wake the message loop for dispatch
  private static final int WM_DISPATCH = 0x8000 + 1;

  // WebView2Loader
  private static final MethodHandle CREATE_WEBVIEW2_ENV;
  static {
    SymbolLookup wv2;
    try {
      wv2 = SymbolLookup.libraryLookup("WebView2Loader.dll", Arena.global());
    } catch (IllegalArgumentException e) {
      throw new UnsatisfiedLinkError(
          "WebView2Loader.dll not found — install Microsoft Edge WebView2 Runtime: " + e.getMessage());
    }
    CREATE_WEBVIEW2_ENV = Linker.nativeLinker().downcallHandle(
        wv2.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  }

  private static final AtomicInteger openWindows = new AtomicInteger(0);
  // Upcall arena — shared across all stubs for this instance
  private final Arena arenaStubs = Arena.ofShared();

  // Win32 state
  private volatile MemorySegment hwnd;
  private MemorySegment wndProcStub;

  // WebView2 COM objects (pointers to COM interface pointers)
  private volatile MemorySegment wv2Controller;  // ICoreWebView2Controller*
  private volatile MemorySegment wv2;            // ICoreWebView2*
  private final CountDownLatch wv2Ready = new CountDownLatch(1);

  // Dispatch
  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

  Win32WebView(boolean debug, int width, int height) {
    openWindows.incrementAndGet();
    buildWndProcStub();
    createWindow(width, height);
    initWebView2(debug);
    // Block until WebView2 environment + controller are ready
    try { wv2Ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  // -------------------------------------------------------------------------
  // Webview — event loop
  // -------------------------------------------------------------------------

  @Override
  public void run() {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment msg = a.allocate(Win32.MSG_LAYOUT);
      int result;
      while ((result = (int) Win32.GetMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0)) > 0) {
        Win32.TranslateMessage.invokeExact(msg);
        Win32.DispatchMessageW.invokeExact(msg);
      }
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  public void close() {
    if (hwnd != null && hwnd.address() != 0L) {
      dispatchImpl(() -> {
        try {
          Linker.nativeLinker().downcallHandle(
              Linker.nativeLinker().defaultLookup().find("DestroyWindow")
                  .orElse(SymbolLookup.libraryLookup("user32", Arena.global()).find("DestroyWindow").orElseThrow()),
              FunctionDescriptor.of(JAVA_INT, ADDRESS))
              .invokeExact(hwnd);
        } catch (Throwable t) { throw new RuntimeException(t); }
      });
    }
    if (wv2Controller != null) {
      comCall(wv2Controller, 8 /* Close */, FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }
    arenaStubs.close();
  }

  // -------------------------------------------------------------------------
  // Webview — native pointer & metadata
  // -------------------------------------------------------------------------

  @Override
  public MemorySegment nativeWindowPointer() {
    return hwnd != null ? hwnd : MemorySegment.NULL;
  }

  @Override
  public String version() {
    return "WebView2 (Windows)";
  }

  // -------------------------------------------------------------------------
  // WebviewBase — platform impls (called on Win32 message-loop thread via dispatch)
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    try (Arena a = Arena.ofConfined()) {
      // ICoreWebView2::Navigate(LPCWSTR uri) — vtable index 28
      comCallWithArg(wv2, 28, url);
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    try (Arena a = Arena.ofConfined()) {
      Win32.SetWindowLong.invokeExact(hwnd, -16, Win32.WS_OVERLAPPEDWINDOW); // no-op, just placeholder
      // SetWindowTextW(hwnd, title)
      Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("SetWindowTextW").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(hwnd, a.allocateFrom(title, StandardCharsets.UTF_16LE));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    try {
      int _ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_SHOWWINDOW);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    // Enforced in WM_GETMINMAXINFO — would need WndProc customisation; no-op for now
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    // Enforced in WM_GETMINMAXINFO — no-op for now
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try {
      int style = (int) Win32.GetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE);
      int _ = (int) Win32.SetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE,
          style & ~(0x00040000 /* WS_SIZEBOX */ | 0x00010000 /* WS_MAXIMIZEBOX */));
      int _ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_SHOWWINDOW);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setHtmlImpl(String html) {
    // ICoreWebView2::NavigateToString(LPCWSTR htmlContent) — vtable index 29
    comCallWithArg(wv2, 29, html);
  }

  @Override
  protected void evalImpl(String js) {
    // ICoreWebView2::ExecuteScript(LPCWSTR javaScript, handler) — vtable index 37
    // Pass NULL handler (fire-and-forget)
    try (Arena a = Arena.ofConfined()) {
      MemorySegment vtbl = wv2.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 37 * 8L);
      Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(wv2, a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    pending.add(r);
    try {
      Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("PostMessageW").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))
          .invokeExact(hwnd, WM_DISPATCH, 0L, 0L);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    // ICoreWebView2::AddScriptToExecuteOnDocumentCreated — vtable index 49, takes handler
    // Use NULL handler (async, fire-and-forget)
    try (Arena a = Arena.ofConfined()) {
      MemorySegment vtbl = wv2.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 49 * 8L);
      Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(wv2, a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    // WebView2 doesn't have a bulk removeAllScripts; no-op (scripts accumulate across navigations)
  }

  // -------------------------------------------------------------------------
  // Webview — appearance/chrome
  // -------------------------------------------------------------------------

  @Override
  public void setDarkAppearance(boolean shouldAppearDark) {
    dispatchImpl(() -> Win32.setDarkMode(hwnd, shouldAppearDark));
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
  // WndProc upcall
  // -------------------------------------------------------------------------

  @SuppressWarnings("unused")
  public long wndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    switch (msg) {
      case Win32.WM_DESTROY -> {
        if (openWindows.decrementAndGet() == 0) Win32.postQuitMessage(0);
        return 0L;
      }
      case Win32.WM_CLOSE -> {
        close();
        return 0L;
      }
      case Win32.WM_SIZE -> {
        resizeWebView2(hWnd);
        return 0L;
      }
    }
    if (msg == WM_DISPATCH) {
      Runnable r;
      while ((r = pending.poll()) != null) r.run();
      return 0L;
    }
    try {
      return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
    } catch (Throwable t) { return 0L; }
  }

  // -------------------------------------------------------------------------
  // WebView2 message received upcall (ICoreWebView2WebMessageReceivedEventHandler::Invoke)
  // -------------------------------------------------------------------------

  @SuppressWarnings("unused")
  public int onWebMessage(MemorySegment self, MemorySegment sender, MemorySegment args) {
    try (Arena a = Arena.ofConfined()) {
      // ICoreWebView2WebMessageReceivedEventArgs::TryGetWebMessageAsString — vtable index 4
      MemorySegment vtbl   = args.get(ADDRESS, 0);
      MemorySegment fn     = vtbl.get(ADDRESS, 4 * 8L);
      MemorySegment ptrStr = a.allocate(ADDRESS);
      int hr = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)).invokeExact(args, ptrStr);
      if (hr == 0) {
        MemorySegment strAddr = ptrStr.get(ADDRESS, 0);
        String json = strAddr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
        onMessage(json);
      }
    } catch (Throwable t) { /* ignore */ }
    return 0; // S_OK
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void buildWndProcStub() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "wndProc",
          MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class))
          .bindTo(this);
      wndProcStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG),
          arenaStubs);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private void createWindow(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment hInstance = Win32.getModuleHandle();

      // WNDCLASSEXW structure
      MemorySegment wce = a.allocate(80); // sizeof(WNDCLASSEXW) on 64-bit
      wce.set(JAVA_INT, 0,  80);           // cbSize
      wce.set(JAVA_INT, 4,  0x0003);       // style = CS_HREDRAW | CS_VREDRAW
      wce.set(ADDRESS,  8,  wndProcStub);  // lpfnWndProc
      wce.set(JAVA_INT, 16, 0);            // cbClsExtra
      wce.set(JAVA_INT, 20, 0);            // cbWndExtra
      wce.set(ADDRESS,  24, hInstance);    // hInstance
      wce.set(ADDRESS,  32, MemorySegment.NULL); // hIcon
      wce.set(ADDRESS,  40, MemorySegment.NULL); // hCursor
      wce.set(ADDRESS,  48, MemorySegment.NULL); // hbrBackground
      wce.set(ADDRESS,  56, MemorySegment.NULL); // lpszMenuName
      String clsName = "AvajeWebview_" + System.identityHashCode(this);
      MemorySegment clsNameSeg = a.allocateFrom(clsName, StandardCharsets.UTF_16LE);
      wce.set(ADDRESS,  64, clsNameSeg);   // lpszClassName
      wce.set(ADDRESS,  72, MemorySegment.NULL); // hIconSm

      short _ = (short) Win32.RegisterClassExW.invokeExact(wce);

      hwnd = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0,                        // exStyle
          clsNameSeg,               // lpClassName
          a.allocateFrom("", StandardCharsets.UTF_16LE), // lpWindowName
          Win32.WS_OVERLAPPEDWINDOW,
          Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT,
          width, height,
          Win32.CW_USEDEFAULT,     // hWndParent (int used as null)
          MemorySegment.NULL, hInstance, MemorySegment.NULL);

      Win32.showWindow(hwnd, Win32.SW_SHOW);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private void initWebView2(boolean debug) {
    try {
      // Build env-completed handler COM object
      MemorySegment envHandler = buildComCallback(2, (self, args) -> {
        // args[0] = HRESULT, args[1] = ICoreWebView2Environment*
        int hr = (int) args[0].get(JAVA_INT, 0); // actually args[0] is HRESULT int
        // Simplified: args[0] is first arg after "this"
        // The invoke signature: (this, HRESULT result, ICoreWebView2Environment* env) → HRESULT
        // We'll get the env from the 2nd extra arg slot
        return 0; // placeholder
      });

      // Use the real approach: build each handler with a specific upcall
      MemorySegment envCompletedHandler = buildEnvCompletedHandler(debug);

      int hr = (int) CREATE_WEBVIEW2_ENV.invokeExact(
          MemorySegment.NULL,         // browserExecutableFolder (null = use installed Edge)
          MemorySegment.NULL,         // userDataFolder (null = default)
          MemorySegment.NULL,         // options (null = default)
          envCompletedHandler);
      if (hr != 0) throw new RuntimeException("CreateCoreWebView2EnvironmentWithOptions failed: 0x" + Integer.toHexString(hr));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private MemorySegment buildEnvCompletedHandler(boolean debug) {
    // ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler
    // vtable: [QueryInterface, AddRef, Release, Invoke(HRESULT, ICoreWebView2Environment*)]
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onEnvCompleted",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      MemorySegment invokeStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs);
      return buildComObject(invokeStub);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onEnvCompleted(MemorySegment self, int hr, MemorySegment env) {
    if (hr != 0) { wv2Ready.countDown(); return hr; }
    try {
      MemorySegment ctrlHandler = buildControllerCompletedHandler();
      // ICoreWebView2Environment::CreateCoreWebView2Controller — vtable index 3
      MemorySegment vtbl = env.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 3 * 8L);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(env, hwnd, ctrlHandler);
    } catch (Throwable t) { wv2Ready.countDown(); }
    return 0;
  }

  private MemorySegment buildControllerCompletedHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onControllerCompleted",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      MemorySegment invokeStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs);
      return buildComObject(invokeStub);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  public int onControllerCompleted(MemorySegment self, int hr, MemorySegment controller) {
    if (hr != 0) { wv2Ready.countDown(); return hr; }
    wv2Controller = controller;
    // ICoreWebView2Controller::get_CoreWebView2 — vtable index 8
    try {
      MemorySegment vtbl = controller.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 8 * 8L);
      MemorySegment pWv2 = Arena.global().allocate(ADDRESS);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(controller, pWv2);
      wv2 = pWv2.get(ADDRESS, 0);
      registerWebMessageHandler();
      resizeWebView2(hwnd);
    } catch (Throwable t) { /* ignore */ }
    wv2Ready.countDown();
    return 0;
  }

  private void registerWebMessageHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onWebMessage",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      MemorySegment invokeStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs);
      MemorySegment handler = buildComObject(invokeStub);
      // ICoreWebView2::add_WebMessageReceived — vtable index 36
      MemorySegment vtbl = wv2.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 36 * 8L);
      MemorySegment pToken = Arena.global().allocate(JAVA_LONG);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(wv2, handler, pToken);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private void resizeWebView2(MemorySegment hWnd) {
    if (wv2Controller == null) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment rect = a.allocate(Win32.RECT_LAYOUT);
      Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("GetClientRect").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(hWnd, rect);
      // ICoreWebView2Controller::put_Bounds — vtable index 4
      MemorySegment vtbl = wv2Controller.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 4 * 8L);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(wv2Controller, rect);
    } catch (Throwable t) { /* ignore size errors */ }
  }

  // -------------------------------------------------------------------------
  // COM helpers
  // -------------------------------------------------------------------------

  /** Creates a minimal COM object: [vtable_ptr → [qiStub, addRef, release, invokeStub]]. */
  private MemorySegment buildComObject(MemorySegment invokeStub) {
    Arena a = arenaStubs; // uses the instance arena so it lives long enough

    // Stubs for QI, AddRef, Release
    MemorySegment qiStub = buildQIStub(a);
    MemorySegment addRefStub = buildAddRefStub(a);
    MemorySegment releaseStub = buildReleaseStub(a);

    // Vtable: 4 pointers
    MemorySegment vtable = a.allocate(ADDRESS, 4);
    vtable.setAtIndex(ADDRESS, 0, qiStub);
    vtable.setAtIndex(ADDRESS, 1, addRefStub);
    vtable.setAtIndex(ADDRESS, 2, releaseStub);
    vtable.setAtIndex(ADDRESS, 3, invokeStub);

    // COM object: single pointer to vtable
    MemorySegment obj = a.allocate(ADDRESS);
    obj.set(ADDRESS, 0, vtable);
    return obj;
  }

  private static MemorySegment buildQIStub(Arena a) {
    try {
      MethodHandle mh = MethodHandles.lookup().findStatic(Win32WebView.class, "comQI",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
      return Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), a);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  private static MemorySegment buildAddRefStub(Arena a) {
    try {
      MethodHandle mh = MethodHandles.lookup().findStatic(Win32WebView.class, "comAddRef",
          MethodType.methodType(long.class, MemorySegment.class));
      return Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_LONG, ADDRESS), a);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  private static MemorySegment buildReleaseStub(Arena a) {
    try {
      MethodHandle mh = MethodHandles.lookup().findStatic(Win32WebView.class, "comRelease",
          MethodType.methodType(long.class, MemorySegment.class));
      return Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_LONG, ADDRESS), a);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unused")
  private static int comQI(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
    ppv.set(ADDRESS, 0, self);
    return 0; // S_OK
  }

  @SuppressWarnings("unused")
  private static long comAddRef(MemorySegment self) { return 1L; }

  @SuppressWarnings("unused")
  private static long comRelease(MemorySegment self) { return 1L; }

  private static void comCallWithArg(MemorySegment comObj, int vtableIdx, String arg) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment vtbl = comObj.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, (long) vtableIdx * 8L);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(comObj, a.allocateFrom(arg, StandardCharsets.UTF_16LE));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private static int comCall(MemorySegment comObj, int vtableIdx, FunctionDescriptor desc) {
    try {
      MemorySegment vtbl = comObj.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, (long) vtableIdx * 8L);
      return (int) Linker.nativeLinker().downcallHandle(fn, desc).invokeExact(comObj);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  // Placeholder for the functional interface used in initWebView2
  @FunctionalInterface
  private interface ComCallback {
    int invoke(MemorySegment self, MemorySegment[] args);
  }

  private static MemorySegment buildComCallback(int extraArgCount, ComCallback cb) {
    // Placeholder; real stubs are built per-callback type above
    return MemorySegment.NULL;
  }
}
