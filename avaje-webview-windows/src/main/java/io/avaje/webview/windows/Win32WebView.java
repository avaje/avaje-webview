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
 * Requires Microsoft Edge WebView2 Runtime to be installed and
 * {@code WebView2Loader.dll} to be on the library path.
 *
 * Thread model: the Win32 message loop drives everything. Cross-thread calls post a
 * custom WM_DISPATCH message (WM_APP+1) to the HWND; the WndProc drains pendingDispatches
 * when it receives that message.
 *
 * WebView2 is async: CreateCoreWebView2EnvironmentWithOptions kicks off a chain of COM
 * callbacks (env → controller → WebView2 object). We block the constructor on wv2Ready
 * until the chain completes, so callers always get a fully-initialised object.
 *
 * COM calling convention: COM interface methods are called by index into a vtable.
 * Every COM object in memory is a pointer to a pointer-to-vtable:
 *   comObj → vtable[] → [method0, method1, ..., methodN]
 * We look up the function pointer by index and call it via a Panama downcall handle.
 * IUnknown (QI, AddRef, Release) occupies indices 0-2 on every COM interface.
 */
final class Win32WebView extends WebviewBase {

  // WM_APP + 1 — custom message in the safe WM_APP range (0x8000–0xBFFF).
  // PostMessageW(hwnd, WM_DISPATCH, 0, 0) wakes the message loop to drain pending runnables.
  private static final int WM_DISPATCH = 0x8000 + 1;

  // WebView2Loader.dll — ships with the Edge WebView2 Runtime.
  // CreateCoreWebView2EnvironmentWithOptions(browserFolder, userDataFolder, options, handler) → HRESULT
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

  // All upcall stubs (WndProc, COM callbacks) must stay alive as long as the window.
  // ofShared() because close() may be called from a thread that didn't create the stubs.
  private final Arena arenaStubs = Arena.ofShared();

  private volatile MemorySegment hwnd;         // HWND — Win32 window handle
  private MemorySegment wndProcStub;            // C function pointer wrapping wndProc()

  // ICoreWebView2Controller* and ICoreWebView2* — set asynchronously by COM callbacks
  private volatile MemorySegment wv2Controller;
  private volatile MemorySegment wv2;
  // Blocks the constructor until the async WebView2 init chain completes
  private final CountDownLatch wv2Ready = new CountDownLatch(1);

  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

