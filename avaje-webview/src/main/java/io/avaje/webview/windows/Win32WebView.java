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
import java.util.function.BooleanSupplier;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

/**
 * Windows WebView2 implementation
 *
 * <p>It creates Three Win32 windows (main, widget, message-only), one combined COM handler object
 * for env+ctrl callbacks (matching reference's single webview2_com_handler), and all init work
 * deferred to run inside the message pump via msgWndProc so that nested pumping for
 * AddScriptToExecuteOnDocumentCreated completions works.
 */
public final class Win32WebView extends WebviewBase {

  private static final String EDGE_RELEASE_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
  private static final String EDGE_UPDATE_KEY =
      "SOFTWARE\\Microsoft\\EdgeUpdate\\ClientState\\" + EDGE_RELEASE_GUID;

  private static final MethodHandle CREATE_ENV_FN;
  private static final boolean USE_LOADER_DLL;

  static {
    MethodHandle fn = null;
    var loaderDll = false;

    try {
      final var lib = SymbolLookup.libraryLookup("WebView2Loader.dll", Arena.global());
      fn =
          Linker.nativeLinker()
              .downcallHandle(
                  lib.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(),
                  FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      loaderDll = true;
    } catch (final Exception ignored) {
    }

    if (fn == null) {
      final var ebWebViewPath = findEbWebViewFromRegistry();
      if (ebWebViewPath != null) {
        try (var a = Arena.ofConfined()) {
          final var hLib =
              (MemorySegment)
                  Win32.LoadLibraryW.invokeExact(
                      a.allocateFrom(ebWebViewPath, StandardCharsets.UTF_16LE));
          if (hLib.address() != 0) {
            final var fnAddr =
                (MemorySegment)
                    Win32.GetProcAddress.invokeExact(
                        hLib, a.allocateFrom("CreateWebViewEnvironmentWithOptionsInternal"));
            if (fnAddr.address() != 0) {
              fn =
                  Linker.nativeLinker()
                      .downcallHandle(
                          fnAddr,
                          FunctionDescriptor.of(
                              JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            }
          }
        } catch (final Throwable ignored) {
        }
      }
    }

    if (fn == null)
      throw new UnsatisfiedLinkError(
          "WebView2 not available - install Microsoft Edge or the WebView2 Runtime");
    CREATE_ENV_FN = fn;
    USE_LOADER_DLL = loaderDll;
  }

  private static String findEbWebViewFromRegistry() {
    final var osArch = System.getProperty("os.arch", "");
    final var arch =
        "amd64".equals(osArch) || "x86_64".equals(osArch)
            ? "x64"
            : "x86".equals(osArch) || "i386".equals(osArch) ? "x86" : "arm64";
    for (final long root : new long[] {Win32.HKEY_LOCAL_MACHINE, Win32.HKEY_CURRENT_USER}) {
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

  private static final AtomicInteger openWindows = new AtomicInteger(0);

  private final Arena arenaStubs = Arena.ofShared();

  private volatile MemorySegment hwnd, hwndMsg, hwndWidget;

  private volatile ComController controller;
  private volatile ComWebView2 webView2;

  /**
   * Set to {@code true} by the init chain ({@code doInitTasks} or an error path) to signal the init
   * pump loop in {@link #embedWebView2} to exit. Volatile so the pump thread sees the write from
   * the COM callback thread.
   */
  private volatile boolean webviewReady;

  private volatile boolean closed;
  private boolean debugMode;

  private final List<String> scriptIds = new ArrayList<>();
  // FIFO queue pairing each nativeAddUserScript call to its completion.
  private final ConcurrentLinkedQueue<Runnable> scriptDoneCallbacks = new ConcurrentLinkedQueue<>();

  private volatile int minW, minH, maxW, maxH;

  /**
   * Cross-thread dispatch queue. Tasks added by any thread are drained on the UI thread inside
   * {@code msgWndProc} when a {@link Win32#WM_APP} message is received (except during nested pump
   * depth &gt; 0).
   */
  private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();

  /**
   * Tracks re-entrant pump nesting depth. When {@link #nativeAddUserScript} starts a nested pump to
   * wait for {@code AddScriptToExecuteOnDocumentCreated} completion, this depth goes to 1. While
   * depth > 0, {@code msgWndProc} must not drain {@link #pending} - only COM completion callbacks
   * (delivered via IPC, not WM_APP) should be processed.
   */
  private int nestedPumpDepth = 0;

  // Combined env+ctrl handler - one COM object for both, state-dispatched.
  private MemorySegment combinedHandler;

  /**
   * Dispatch flag for the combined env+ctrl COM handler. {@code false} during the
   * environment-completed phase, {@code true} once {@code CreateCoreWebView2Controller} is called
   * and the next callback will be the controller-completed phase.
   */
  private volatile boolean ctrlPhase; // false=env callback, true=ctrl callback

  // WndProc upcall stubs
  private MemorySegment mainWndProcStub, msgWndProcStub, widgetWndProcStub;
  private MemorySegment cachedQI, cachedAddRef, cachedRelease;

  public Win32WebView(boolean debug, int width, int height) {
    openWindows.incrementAndGet();
    Win32.coInitialize();
    buildWndProcStubs();
    createWindows(width, height);
    embedWebView2(debug);
  }

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
      try {
        final var _ = (int) Win32.DestroyWindow.invokeExact(hwnd);
      } catch (final Throwable ignored) {
      }
    }
  }

  @Override
  public MemorySegment nativeWindowPointer() {
    return hwnd != null ? hwnd : MemorySegment.NULL;
  }

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
      final var _ =
          (int)
              Win32.SetWindowPos.invokeExact(
                  hwnd,
                  MemorySegment.NULL,
                  0,
                  0,
                  width,
                  height,
                  Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    minW = width;
    minH = height;
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    maxW = width;
    maxH = height;
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try {
      final var style = (int) Win32.GetWindowLong.invokeExact(hwnd, Win32.GWL_STYLE);
      final var _ =
          (int)
              Win32.SetWindowLong.invokeExact(
                  hwnd, Win32.GWL_STYLE, style & ~(Win32.WS_THICKFRAME | Win32.WS_MAXIMIZEBOX));
      Win32.SetWindowPos.invokeExact(
          hwnd,
          MemorySegment.NULL,
          0,
          0,
          width,
          height,
          Win32.SWP_NOZORDER | Win32.SWP_FRAMECHANGED);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
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
      try {
        final var _ = (int) Win32.PostMessageW.invokeExact(hwndMsg, Win32.WM_APP, 0L, 0L);
      } catch (final Throwable t) {
        throw new RuntimeException(t);
      }
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
        pumpLoop(() -> done[0]);
      } finally {
        nestedPumpDepth--;
      }
    } else {
      scriptDoneCallbacks.poll();
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    for (final String id : scriptIds) webView2.removeScriptToExecuteOnDocumentCreated(id);
    scriptIds.clear();
  }

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
    try {
      setIcon(Path.of(uri));
    } catch (final Exception ignored) {
    }
  }

  /**
   * Creates upcall stubs for the three WndProc callbacks and stores them in {@link #arenaStubs}.
   * Must be called before {@link #createWindows} because the stubs are passed as the {@code
   * lpfnWndProc} field of each {@code WNDCLASSEXW}.
   */
  private void buildWndProcStubs() {
    final var linker = Linker.nativeLinker();
    final var fd = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    try {
      final var lookup = MethodHandles.lookup();
      mainWndProcStub =
          linker.upcallStub(
              lookup
                  .findVirtual(
                      Win32WebView.class,
                      "mainWndProc",
                      MethodType.methodType(
                          long.class, MemorySegment.class, int.class, long.class, long.class))
                  .bindTo(this),
              fd,
              arenaStubs);
      msgWndProcStub =
          linker.upcallStub(
              lookup
                  .findVirtual(
                      Win32WebView.class,
                      "msgWndProc",
                      MethodType.methodType(
                          long.class, MemorySegment.class, int.class, long.class, long.class))
                  .bindTo(this),
              fd,
              arenaStubs);
      widgetWndProcStub =
          linker.upcallStub(
              lookup
                  .findVirtual(
                      Win32WebView.class,
                      "widgetWndProc",
                      MethodType.methodType(
                          long.class, MemorySegment.class, int.class, long.class, long.class))
                  .bindTo(this),
              fd,
              arenaStubs);
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * WndProc for the main (top-level) window. Handles:
   *
   * <ul>
   *   <li>{@link Win32#WM_DESTROY} - decrements open-window count; posts {@code WM_QUIT} when the
   *       last window is destroyed.
   *   <li>{@link Win32#WM_CLOSE} - delegates to {@link #close()}.
   *   <li>{@link Win32#WM_SIZE} - resizes the widget child window to match the new client area.
   *   <li>{@link Win32#WM_ACTIVATE} - moves WebView2 focus to the controller on activation.
   *   <li>{@link Win32#WM_GETMINMAXINFO} - enforces min/max size constraints.
   *   <li>{@link Win32#WM_SETTINGCHANGE} - re-applies dark mode when the system color scheme
   *       changes ({@code ImmersiveColorSet} area).
   * </ul>
   */
  @SuppressWarnings("unused")
  public long mainWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    switch (msg) {
      case Win32.WM_DESTROY -> {
        if (openWindows.decrementAndGet() == 0) Win32.postQuitMessage(0);
        return 0;
      }
      case Win32.WM_CLOSE -> {
        close();
        return 0;
      }
      case Win32.WM_SIZE -> {
        resizeWidget(hWnd);
        return 0;
      }
      case Win32.WM_ACTIVATE -> {
        if ((int) (wParam & 0xFFFF) != Win32.WA_INACTIVE) focusWebView2();
        return 0;
      }
      case Win32.WM_GETMINMAXINFO -> {
        applyMinMaxInfo(lParam);
        return 0;
      }
      case Win32.WM_SETTINGCHANGE -> {
        if (lParam != 0) {
          try {
            final var area =
                MemorySegment.ofAddress(lParam)
                    .reinterpret(256 * 2)
                    .getString(0, StandardCharsets.UTF_16LE);
            if ("ImmersiveColorSet".equals(area)) {
              Win32.applyDarkMode(hWnd, isDarkTheme());
            }
          } catch (final Exception ignored) {
          }
        }
        return 0;
      }
    }
    try {
      return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
    } catch (final Throwable t) {
      return 0;
    }
  }

  /**
   * WndProc for the message-only window ({@code HWND_MESSAGE} parent). Handles {@link Win32#WM_APP}
   * by draining the {@link #pending} queue on the UI thread.
   *
   * <p>When {@link #nestedPumpDepth} is greater than zero (inside {@link #nativeAddUserScript}'s
   * nested pump), pending tasks are intentionally skipped - the nested pump only needs COM
   * completion callbacks delivered via WebView2's IPC, not general dispatch tasks.
   */
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
    try {
      return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
    } catch (final Throwable t) {
      return 0;
    }
  }

  /**
   * WndProc for the WebView2 widget (child) window. Handles {@link Win32#WM_SIZE} by updating the
   * WebView2 controller bounds to fill the new client area.
   */
  @SuppressWarnings("unused")
  public long widgetWndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
    if (msg == Win32.WM_SIZE) {
      resizeWebView2(hWnd);
      return 0;
    }
    try {
      return (long) Win32.DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
    } catch (final Throwable t) {
      return 0;
    }
  }

  /**
   * Creates the three Win32 windows required by the WebView2 embedding model:
   *
   * <ol>
   *   <li>Main window - top-level, visible, owns the title bar and chrome.
   *   <li>Widget window - {@code WS_CHILD} of main; hosts the WebView2 controller.
   *   <li>Message-only window - {@code HWND_MESSAGE} parent; receives {@link Win32#WM_APP} for
   *       cross-thread dispatch.
   * </ol>
   *
   * Each window gets its own registered class with a unique name (keyed on {@link
   * System#identityHashCode} to allow multiple simultaneous windows).
   */
  private void createWindows(int width, int height) {
    try (var a = Arena.ofConfined()) {
      final var hInstance = Win32.getModuleHandle();

      final var mainCls = "AvajeWebView_" + System.identityHashCode(this);
      registerClass(a, hInstance, mainCls, mainWndProcStub);
      hwnd =
          (MemorySegment)
              Win32.CreateWindowExW.invokeExact(
                  0,
                  a.allocateFrom(mainCls, StandardCharsets.UTF_16LE),
                  a.allocateFrom("", StandardCharsets.UTF_16LE),
                  Win32.WS_OVERLAPPEDWINDOW,
                  Win32.CW_USEDEFAULT,
                  Win32.CW_USEDEFAULT,
                  width,
                  height,
                  MemorySegment.NULL,
                  MemorySegment.NULL,
                  hInstance,
                  MemorySegment.NULL);
      if (hwnd == null || hwnd.address() == 0)
        throw new RuntimeException("CreateWindowExW (main) failed");

      final var widgetCls = "AvajeWebView_Widget_" + System.identityHashCode(this);
      registerClass(a, hInstance, widgetCls, widgetWndProcStub);
      hwndWidget =
          (MemorySegment)
              Win32.CreateWindowExW.invokeExact(
                  0x10000 /* WS_EX_CONTROLPARENT */,
                  a.allocateFrom(widgetCls, StandardCharsets.UTF_16LE),
                  MemorySegment.NULL,
                  Win32.WS_CHILD,
                  0,
                  0,
                  0,
                  0,
                  hwnd,
                  MemorySegment.NULL,
                  hInstance,
                  MemorySegment.NULL);
      if (hwndWidget == null || hwndWidget.address() == 0)
        throw new RuntimeException("CreateWindowExW (widget) failed");

      final var msgCls = "AvajeWebView_Msg_" + System.identityHashCode(this);
      registerClass(a, hInstance, msgCls, msgWndProcStub);
      hwndMsg =
          (MemorySegment)
              Win32.CreateWindowExW.invokeExact(
                  0,
                  a.allocateFrom(msgCls, StandardCharsets.UTF_16LE),
                  MemorySegment.NULL,
                  0,
                  0,
                  0,
                  0,
                  0,
                  MemorySegment.ofAddress(-3L) /* HWND_MESSAGE */,
                  MemorySegment.NULL,
                  hInstance,
                  MemorySegment.NULL);
      if (hwndMsg == null || hwndMsg.address() == 0)
        throw new RuntimeException("CreateWindowExW (message) failed");

    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Registers a Win32 window class with the given WndProc stub. Fills a raw 80-byte {@code
   * WNDCLASSEXW} buffer directly rather than using a named layout, because this struct is
   * write-once at class registration time.
   */
  private static void registerClass(
      Arena a, MemorySegment hInstance, String cls, MemorySegment proc) {
    final var wce = a.allocate(80);
    wce.set(JAVA_INT, 0, 80);
    wce.set(JAVA_INT, 4, 0x0003); // CS_HREDRAW | CS_VREDRAW
    wce.set(ADDRESS, 8, proc);
    wce.set(JAVA_INT, 16, 0);
    wce.set(JAVA_INT, 20, 0);
    wce.set(ADDRESS, 24, hInstance);
    wce.set(ADDRESS, 32, MemorySegment.NULL);
    wce.set(ADDRESS, 40, MemorySegment.NULL);
    wce.set(ADDRESS, 48, MemorySegment.NULL);
    wce.set(ADDRESS, 56, MemorySegment.NULL);
    wce.set(ADDRESS, 64, a.allocateFrom(cls, StandardCharsets.UTF_16LE));
    wce.set(ADDRESS, 72, MemorySegment.NULL);
    try {
      final var _ = (short) Win32.RegisterClassExW.invokeExact(wce);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

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
          hr =
              (int)
                  CREATE_ENV_FN.invokeExact(
                      MemorySegment.NULL,
                      a.allocateFrom(userData, StandardCharsets.UTF_16LE),
                      MemorySegment.NULL,
                      combinedHandler);
        }
      } else {
        try (var a = Arena.ofConfined()) {
          hr =
              (int)
                  CREATE_ENV_FN.invokeExact(
                      1 /* isBuiltin */,
                      0 /* installed */,
                      a.allocateFrom(userData, StandardCharsets.UTF_16LE),
                      MemorySegment.NULL,
                      combinedHandler);
        }
      }
      if (hr != 0)
        throw new RuntimeException(
            "CreateCoreWebView2Environment failed: 0x" + Integer.toHexString(hr));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }

    // Pump until webviewReady - this exits AFTER the deferred init task in msgWndProc
    // completes (settings + scripts + show). The pump is still running when addScriptOnDoc
    // is called (via setupJsBridge -> nativeAddUserScript from within msgWndProc), so nested
    // pumping for completion delivery works correctly.
    pumpLoop(() -> webviewReady);
    if (webView2 == null) throw new RuntimeException("WebView2 initialization failed");
  }

  private MemorySegment buildCombinedHandler() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  Win32WebView.class,
                  "onCombinedInvoke",
                  MethodType.methodType(
                      int.class, MemorySegment.class, int.class, MemorySegment.class))
              .bindTo(this);
      return buildComObject(
          Linker.nativeLinker()
              .upcallStub(
                  mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Dispatches to env-completed or ctrl-completed phase based on {@link #ctrlPhase}. Called by
   * WebView2 for both CreateCoreWebView2EnvironmentCompletedHandler::Invoke and
   * CreateCoreWebView2ControllerCompletedHandler::Invoke
   */
  @SuppressWarnings("unused")
  public int onCombinedInvoke(MemorySegment self, int hr, MemorySegment ptr) {
    if (!ctrlPhase) {
      return onEnvCompleted(hr, ptr);
    }
    return onCtrlCompleted(hr, ptr);
  }

  private int onEnvCompleted(int hr, MemorySegment env) {
    if (hr != 0) {
      webviewReady = true;
      return hr;
    }
    // Pass combinedHandler as the ctrl handler - same object, mirrors reference's "this".
    // When WebView2 calls QI on it for ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
    // our QI returns self, which is this same handler.
    ctrlPhase = true;
    final var createCtrl = Win32.vtableFn(env, 3);
    try {
      final var mh =
          Linker.nativeLinker()
              .downcallHandle(
                  createCtrl, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      final var createHr = (int) mh.invokeExact(env, hwndWidget, combinedHandler);
      if (createHr != 0) {
        webviewReady = true;
      }
    } catch (final Throwable t) {
      webviewReady = true;
      throw new RuntimeException(t);
    }
    return 0;
  }

  private int onCtrlCompleted(int hr, MemorySegment ctrlPtr) {
    if (hr != 0) {
      webviewReady = true;
      return hr;
    }

    // AddRef the controller before this callback returns. Without this, WebView2 releases its
    // reference on return, freeing the COM object and causing every
    // subsequent call to fail with 0x8007139f.
    nativeAddRef(ctrlPtr);

    controller = new ComController(ctrlPtr);
    final var wv2Ptr = controller.getCoreWebView2();
    if (wv2Ptr.address() == 0 || (wv2Ptr.address() & 7) != 0) {
      webviewReady = true;
      return -1;
    }
    webView2 = new ComWebView2(wv2Ptr);

    // Register event handlers while still inside the controller callback (this works;
    // these are local COM ops that don't require the IPC channel).
    addWebMessageHandler();
    addPermissionHandler();
    addProcessFailedHandler();

    // Defer the IPC-requiring work (settings, scripts, resize, show) to a pending task
    // that will run inside msgWndProc in a clean stackframe outside any WebView2 callback.
    pending.add(this::doInitTasks);
    try {
      final var _ = (int) Win32.PostMessageW.invokeExact(hwndMsg, Win32.WM_APP, 0L, 0L);
    } catch (final Throwable ignored) {
    }

    return 0;
  }

  /**
   * Runs after the controller callback returns. It's called from msgWndProc (inside
   * DispatchMessageW, part of the init pump).
   */
  private void doInitTasks() {
    applySettings(debugMode);
    setupJsBridge(POST_FN); // nativeAddUserScript uses nested pump; works from msgWndProc context
    resizeWidget(hwnd);
    try {
      final var _ = controller.putIsVisible(true);
      Win32.ShowWindow.invokeExact(hwndWidget, Win32.SW_SHOW);
      Win32.UpdateWindow.invokeExact(hwndWidget);
      Win32.ShowWindow.invokeExact(hwnd, Win32.SW_SHOW);
      Win32.UpdateWindow.invokeExact(hwnd);
      Win32.SetFocus.invokeExact(hwnd);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    focusWebView2();
    // Signal the embedWebView2 pumpLoop to exit.
    webviewReady = true;
  }

  /// WebMessage handler (JS -> Java)
  private void addWebMessageHandler() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  Win32WebView.class,
                  "onWebMessage",
                  MethodType.methodType(
                      int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      webView2.addWebMessageReceived(
          buildComObject(
              Linker.nativeLinker()
                  .upcallStub(
                      mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unused")
  public int onWebMessage(MemorySegment self, MemorySegment sender, MemorySegment args) {
    // ICoreWebView2WebMessageReceivedEventArgs::TryGetWebMessageAsString - vtable[5]
    try (var a = Arena.ofConfined()) {
      final var pStr = a.allocate(ADDRESS);
      final var tryGet =
          Linker.nativeLinker()
              .downcallHandle(
                  Win32.vtableFn(args, 5), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      final var hr = (int) tryGet.invokeExact(args, pStr);
      if (hr == 0) {
        final var strPtr = pStr.get(ADDRESS, 0);
        if (strPtr.address() != 0) {
          final var json = strPtr.reinterpret(65536).getString(0, StandardCharsets.UTF_16LE);
          Win32.coTaskMemFree(strPtr);
          onMessage(json);
        }
      }
    } catch (final Throwable ignored) {
    }
    return 0;
  }

  private void addPermissionHandler() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  Win32WebView.class,
                  "onPermissionRequested",
                  MethodType.methodType(
                      int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      webView2.addPermissionRequested(
          buildComObject(
              Linker.nativeLinker()
                  .upcallStub(
                      mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unused")
  public int onPermissionRequested(MemorySegment self, MemorySegment sender, MemorySegment args) {
    try (var a = Arena.ofConfined()) {
      final var pKind = a.allocate(JAVA_INT);
      final var getKind =
          Linker.nativeLinker()
              .downcallHandle(
                  Win32.vtableFn(args, 4), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      final var _ = (int) getKind.invokeExact(args, pKind);
      if (pKind.get(JAVA_INT, 0) == Win32.COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ) {
        final var putState =
            Linker.nativeLinker()
                .downcallHandle(
                    Win32.vtableFn(args, 7), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        putState.invokeExact(args, Win32.COREWEBVIEW2_PERMISSION_STATE_ALLOW);
      }
    } catch (final Throwable ignored) {
    }
    return 0;
  }

  private void addProcessFailedHandler() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  Win32WebView.class,
                  "onProcessFailed",
                  MethodType.methodType(
                      int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      webView2.addProcessFailed(
          buildComObject(
              Linker.nativeLinker()
                  .upcallStub(
                      mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), arenaStubs)));
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unused")
  public int onProcessFailed(MemorySegment self, MemorySegment sender, MemorySegment args) {
    return 0;
  }

  private MemorySegment buildScriptAddedHandler() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  Win32WebView.class,
                  "onScriptAdded",
                  MethodType.methodType(
                      int.class, MemorySegment.class, int.class, MemorySegment.class))
              .bindTo(this);
      return buildComObject(
          Linker.nativeLinker()
              .upcallStub(
                  mh, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), arenaStubs));
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unused")
  public int onScriptAdded(MemorySegment self, int hr, MemorySegment idStr) {
    if (hr == 0 && idStr.address() != 0) {
      try {
        // idStr is an [in] LPCWSTR owned by WebView2 for the duration of this callback.
        final var id = idStr.reinterpret(1024).getString(0, StandardCharsets.UTF_16LE);
        scriptIds.add(id);
      } catch (final Exception ignored) {
      }
    }
    // Unblock the nativeAddUserScript that submitted this script (FIFO order).
    final var cb = scriptDoneCallbacks.poll();
    if (cb != null) cb.run();
    return 0;
  }

  private void applySettings(boolean debug) {
    final var settings = webView2.getSettings();
    if (settings == null) return;
    settings.putIsStatusBarEnabled(false);
    settings.putAreDevToolsEnabled(debug);
  }

  private void resizeWidget(MemorySegment hWnd) {
    if (hwndWidget == null || hwndWidget.address() == 0) return;
    try (var a = Arena.ofConfined()) {
      final var rect = Win32.getClientRect(hWnd, a);
      final var left = rect.get(JAVA_INT, 0);
      final var top = rect.get(JAVA_INT, 4);
      final var right = rect.get(JAVA_INT, 8);
      final var bottom = rect.get(JAVA_INT, 12);
      final var _ =
          (int) Win32.MoveWindow.invokeExact(hwndWidget, left, top, right - left, bottom - top, 1);
    } catch (final Throwable ignored) {
    }
  }

  private void resizeWebView2(MemorySegment hWnd) {
    if (controller == null) return;
    try (var a = Arena.ofConfined()) {
      final var rect = Win32.getClientRect(hWnd, a);
      final int l = rect.get(JAVA_INT, 0),
          t = rect.get(JAVA_INT, 4),
          r = rect.get(JAVA_INT, 8),
          b = rect.get(JAVA_INT, 12);
      if (r - l <= 0 || b - t <= 0) return;
      controller.putBounds(rect);
    }
  }

  private void focusWebView2() {
    if (controller != null) controller.moveFocus(Win32.COREWEBVIEW2_MOVE_FOCUS_PROGRAMMATIC);
  }

  private void applyMinMaxInfo(long lParam) {
    final var mmi = MemorySegment.ofAddress(lParam).reinterpret(40);
    if (maxW > 0 && maxH > 0) {
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxSize_x, maxW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxSize_y, maxH);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxTrack_x, maxW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMaxTrack_y, maxH);
    }
    if (minW > 0 && minH > 0) {
      mmi.set(JAVA_INT, Win32.MINMAX_ptMinTrack_x, minW);
      mmi.set(JAVA_INT, Win32.MINMAX_ptMinTrack_y, minH);
    }
  }

  private static boolean isDarkTheme() {
    final var val =
        Win32.regQueryString(
            Win32.HKEY_CURRENT_USER,
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "AppsUseLightTheme");
    return "0".equals(val);
  }

  /// Message loop
  private void pumpLoop(BooleanSupplier done) {
    try (var a = Arena.ofConfined()) {
      final var msg = a.allocate(Win32.MSG_LAYOUT);
      for (; ; ) {
        if (done.getAsBoolean()) break;
        final var r = (int) Win32.GetMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0);
        if (r <= 0 || msg.get(JAVA_INT, 8) == Win32.WM_QUIT) break;
        final var _ = (int) Win32.TranslateMessage.invokeExact(msg);
        Win32.DispatchMessageW.invokeExact(msg);
        if (done.getAsBoolean()) break;
      }
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Constructs a minimal COM object in {@link #arenaStubs} with a 4-slot vtable: {@code
   * QueryInterface} (slot 0), {@code AddRef} (slot 1), {@code Release} (slot 2), and the provided
   * {@code Invoke} stub (slot 3).
   *
   * <p>This layout satisfies both {@code IUnknown} and the single-method handler interfaces ({@code
   * ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler}, etc.) that WebView2 calls into.
   */
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

  private MemorySegment qiStub() {
    if (cachedQI == null) {
      try {
        cachedQI =
            Linker.nativeLinker()
                .upcallStub(
                    MethodHandles.lookup()
                        .findStatic(
                            Win32WebView.class,
                            "comQI",
                            MethodType.methodType(
                                int.class,
                                MemorySegment.class,
                                MemorySegment.class,
                                MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                    arenaStubs);
      } catch (final ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }
    return cachedQI;
  }

  private MemorySegment addRefStub() {
    if (cachedAddRef == null) {
      try {
        cachedAddRef =
            Linker.nativeLinker()
                .upcallStub(
                    MethodHandles.lookup()
                        .findStatic(
                            Win32WebView.class,
                            "comAddRef",
                            MethodType.methodType(long.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS),
                    arenaStubs);
      } catch (final ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }
    return cachedAddRef;
  }

  private MemorySegment releaseStub() {
    if (cachedRelease == null) {
      try {
        cachedRelease =
            Linker.nativeLinker()
                .upcallStub(
                    MethodHandles.lookup()
                        .findStatic(
                            Win32WebView.class,
                            "comRelease",
                            MethodType.methodType(long.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS),
                    arenaStubs);
      } catch (final ReflectiveOperationException e) {
        throw new RuntimeException(e);
      }
    }
    return cachedRelease;
  }

  /**
   * {@code IUnknown::QueryInterface} implementation. Returns the object itself for any interface
   * query. WebView2 only calls QI with interfaces it expects this handler to implement. Lifetime is
   * managed by {@link #arenaStubs} rather than COM reference counting.
   */
  @SuppressWarnings("unused")
  private static int comQI(MemorySegment self, MemorySegment iid, MemorySegment ppv) {
    if (ppv.address() != 0) ppv.set(ADDRESS, 0, self);
    return 0; // S_OK
  }

  /**
   * {@code IUnknown::AddRef} reference count not tracked; lifetime managed by {@link #arenaStubs}.
   */
  @SuppressWarnings("unused")
  private static long comAddRef(MemorySegment self) {
    return 1;
  }

  /**
   * {@code IUnknown::Release} reference count not tracked; lifetime managed by {@link #arenaStubs}.
   */
  @SuppressWarnings("unused")
  private static long comRelease(MemorySegment self) {
    return 1;
  }

  /// Calls IUnknown::AddRef (vtable[1]) on a native COM object to increment
  /// its reference count, keeping the object alive beyond the current callback.
  private static void nativeAddRef(MemorySegment comObj) {
    try {
      final var addRef =
          Linker.nativeLinker()
              .downcallHandle(
                  Win32.vtableFn(comObj, 1), // IUnknown::AddRef
                  FunctionDescriptor.of(JAVA_INT, ADDRESS));
      final var _ = (int) addRef.invokeExact(comObj);
    } catch (final Throwable ignored) {
    }
  }
}
