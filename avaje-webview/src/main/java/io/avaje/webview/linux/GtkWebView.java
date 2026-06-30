package io.avaje.webview.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

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
  // Arenas queued for closing by the GTK thread after each event-loop iteration. Windows on
  // non-GTK threads can't close their own arena: windowClosedLatch fires inside drainDispatchQueue
  // (the upcall stub), so the GTK thread is still executing inside that arena when the latch
  // unblocks. The GTK thread drains this queue after gMainContextIteration returns, at which point
  // every dispatch callback has completed and the stubs are no longer executing.
  private static final ConcurrentLinkedQueue<Arena> pendingArenaClose = new ConcurrentLinkedQueue<>();

  private volatile MemorySegment window; // GtkWindow*
  private volatile MemorySegment webView; // WebKitWebView*
  private volatile MemorySegment ucManager; // WebKitUserContentManager*

  private boolean windowShown = false;
  private volatile boolean closed = false;
  private volatile boolean windowDestroyed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  /**
   * Arena that owns the three Panama upcall stubs (dispatchStub, destroyStub, msgStub).
   *
   * <p>{@code Arena.ofShared()} is used so the arena can be explicitly closed from the GTK thread
   * after the event loop exits, at which point no more callbacks can arrive. Closing it frees the
   * native stub trampolines immediately rather than waiting for GC.
   */
  private final Arena callbackArena = Arena.ofShared();

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
      final var initLatch = new CountDownLatch(1);
      pendingDispatches.add(
          () -> {
            initWindowAndWebView(debug);
            initLatch.countDown();
          });
      GLib.gIdleAddFull(
          GLib.G_PRIORITY_HIGH_IDLE, dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
      try {
        initLatch.await();
      } catch (final InterruptedException e) {
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
      // NULL context = the thread-default main context (GTK's global default context on the GTK
      // thread). mayBlock=1 parks the OS thread in poll/select until at least one event source is
      // ready — essential for CPU efficiency. We loop manually rather than calling gtk_main() so
      // we can exit as soon as openWindows reaches 0 without relying on gtk_main_quit().
      while (openWindows.get() > 0) {
        GLib.gMainContextIteration(MemorySegment.NULL, 1);
        // After gMainContextIteration returns, every dispatch callback has completed. Arenas
        // queued by doGtkClose (which runs inside drainDispatchQueue) are now safe to close.
        Arena a;
        while ((a = pendingArenaClose.poll()) != null) a.close();
      }
    } else {
      // Non-GTK thread: just wait for the window to close.
      try {
        windowClosedLatch.await();
      } catch (final InterruptedException e) {
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
      // This g_object_unref balances the g_object_ref_sink from initWindowAndWebView.
      // gtk_window_set_child() does NOT take ownership of the child widget — it holds its own
      // ref through the GtkWidget parent-child hierarchy. After gtk_window_destroy, the parent
      // releases the child's GtkWidget ref, but our explicit ref (from the sink) would keep the
      // WebKitWebView alive indefinitely unless we release it here.
      GLib.gObjectUnref(webView);
      webView = MemorySegment.NULL;
    }
    // Queue the arena for the GTK thread to close after the current dispatch cycle completes.
    // We cannot close it here: this method runs inside drainDispatchQueue (an upcall stub in the
    // arena), so closing now would free memory the stub is still executing from.
    pendingArenaClose.add(callbackArena);
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
    try (var a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadUri(webView, a.allocateFrom(url));
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
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
    try (var a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadHtml(webView, a.allocateFrom(html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    if (closed || webView == null || webView.address() == 0L) return;
    // webkit_web_view_evaluate_javascript asserts WEBKIT_IS_WEB_VIEW(web_view) and additionally
    // requires a page to be loaded. Calling it before any content is loaded triggers a
    // g_return_val_if_fail abort in the WebKit process. get_uri() returns NULL/address==0 when
    // no page is loaded, so we use it as a cheap guard before every eval.
    final var uri = WebKit6.webkitWebViewGetUri(webView);
    if (uri.address() == 0L) return;
    try (var a = Arena.ofConfined()) {
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
    try (var a = Arena.ofConfined()) {
      final var script =
          WebKit6.webkitUserScriptNew(
              a.allocateFrom(js),
              WebKit6.WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
              WebKit6.WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START);
      WebKit6.webkitUcmAddScript(ucManager, script);
      // webkit_user_content_manager_add_script() retains its own reference to the script.
      // We unref our copy immediately so our handle doesn't artificially extend the script's
      // lifetime past its useful point. The UCM holds the live reference.
      WebKit6.webkitUserScriptUnref(script);
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
    } catch (final Exception e) {
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
   *
   * <p>We call POSIX {@code setenv()} rather than {@code System.setProperty()} or manipulating
   * {@code ProcessBuilder.environment()} because WebKitGTK reads this variable at the C level via
   * {@code getenv()} before any Java process environment is consulted. Only a POSIX {@code setenv}
   * call updates the C runtime's environment table that WebKit reads at init time. The overwrite
   * flag is set to {@code 1} (YES) so our value takes effect even if the variable was previously
   * set to something else — but we skip the call entirely if the user already set it to avoid
   * stomping on an intentional override.
   */
  private static void applyDmabufWorkaround() {
    // Wayland is fine
    // no NVIDIA driver
    if (System.getenv("WAYLAND_DISPLAY") != null
        || !new java.io.File("/sys/module/nvidia").isDirectory()
        || System.getenv("WEBKIT_DISABLE_DMABUF_RENDERER") != null) return; // already set
    try {
      final var libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
      final var setenv =
          Linker.nativeLinker()
              .downcallHandle(
                  libc.find("setenv").orElseThrow(),
                  FunctionDescriptor.of(
                      ValueLayout.JAVA_INT,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.JAVA_INT));
      try (var a = Arena.ofConfined()) {
        final var _ =
            (int)
                setenv.invokeExact(
                    a.allocateFrom("WEBKIT_DISABLE_DMABUF_RENDERER"), a.allocateFrom("1"), 1);
      }
    } catch (final Throwable ignored) {
    }
  }

  private static void initGtk() {
    if (gtkInitDone) return;
    if (!Gtk4.gtkInitCheck()) throw new RuntimeException("gtk_init_check() failed — no display?");
    gtkInitDone = true;
  }

  /**
   * Builds the three C function pointer stubs used as GLib/GTK signal callbacks.
   *
   * <p>For each stub: {@code MethodHandles.lookup().findVirtual()} produces a virtual-dispatch
   * handle for the target method. {@code bindTo(this)} converts it to a direct handle permanently
   * bound to this specific {@code GtkWebView} instance — subsequent calls always dispatch to
   * {@code this.drainDispatchQueue()} (or whichever method) without virtual dispatch overhead.
   * {@code Linker.nativeLinker().upcallStub()} then wraps the handle in a small native
   * trampoline: a real C function pointer that GLib/GTK can call with the correct ABI.
   */
  private void buildUpcallStubs() {
    final var linker = Linker.nativeLinker();
    final var lookup = MethodHandles.lookup();
    try {
      // GSourceFunc: gboolean(*)(gpointer data) — used by g_idle_add_full
      final var drainMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "drainDispatchQueue",
                  MethodType.methodType(int.class, MemorySegment.class))
              .bindTo(this);
      dispatchStub = linker.upcallStub(drainMh, GLib.GSOURCE_FUNC_DESC, callbackArena);

      // "destroy" signal: void(*)(GtkWidget*, gpointer)
      final var destroyMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onWindowDestroy",
                  MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      destroyStub = linker.upcallStub(destroyMh, Gtk4.DESTROY_SIGNAL_DESC, callbackArena);

      // "script-message-received": void(*)(WebKitUserContentManager*, JSCValue*, gpointer)
      final var msgMh =
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
    // webkit_web_view_new() returns a GObject with a floating reference (refcount=1, floating flag
    // set). g_object_ref_sink atomically claims ownership by clearing the floating flag, giving us
    // a stable +1 reference that we control. We sink it immediately — before gtk_window_set_child
    // — because set_child() would also sink it, but only when called. An intermediate operation
    // (e.g. a signal connect) between new() and set_child() could trigger a reference cycle that
    // causes a premature free if the floating ref hasn't been claimed yet.
    GLib.gObjectRefSink(webView);

    ucManager = WebKit6.webkitWebViewGetUserContentManager(webView);
    try (var a = Arena.ofConfined()) {
      // Registering the handler by name before any page load ensures window.webkit.messageHandlers
      // .__webview__ exists from the very first page navigation. The third arg (world=NULL) means
      // the default script world; non-null worlds isolate scripts from page content.
      WebKit6.webkitUcmRegisterHandler(ucManager, a.allocateFrom(HANDLER_NAME));
    }
    // GLib signal detail syntax: "signal-name::detail". The ::__webview__ detail causes GLib to
    // fire this connection only when the handler name matches — WebKitGTK uses detail-based
    // multiplexing to route messages from different named handlers through a single signal type.
    GLib.gSignalConnect(
        ucManager, "script-message-received::" + HANDLER_NAME, msgStub, MemorySegment.NULL);

    final var settings = WebKit6.webkitWebViewGetSettings(webView);
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
