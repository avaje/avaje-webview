package io.avaje.webview.linux;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Linux implementation using GTK4 + WebKitGTK 6.0 via Panama FFI.
 *
 * <p>GTK is single-threaded: every GTK/WebKit call must happen on the thread that called gtk_init
 * (tracked as gtkThread). Cross-thread calls queue a Runnable and schedule a GLib idle source via
 * g_idle_add_full, which fires drainDispatchQueue on the GTK thread during the next main-loop
 * iteration.
 *
 * <p>Multiple CocoaWebView windows can share a single GTK main loop: if a second window is created
 * from a non-GTK thread, it dispatches its init onto the GTK thread and blocks on a CountDownLatch
 * until done.
 */
public final class GtkWebView extends WebviewBase {

  // JS bridge: window.webkit.messageHandlers.__webview__.postMessage(json)
  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // GTK is single-threaded; gtkThread is the thread that owns the GTK main loop.
  private static volatile Thread gtkThread;
  private static volatile boolean gtkInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);

  private volatile MemorySegment window; // GtkWindow*
  private volatile MemorySegment webView; // WebKitWebView*
  private volatile MemorySegment ucManager; // WebKitUserContentManager*

  private boolean windowShown = false;
  private volatile boolean closed = false;
  private volatile boolean windowDestroyed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  private Arena callbackArena = Arena.ofAuto();

  // C function pointers (upcall stubs) wired to GLib/GTK signals
  private MemorySegment dispatchStub; // GSourceFunc — drains pendingDispatches on GTK thread
  private MemorySegment destroyStub; // "destroy" signal on GtkWindow
  private MemorySegment msgStub; // "script-message-received" signal on WebKitUserContentManager

  private final ConcurrentLinkedQueue<Runnable> pendingDispatches = new ConcurrentLinkedQueue<>();
  private final int initialWidth;
  private final int initialHeight;

  public GtkWebView(boolean debug, int width, int height) {
    this.initialWidth = width;
    this.initialHeight = height;
    openWindows.incrementAndGet();

    if (gtkThread == null || gtkThread == Thread.currentThread()) {
      // First window or same thread as existing GTK loop — init inline.
      gtkThread = Thread.currentThread();
      applyDmabufWorkaround();
      initGtk();
      buildUpcallStubs();
      initWindowAndWebView(debug);
    } else {
      // Already a GTK loop running on another thread — dispatch our init onto it.
      applyDmabufWorkaround();
      buildUpcallStubs();
      var initLatch = new CountDownLatch(1);
      pendingDispatches.add(
          () -> {
            initWindowAndWebView(debug);
            initLatch.countDown();
          });
      GLib.gIdleAddFull(
          GLib.G_PRIORITY_HIGH_IDLE, dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
      try {
        initLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted waiting for GTK window init", e);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Webview — event loop
  // -------------------------------------------------------------------------

  @Override
  public void run() {
    if (Thread.currentThread() == gtkThread) {
      // Drive the GTK main loop manually so we can exit when all windows are gone.
      // g_main_context_iteration(NULL, block=1) processes one pending event or blocks.
      while (openWindows.get() > 0) {
        GLib.gMainContextIteration(MemorySegment.NULL, 1);
      }
    } else {
      // Non-GTK thread: just wait for the window to close.
      try {
        windowClosedLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    // All GTK/GObject calls must happen on the GTK thread. Dispatch there regardless of caller.
    if (Thread.currentThread() == gtkThread) {
      doGtkClose();
    } else {
      pendingDispatches.add(this::doGtkClose);
      GLib.gIdleAddFull(GLib.G_PRIORITY_HIGH_IDLE, dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
    }
  }

  private void doGtkClose() {
    if (!windowDestroyed && window != null && window.address() != 0L) {
      // gtk_window_destroy emits "destroy" synchronously, so onWindowDestroy runs before
      // this returns — windowClosedLatch is counted down and openWindows decremented there.
      Gtk4.gtkWindowDestroy(window);
    }
    if (webView != null && webView.address() != 0L) {
      GLib.gObjectUnref(webView); // balance the gObjectRefSink from initWindowAndWebView
      webView = MemorySegment.NULL;
    }
    callbackArena = null;
  }

  // -------------------------------------------------------------------------
  // Webview — native pointer & metadata
  // -------------------------------------------------------------------------

  @Override
  public MemorySegment nativeWindowPointer() {
    return window != null ? window : MemorySegment.NULL;
  }

  // -------------------------------------------------------------------------
  // WebviewBase — platform-specific impls (called on GTK thread)
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    if (closed) return;
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadUri(webView, a.allocateFrom(url));
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    if (closed) return;
    try (Arena a = Arena.ofConfined()) {
      Gtk4.gtkWindowSetTitle(window, a.allocateFrom(title));
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    if (closed) return;
    // Must be resizable before changing default size, or the window ignores the request.
    Gtk4.gtkWindowSetResizable(window, true);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    if (closed) return;
    // GTK4 minimum size is set on the widget, not the window.
    Gtk4.gtkWidgetSetSizeRequest(webView, width, height);
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    // GTK4 removed geometry hints (they were in GTK3 via gtk_window_set_geometry_hints).
    // No equivalent exists in GTK4 without writing a custom size-allocate handler.
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    if (closed) return;
    Gtk4.gtkWindowSetResizable(window, false);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setHtmlImpl(String html) {
    if (closed) return;
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadHtml(webView, a.allocateFrom(html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    if (closed || webView == null || webView.address() == 0L) return;
    // Skip if no page is loaded yet (webkit_web_view_get_uri asserts WEBKIT_IS_WEB_VIEW).
    MemorySegment uri = WebKit6.webkitWebViewGetUri(webView);
    if (uri.address() == 0L) return;
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewEvaluateJavascript(webView, a.allocateFrom(js), -1L);
    }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    if (Thread.currentThread() == gtkThread) {
      // Already on the GTK thread — run immediately rather than round-tripping through GLib.
      r.run();
    } else {
      pendingDispatches.add(r);
      // G_PRIORITY_HIGH_IDLE fires before redraws but after I/O; keeps the UI responsive.
      GLib.gIdleAddFull(
          GLib.G_PRIORITY_HIGH_IDLE, dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    if (closed) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment script =
          WebKit6.webkitUserScriptNew(
              a.allocateFrom(js),
              WebKit6.WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
              WebKit6.WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START);
      WebKit6.webkitUcmAddScript(ucManager, script);
      WebKit6.webkitUserScriptUnref(script); // UCM retains its own copy
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    if (closed) return;
    WebKit6.webkitUcmRemoveAllScripts(ucManager);
  }

  // -------------------------------------------------------------------------
  // Webview — appearance/chrome
  // -------------------------------------------------------------------------

  @Override
  public void setDarkAppearance(boolean shouldAppearDark) {
    dispatchImpl(() -> LinuxHelper.setWindowAppearance(this, shouldAppearDark));
  }

  @Override
  public Webview maximizeWindow() {
    dispatchImpl(() -> LinuxHelper.maximizeWindow(this));
    return this;
  }

  @Override
  public Webview fullscreen() {
    dispatchImpl(() -> LinuxHelper.fullscreen(this));
    return this;
  }

  @Override
  public void setIcon(Path path) {
    // GTK4 dropped file-based window icons; app icons are set via the .desktop file.
    // gtk_window_set_icon_name() works with icon themes, not arbitrary paths.
  }

  @Override
  public void setIcon(URI uri) {
    try {
      setIcon(Path.of(uri));
    } catch (Exception e) {
      // best-effort
    }
  }

  // -------------------------------------------------------------------------
  // Upcall stub targets — called FROM native code via GLib signal dispatch
  // -------------------------------------------------------------------------

  /**
   * GSourceFunc callback — drains the pending dispatch queue on the GTK main thread. Returns
   * G_SOURCE_REMOVE (0) so GLib removes the idle source after one invocation. We add a fresh idle
   * source per dispatch(), so there's no need to repeat.
   */
  @SuppressWarnings("unused")
  public int drainDispatchQueue(MemorySegment ignoredData) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
    return 0; // G_SOURCE_REMOVE
  }

  /**
   * "destroy" signal handler for the GtkWindow. GTK emits this after the window is torn down; at
   * this point the GtkWindow* is no longer usable, so we null it out and signal waiters.
   */
  @SuppressWarnings("unused")
  public void onWindowDestroy(MemorySegment widget, MemorySegment ignoredData) {
    window = MemorySegment.NULL;
    windowDestroyed = true;
    openWindows.decrementAndGet();
    windowClosedLatch.countDown();
  }

  /**
   * "script-message-received" signal on WebKitUserContentManager. jsValue is a JavaScriptCore
   * JSCValue wrapping the JS argument to postMessage().
   */
  @SuppressWarnings("unused")
  public void onScriptMessage(MemorySegment manager, MemorySegment jsValue, MemorySegment data) {
    onMessage(WebKit6.jscValueToString(jsValue));
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * NVIDIA GPUs under X11 (not Wayland) can crash WebKitGTK's DMABuf renderer. Set
   * WEBKIT_DISABLE_DMABUF_RENDERER=1 via setenv() before GTK starts if we detect the combination,
   * unless the user already set it themselves.
   */
  private static void applyDmabufWorkaround() {
    // Wayland is fine
    // no NVIDIA driver
    if ((System.getenv("WAYLAND_DISPLAY") != null)
        || !new java.io.File("/sys/module/nvidia").isDirectory()
        || (System.getenv("WEBKIT_DISABLE_DMABUF_RENDERER") != null)) return; // already set
    try {
      var libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
      var setenv =
          Linker.nativeLinker()
              .downcallHandle(
                  libc.find("setenv").orElseThrow(),
                  FunctionDescriptor.of(
                      ValueLayout.JAVA_INT,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.JAVA_INT));
      try (Arena a = Arena.ofConfined()) {
        int _ =
            (int)
                setenv.invokeExact(
                    a.allocateFrom("WEBKIT_DISABLE_DMABUF_RENDERER"), a.allocateFrom("1"), 1);
      }
    } catch (Throwable ignored) {
    }
  }

  private static void initGtk() {
    if (gtkInitDone) return;
    if (!Gtk4.gtkInitCheck()) throw new RuntimeException("gtk_init_check() failed — no display?");
    gtkInitDone = true;
  }

  /**
   * Builds the three C function pointer stubs used as GLib/GTK signal callbacks. Panama
   * upcallStub() takes a bound MethodHandle and a FunctionDescriptor and returns a MemorySegment
   * that looks like a C function pointer to native code.
   */
  private void buildUpcallStubs() {
    var linker = Linker.nativeLinker();
    var lookup = MethodHandles.lookup();
    try {
      // GSourceFunc: gboolean(*)(gpointer data) — used by g_idle_add_full
      var drainMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "drainDispatchQueue",
                  MethodType.methodType(int.class, MemorySegment.class))
              .bindTo(this);
      dispatchStub = linker.upcallStub(drainMh, GLib.GSOURCE_FUNC_DESC, callbackArena);

      // "destroy" signal: void(*)(GtkWidget*, gpointer)
      var destroyMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onWindowDestroy",
                  MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      destroyStub = linker.upcallStub(destroyMh, Gtk4.DESTROY_SIGNAL_DESC, callbackArena);

      // "script-message-received": void(*)(WebKitUserContentManager*, JSCValue*, gpointer)
      var msgMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onScriptMessage",
                  MethodType.methodType(
                      void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      msgStub = linker.upcallStub(msgMh, WebKit6.SCRIPT_MESSAGE_RECEIVED_DESC, callbackArena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException("Failed to build upcall stubs", e);
    }
  }

  private void initWindowAndWebView(boolean debug) {
    window = Gtk4.gtkWindowNew();
    GLib.gSignalConnect(window, "destroy", destroyStub, MemorySegment.NULL);

    webView = WebKit6.webkitWebViewNew();
    // gObjectRefSink takes ownership of the floating reference GObject emits on construction,
    // so we control the lifetime and must call gObjectUnref in close().
    GLib.gObjectRefSink(webView);

    ucManager = WebKit6.webkitWebViewGetUserContentManager(webView);
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitUcmRegisterHandler(ucManager, a.allocateFrom(HANDLER_NAME));
    }
    // The signal name includes the handler name so only messages for __webview__ fire here.
    GLib.gSignalConnect(
        ucManager, "script-message-received::" + HANDLER_NAME, msgStub, MemorySegment.NULL);

    MemorySegment settings = WebKit6.webkitWebViewGetSettings(webView);
    WebKit6.webkitSettingsSetJsClipboard(settings, true);
    if (debug) {
      WebKit6.webkitSettingsSetConsoleToStdout(settings, true);
      WebKit6.webkitSettingsSetDevExtras(settings, true);
    }

    setupJsBridge(POST_FN);
    showWindow(initialWidth, initialHeight);
  }

  private void showWindow(int width, int height) {
    if (windowShown) return;
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
    Gtk4.gtkWindowSetChild(window, webView);
    Gtk4.gtkWidgetSetVisible(webView, true);
    Gtk4.gtkWidgetGrabFocus(webView);
    Gtk4.gtkWidgetSetVisible(window, true);
    windowShown = true;
  }
}