  Win32WebView(boolean debug, int width, int height) {
    openWindows.incrementAndGet();
    buildWndProcStub();     // create the C function pointer for RegisterClassExW
    createWindow(width, height);
    initWebView2(debug);    // starts the async COM callback chain
    // Block until env → controller → webview2 chain completes (usually <1s)
    try { wv2Ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  // -------------------------------------------------------------------------
  // Webview — event loop
  // -------------------------------------------------------------------------

  @Override
  public void run() {
    // Standard Win32 message loop: GetMessage blocks until a message arrives,
    // TranslateMessage handles keyboard events (WM_KEYDOWN → WM_CHAR), then DispatchMessage
    // routes the message to wndProc. Loop exits when GetMessage returns 0 (WM_QUIT received).
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
      // DestroyWindow must be called on the message-loop thread; dispatch it there.
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
      // ICoreWebView2Controller::Close() — vtable index 8 — releases the WebView2 resources.
      comCall(wv2Controller, 8, FunctionDescriptor.of(JAVA_INT, ADDRESS));
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

  // -------------------------------------------------------------------------
  // WebviewBase — platform impls (dispatched to the Win32 message-loop thread)
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
      // SetWindowTextW(HWND, LPCWSTR) — standard Win32, not COM
      Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("SetWindowTextW").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(hwnd, a.allocateFrom(title, StandardCharsets.UTF_16LE));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    try {
      // SWP_NOZORDER keeps the Z-order; SWP_SHOWWINDOW ensures the window is visible.
      int _ = (int) Win32.SetWindowPos.invokeExact(hwnd, MemorySegment.NULL,
          0, 0, width, height, Win32.SWP_NOZORDER | Win32.SWP_SHOWWINDOW);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    // Min/max size is enforced in WM_GETMINMAXINFO inside WndProc.
    // Implementing that requires storing the limits and handling the message — not done yet.
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    // Same as above — WM_GETMINMAXINFO is the right hook.
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try {
      // Strip WS_SIZEBOX (resize border) and WS_MAXIMIZEBOX from the window style.
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
    // ICoreWebView2::ExecuteScript(LPCWSTR js, handler) — vtable index 37
    // NULL handler = fire-and-forget; we don't need the return value.
    try (Arena a = Arena.ofConfined()) {
      MemorySegment vtbl = wv2.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 37 * 8L); // 8 bytes per pointer on 64-bit
      Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(wv2, a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    // Queue the runnable, then post WM_DISPATCH to wake the message loop.
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
    // ICoreWebView2::AddScriptToExecuteOnDocumentCreated — vtable index 49
    // NULL handler = async, fire-and-forget; we don't need the scriptId token.
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
    // WebView2 doesn't expose a bulk RemoveAllScripts API; scripts accumulate.
    // The only workaround is to recreate the WebView2 environment, which is too expensive.
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
  // WndProc — the Win32 window procedure, called by DispatchMessageW
  // -------------------------------------------------------------------------

  /**
   * LRESULT CALLBACK WndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam)
   *
   * Win32 routes all window messages here. We handle the ones we care about and
   * forward the rest to DefWindowProcW (the default handler).
   */
  @SuppressWarnings("unused")
  public long wndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    switch (msg) {
      case Win32.WM_DESTROY -> {
        // Window is being destroyed. If this was the last open window, post WM_QUIT
        // to exit the GetMessage loop and let run() return.
        if (openWindows.decrementAndGet() == 0) Win32.postQuitMessage(0);
        return 0L;
      }
      case Win32.WM_CLOSE -> {
        // User clicked the X button. close() cleans up and eventually destroys the window.
        close();
        return 0L;
      }
      case Win32.WM_SIZE -> {
        // Window was resized — tell WebView2 to fill the new client area.
        resizeWebView2(hWnd);
        return 0L;
      }
    }
    if (msg == WM_DISPATCH) {
      // Our custom cross-thread wake message — drain the pending queue.
      Runnable r;
      while ((r = pending.poll()) != null) r.run();
      return 0L;
    }
    try {
      return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
    } catch (Throwable t) { return 0L; }
  }

  // -------------------------------------------------------------------------
  // ICoreWebView2WebMessageReceivedEventHandler::Invoke — COM callback from WebView2
  // -------------------------------------------------------------------------

  /**
   * Called by WebView2 when JS posts a message (window.__webview__.postMessage(...)).
   * COM signature: HRESULT Invoke(ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs*)
   * self = the COM handler object we built; we ignore it and use the event args directly.
   */
  @SuppressWarnings("unused")
  public int onWebMessage(MemorySegment self, MemorySegment sender, MemorySegment args) {
    try (Arena a = Arena.ofConfined()) {
      // ICoreWebView2WebMessageReceivedEventArgs::TryGetWebMessageAsString — vtable index 4
      // Writes a newly-allocated LPWSTR into ptrStr; caller owns the string.
      MemorySegment vtbl   = args.get(ADDRESS, 0);
      MemorySegment fn     = vtbl.get(ADDRESS, 4 * 8L);
      MemorySegment ptrStr = a.allocate(ADDRESS);
      int hr = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)).invokeExact(args, ptrStr);
      if (hr == 0) {
        MemorySegment strAddr = ptrStr.get(ADDRESS, 0);
        // reinterpret(MAX_VALUE) is needed because Panama doesn't know the native string length.
        String json = strAddr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
        onMessage(json);
      }
    } catch (Throwable t) { /* ignore */ }
    return 0; // S_OK
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * Wraps wndProc() as a C function pointer via Panama upcallStub.
   * WNDPROC signature: LRESULT(*)(HWND, UINT, WPARAM, LPARAM)
   * Registered in the WNDCLASSEXW struct so Win32 calls us for every window message.
   */
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

  /**
   * Registers a Win32 window class and creates the HWND.
   * WNDCLASSEXW is laid out manually as a MemorySegment — offsets match the 64-bit ABI struct.
   */
  private void createWindow(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment hInstance = Win32.getModuleHandle();

      // WNDCLASSEXW — 80 bytes on 64-bit Windows
      MemorySegment wce = a.allocate(80);
      wce.set(JAVA_INT, 0,  80);           // cbSize = sizeof(WNDCLASSEXW)
      wce.set(JAVA_INT, 4,  0x0003);       // style = CS_HREDRAW | CS_VREDRAW (redraw on resize)
      wce.set(ADDRESS,  8,  wndProcStub);  // lpfnWndProc = our Panama upcall stub
      wce.set(JAVA_INT, 16, 0);            // cbClsExtra
      wce.set(JAVA_INT, 20, 0);            // cbWndExtra
      wce.set(ADDRESS,  24, hInstance);    // hInstance
      wce.set(ADDRESS,  32, MemorySegment.NULL); // hIcon
      wce.set(ADDRESS,  40, MemorySegment.NULL); // hCursor
      wce.set(ADDRESS,  48, MemorySegment.NULL); // hbrBackground (null = no background erase)
      wce.set(ADDRESS,  56, MemorySegment.NULL); // lpszMenuName
      String clsName = "AvajeWebview_" + System.identityHashCode(this);
      MemorySegment clsNameSeg = a.allocateFrom(clsName, StandardCharsets.UTF_16LE);
      wce.set(ADDRESS,  64, clsNameSeg);   // lpszClassName (unique per instance)
      wce.set(ADDRESS,  72, MemorySegment.NULL); // hIconSm

      short _ = (short) Win32.RegisterClassExW.invokeExact(wce);

      hwnd = (MemorySegment) Win32.CreateWindowExW.invokeExact(
          0,                        // exStyle
          clsNameSeg,               // lpClassName (must match what we registered)
          a.allocateFrom("", StandardCharsets.UTF_16LE), // lpWindowName (title — set later)
          Win32.WS_OVERLAPPEDWINDOW,
          Win32.CW_USEDEFAULT, Win32.CW_USEDEFAULT, // position (OS picks)
          width, height,
          Win32.CW_USEDEFAULT,      // hWndParent (treated as null here)
          MemorySegment.NULL, hInstance, MemorySegment.NULL);

      Win32.showWindow(hwnd, Win32.SW_SHOW);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Starts the async WebView2 initialisation chain:
   *   CreateCoreWebView2EnvironmentWithOptions → onEnvCompleted
   *   → CreateCoreWebView2Controller → onControllerCompleted
   *   → get_CoreWebView2 → wv2 is ready; wv2Ready.countDown()
   *
   * All three steps involve COM callback objects we build via buildComObject().
   * NULL for browserExecutableFolder and userDataFolder means "use system Edge + default profile".
   */
  private void initWebView2(boolean debug) {
    try {
      MemorySegment envCompletedHandler = buildEnvCompletedHandler(debug);
      int hr = (int) CREATE_WEBVIEW2_ENV.invokeExact(
          MemorySegment.NULL,  // browserExecutableFolder — null = use installed Edge
          MemorySegment.NULL,  // userDataFolder — null = default (%LOCALAPPDATA%\...)
          MemorySegment.NULL,  // options — null = defaults
          envCompletedHandler);
      if (hr != 0) throw new RuntimeException("CreateCoreWebView2EnvironmentWithOptions failed: 0x" + Integer.toHexString(hr));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /**
   * Builds the ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler COM object.
   * Vtable layout: [QI, AddRef, Release, Invoke(HRESULT, ICoreWebView2Environment*)]
   */
  private MemorySegment buildEnvCompletedHandler(boolean debug) {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onEnvCompleted",
          MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class))
          .bindTo(this);
      MemorySegment invokeStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs);
      return buildComObject(invokeStub);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  /** Step 2 of the async chain — the environment is ready; create the controller. */
  @SuppressWarnings("unused")
  public int onEnvCompleted(MemorySegment self, int hr, MemorySegment env) {
    if (hr != 0) { wv2Ready.countDown(); return hr; }
    try {
      MemorySegment ctrlHandler = buildControllerCompletedHandler();
      // ICoreWebView2Environment::CreateCoreWebView2Controller(HWND, handler) — vtable index 3
      MemorySegment vtbl = env.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 3 * 8L);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(env, hwnd, ctrlHandler);
    } catch (Throwable t) { wv2Ready.countDown(); }
    return 0;
  }

  /** Builds the ICoreWebView2CreateCoreWebView2ControllerCompletedHandler COM object. */
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

  /** Step 3 — controller is ready; extract the ICoreWebView2 and register the message handler. */
  @SuppressWarnings("unused")
  public int onControllerCompleted(MemorySegment self, int hr, MemorySegment controller) {
    if (hr != 0) { wv2Ready.countDown(); return hr; }
    wv2Controller = controller;
    try {
      // ICoreWebView2Controller::get_CoreWebView2(ICoreWebView2**) — vtable index 8
      MemorySegment vtbl = controller.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 8 * 8L);
      MemorySegment pWv2 = Arena.global().allocate(ADDRESS);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(controller, pWv2);
      wv2 = pWv2.get(ADDRESS, 0); // dereference the out-pointer to get the ICoreWebView2*
      registerWebMessageHandler();
      resizeWebView2(hwnd); // size WebView2 to fill the window immediately
    } catch (Throwable t) { /* ignore */ }
    wv2Ready.countDown(); // unblock the constructor
    return 0;
  }

  /** Subscribes to WebMessageReceived so JS postMessage() calls reach onWebMessage(). */
  private void registerWebMessageHandler() {
    try {
      var mh = MethodHandles.lookup().findVirtual(Win32WebView.class, "onWebMessage",
          MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
          .bindTo(this);
      MemorySegment invokeStub = Linker.nativeLinker().upcallStub(mh,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs);
      MemorySegment handler = buildComObject(invokeStub);
      // ICoreWebView2::add_WebMessageReceived(handler, token*) — vtable index 36
      MemorySegment vtbl = wv2.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, 36 * 8L);
      MemorySegment pToken = Arena.global().allocate(JAVA_LONG); // EventRegistrationToken out-param
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
          .invokeExact(wv2, handler, pToken);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Fits WebView2 to the current window client area. Called on WM_SIZE and after init. */
  private void resizeWebView2(MemorySegment hWnd) {
    if (wv2Controller == null) return;
    try (Arena a = Arena.ofConfined()) {
      // GetClientRect fills a RECT {left, top, right, bottom} — the bounds WebView2 expects.
      MemorySegment rect = a.allocate(Win32.RECT_LAYOUT);
      Linker.nativeLinker().downcallHandle(
          SymbolLookup.libraryLookup("user32", Arena.global()).find("GetClientRect").orElseThrow(),
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(hWnd, rect);
      // ICoreWebView2Controller::put_Bounds(RECT) — vtable index 4
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

  /**
   * Builds a minimal COM object with a 4-entry vtable: [QI, AddRef, Release, invokeStub].
   *
   * COM objects in memory look like this:
   *   comObj → vtablePtr → [fn0, fn1, fn2, fn3, ...]
   * We allocate the vtable array and a single-pointer "object" that points to it.
   * IUnknown (indices 0-2) is trivially implemented: QI returns self, ref counts are fake.
   * The real logic lives at index 3 (Invoke).
   */
  private MemorySegment buildComObject(MemorySegment invokeStub) {
    Arena a = arenaStubs; // must outlive the COM callbacks

    MemorySegment qiStub = buildQIStub(a);
    MemorySegment addRefStub = buildAddRefStub(a);
    MemorySegment releaseStub = buildReleaseStub(a);

    // vtable: array of 4 function pointers
    MemorySegment vtable = a.allocate(ADDRESS, 4);
    vtable.setAtIndex(ADDRESS, 0, qiStub);
    vtable.setAtIndex(ADDRESS, 1, addRefStub);
    vtable.setAtIndex(ADDRESS, 2, releaseStub);
    vtable.setAtIndex(ADDRESS, 3, invokeStub);

    // The "COM object" is just a pointer to the vtable pointer.
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

  // IUnknown::QueryInterface — returns self for any IID (good enough for single-interface objects)
  @SuppressWarnings("unused")
  private static int comQI(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
    ppv.set(ADDRESS, 0, self);
    return 0; // S_OK
  }

  // IUnknown::AddRef / Release — fake ref count; object lifetime is managed by the Arena
  @SuppressWarnings("unused")
  private static long comAddRef(MemorySegment self) { return 1L; }

  @SuppressWarnings("unused")
  private static long comRelease(MemorySegment self) { return 1L; }

  /** Calls a COM method that takes a single LPCWSTR argument (Navigate, NavigateToString, etc.). */
  private static void comCallWithArg(MemorySegment comObj, int vtableIdx, String arg) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment vtbl = comObj.get(ADDRESS, 0);
      MemorySegment fn   = vtbl.get(ADDRESS, (long) vtableIdx * 8L);
      int _ = (int) Linker.nativeLinker().downcallHandle(fn,
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
          .invokeExact(comObj, a.allocateFrom(arg, StandardCharsets.UTF_16LE));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Calls a COM method that takes only `this` and returns HRESULT. */
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
