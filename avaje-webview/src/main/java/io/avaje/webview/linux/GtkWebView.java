package io.avaje.webview.linux;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;
import io.avaje.webview.WindowSize;

/**
 * Linux implementation using GTK4 + WebKitGTK 6.0 via FFM.
 *
 * <p>GTK is single threaded, so every GTK/WebKit call belongs on the thread that called gtk_init
 * (kept in gtkThread). Calls from elsewhere queue a Runnable and add a GLib idle source with
 * g_idle_add_full; drainDispatchQueue then runs it on the GTK thread at the next main-loop
 * iteration.
 *
 * <p>A second window created off the GTK thread sends its init the same way and waits on a
 * CountDownLatch.
 */
public final class GtkWebView extends WebviewBase {

  // JS bridge: window.webkit.messageHandlers.__webview__.postMessage(json)
  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // Owns the GTK main loop, and the only thread allowed to touch GTK.
  private static volatile Thread gtkThread;
  private static volatile boolean gtkInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);
  // Arenas the GTK thread closes after an event-loop iteration. A window on another thread can't
  // close its own arena: windowClosedLatch is counted down from inside drainDispatchQueue, so the
  // GTK thread is still running in that arena when the latch unblocks. Once gMainContextIteration
  // returns, every dispatch callback is done and the stubs are idle.
  private static final ConcurrentLinkedQueue<Arena> pendingArenaClose =
      new ConcurrentLinkedQueue<>();

  private volatile MemorySegment window; // GtkWindow*
  private volatile MemorySegment webView; // WebKitWebView*
  private volatile MemorySegment ucManager; // WebKitUserContentManager*

  // Set once the first page load has put the WebKitWebView into the window.
  private boolean contentAttached = false;
  private volatile boolean closed = false;
  private volatile boolean windowDestroyed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  /**
   * Dispatch queue shared by every window. Each window used to own its drain stub, but a
   * background thread can call dispatch() after its window closed, scheduling an idle source on a
   * stub whose arena was already freed. One stub in the global arena never goes away.
   */
  private static final ConcurrentLinkedQueue<Runnable> pendingDispatches =
      new ConcurrentLinkedQueue<>();

  /** GSourceFunc for g_idle_add_full that runs {@link #drainDispatchQueue} on the GTK thread. */
  private static final MemorySegment dispatchStub;

  static {
    try {
      final var drainMh =
          MethodHandles.lookup()
              .findStatic(
                  GtkWebView.class,
                  "drainDispatchQueue",
                  MethodType.methodType(int.class, MemorySegment.class));
      dispatchStub =
          Linker.nativeLinker().upcallStub(drainMh, GLib.GSOURCE_FUNC_DESC, Arena.global());
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Arena that owns this window's Panama upcall stubs (destroyStub, msgStub, loadChangedStub).
   *
   * <p>Shared rather than confined so the GTK thread can close it once the event loop has exited
   * and no further callbacks can arrive, freeing the stub trampolines without waiting on GC.
   */
  private final Arena callbackArena = Arena.ofShared();

  // C function pointers (upcall stubs) wired to GLib/GTK signals
  private MemorySegment destroyStub; // "destroy" signal on GtkWindow
  private MemorySegment msgStub; // "script-message-received" signal on WebKitUserContentManager
  private MemorySegment loadChangedStub; // "load-changed" signal on WebKitWebView
  private MemorySegment closeRequestStub; // "close-request" signal on GtkWindow
  private MemorySegment activeStub; // "notify::is-active" signal on GtkWindow
  private MemorySegment realizeStub; // "realize" signal on GtkWindow
  private MemorySegment layoutStub; // "layout" signal on the window's GdkSurface

  private final int initialWidth;
  private final int initialHeight;

  public GtkWebView(
      boolean debug,
      boolean redirectConsole,
      int width,
      int height,
      boolean borderless,
      boolean outline,
      boolean transparent,
      MemorySegment parentWindow,
      boolean moveParentWithChild) {
    super(redirectConsole, borderless, outline, transparent, parentWindow, moveParentWithChild);
    this.initialWidth = width;
    this.initialHeight = height;
    openWindows.incrementAndGet();

    if (gtkThread == null || gtkThread == Thread.currentThread()) {
      gtkThread = Thread.currentThread();
      applyDmabufWorkaround();
      initGtk();
      buildUpcallStubs();
      initWindowAndWebView(debug);
    } else {
      // A GTK loop is already running elsewhere, so send init to it.
      applyDmabufWorkaround();
      buildUpcallStubs();
      final var initLatch = new CountDownLatch(1);
      pendingDispatches.add(
          () -> {
            initWindowAndWebView(debug);
            initLatch.countDown();
          });
      GLib.gIdleAddFull(dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
      try {
        initLatch.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted waiting for GTK window init", e);
      }
    }
  }

  @Override
  public void run() {
    if (Thread.currentThread() == gtkThread) {
      // Hand-rolled main loop rather than gtk_main(), so the loop ends as soon as the last
      // window closes without needing gtk_main_quit(). NULL context is the thread-default one,
      // and mayBlock=1 parks in poll() until an event source is ready.
      while (openWindows.get() > 0) {
        GLib.gMainContextIteration(MemorySegment.NULL, 1);
        // Every dispatch callback has finished by now, so arenas queued by doGtkClose (which
        // runs inside drainDispatchQueue) are safe to close.
        Arena a;
        while ((a = pendingArenaClose.poll()) != null) a.close();
      }
    } else {
      // Off the GTK thread there is no loop to drive, so wait for the close.
      try {
        windowClosedLatch.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    // GTK and GObject calls belong on the GTK thread whoever the caller is.
    if (Thread.currentThread() == gtkThread) {
      doGtkClose();
    } else {
      pendingDispatches.add(this::doGtkClose);
      GLib.gIdleAddFull(dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
    }
  }

  private void doGtkClose() {
    if (!windowDestroyed && window != null && window.address() != 0L) {
      final var surface = Gtk4.gtkNativeGetSurface(window);
      if (surface.address() != 0L) {
        GLib.gSignalHandlersDisconnectByFunc(surface, layoutStub);
      }
      GLib.gSignalHandlersDisconnectByFunc(window, closeRequestStub);
      GLib.gSignalHandlersDisconnectByFunc(window, activeStub);
      GLib.gSignalHandlersDisconnectByFunc(window, realizeStub);
      Gtk4.gtkWidgetSetVisible(window, false);
      // "destroy" is emitted synchronously, so onWindowDestroy has already counted down
      // windowClosedLatch and decremented openWindows by the time this returns.
      Gtk4.gtkWindowDestroy(window);
    }
    if (webView != null && webView.address() != 0L) {
      // The web process can still deliver a script message or load event after this, and the
      // stubs those signals point at are freed once callbackArena closes. Unhook them first.
      GLib.gSignalHandlersDisconnectByFunc(ucManager, msgStub);
      GLib.gSignalHandlersDisconnectByFunc(webView, loadChangedStub);
      // Balances the g_object_ref_sink in initWindowAndWebView. gtk_window_set_child() doesn't
      // take ownership, it holds its own ref through the widget hierarchy, which gtk_window_destroy
      // releases. The ref from the sink is ours, and without this the WebKitWebView leaks.
      GLib.gObjectUnref(webView);
      webView = MemorySegment.NULL;
    }
    // Closing the arena here would free memory the currently executing stub lives in, since this
    // runs inside drainDispatchQueue. Leave it to the GTK thread after the dispatch cycle ends.
    pendingArenaClose.add(callbackArena);
    if (parentWindow.address() != 0L) {
      Gtk4.gtkWidgetSetSensitive(parentWindow, true);
    }
  }

  @Override
  public MemorySegment nativeWindowPointer() {
    return window != null ? window : MemorySegment.NULL;
  }

  @Override
  protected void navigateImpl(String url) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadUri(webView, a.allocateFrom(url));
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      Gtk4.gtkWindowSetTitle(window, a.allocateFrom(title));
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    // A non-resizable window ignores the default size, so flip it back first.
    Gtk4.gtkWindowSetResizable(window, true);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    Gtk4.gtkWidgetSetSizeRequest(webView, width, height);
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    // GTK4 dropped geometry hints, and there is no replacement short of a custom
    // size-allocate handler.
  }

  @Override
  protected void disableMaximizeImpl() {
    // No GTK4 API disables only the maximize button, short of subclassing or CSS hacks.
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    Gtk4.gtkWindowSetResizable(window, false);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setHtmlImpl(String html) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadHtml(webView, a.allocateFrom(html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    if (closed || webView == null || webView.address() == 0L) {
      return;
    }
    // webkit_web_view_evaluate_javascript needs a loaded page, and aborts the WebKit process
    // through g_return_val_if_fail without one. get_uri() is NULL until then, so it makes a cheap
    // guard.
    final var uri = WebKit6.webkitWebViewGetUri(webView);
    if (uri.address() == 0L) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      WebKit6.webkitWebViewEvaluateJavascript(webView, a.allocateFrom(js), -1L);
    }
  }

  @Override
  protected boolean onUIThread() {
    return Thread.currentThread() == gtkThread;
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    if (Thread.currentThread() == gtkThread) {
      r.run();
    } else {
      // Hand it to the GTK thread through GLib.
      pendingDispatches.add(r);
      GLib.gIdleAddFull(dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var script =
          WebKit6.webkitUserScriptNew(
              a.allocateFrom(js),
              WebKit6.WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
              WebKit6.WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START);
      WebKit6.webkitUcmAddScript(ucManager, script);
      // The UCM took its own reference, so drop ours and let it hold the script alive.
      WebKit6.webkitUserScriptUnref(script);
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    if (closed) {
      return;
    }
    WebKit6.webkitUcmRemoveAllScripts(ucManager);
  }

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
  public Webview unmaximizeWindow() {
    dispatchImpl(() -> LinuxHelper.unmaximizeWindow(this));
    return this;
  }

  @Override
  public boolean isMaximized() {
    return LinuxHelper.isMaximized(this);
  }

  @Override
  public Webview fullscreen() {
    dispatchImpl(() -> LinuxHelper.fullscreen(this));
    return this;
  }

  @Override
  public Webview minimizeWindow() {
    dispatchImpl(() -> LinuxHelper.minimizeWindow(this));
    return this;
  }

  @Override
  protected void startWindowDragImpl() {
    LinuxHelper.startWindowDrag(this);
  }

  @Override
  protected void showWindowImpl() {
    if (!closed && contentAttached) {
      Gtk4.gtkWindowPresent(window);
    }
  }

  @Override
  protected void hideWindowImpl() {
    if (!closed && contentAttached) {
      Gtk4.gtkWidgetSetVisible(window, false);
    }
  }

  @Override
  protected void restoreWindowImpl() {
    // gtk_window_present also un-minimizes, but would show a hidden window as well.
    if (!closed && contentAttached && visibleRequested) {
      Gtk4.gtkWindowPresent(window);
    }
  }

  @Override
  protected boolean isMinimizedImpl() {
    if (closed) {
      return false;
    }
    final var surface = Gtk4.gtkNativeGetSurface(window);
    return surface.address() != 0L
        && (Gtk4.gdkToplevelGetState(surface) & Gtk4.GDK_TOPLEVEL_STATE_MINIMIZED) != 0;
  }

  @Override
  protected void exitFullscreenImpl() {
    if (!closed) {
      Gtk4.gtkWindowUnfullscreen(window);
    }
  }

  @Override
  protected boolean isFullscreenImpl() {
    return !closed && Gtk4.gtkWindowIsFullscreen(window);
  }

  @Override
  protected void focusWindowImpl() {
    if (!closed && contentAttached && visibleRequested) {
      Gtk4.gtkWindowPresent(window);
    }
  }

  @Override
  protected WindowSize getSizeImpl() {
    if (!closed && contentAttached) {
      final var width = Gtk4.gtkWidgetGetWidth(window);
      if (width > 0) {
        return new WindowSize(width, Gtk4.gtkWidgetGetHeight(window));
      }
    }
    if (!closed) {
      final var size = Gtk4.gtkWindowGetDefaultSize(window);
      if (size[0] > 0 && size[1] > 0) {
        return new WindowSize(size[0], size[1]);
      }
    }
    return new WindowSize(initialWidth, initialHeight);
  }

  @Override
  public void setIcon(Path path) {
    // GTK4 dropped file-based window icons. gtk_window_set_icon_name() takes a theme name, not
    // a path, and the app icon comes from the .desktop file.
  }

  /**
   * GSourceFunc callback that drains the pending dispatch queue on the GTK main thread. Returns
   * G_SOURCE_REMOVE (0) since every dispatch() adds its own idle source.
   */
  @SuppressWarnings("unused")
  public static int drainDispatchQueue(MemorySegment ignoredData) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
    return 0; // G_SOURCE_REMOVE
  }

  /** "destroy" signal handler for the GtkWindow after the window is torn down. */
  @SuppressWarnings("unused")
  public void onWindowDestroy(MemorySegment widget, MemorySegment ignoredData) {
    window = MemorySegment.NULL;
    windowDestroyed = true;
    openWindows.decrementAndGet();
    windowClosedLatch.countDown();
  }

  /**
   * "close-request" signal, raised by the window manager's close button or a shortcut such as
   * Alt+F4. Always stops GTK's default handler and closes through {@link #close()} instead, so a
   * user close gets the same cleanup as a programmatic one.
   *
   * @return {@code 1} (TRUE) so GTK doesn't destroy the window itself
   */
  @SuppressWarnings("unused")
  public int onCloseRequest(MemorySegment widget, MemorySegment ignoredData) {
    if (fireCloseRequested()) {
      close();
    }
    return 1;
  }

  /** "notify::is-active" signal: the window gained or lost keyboard focus. */
  @SuppressWarnings("unused")
  public void onActiveChanged(MemorySegment object, MemorySegment pspec, MemorySegment data) {
    if (!closed) {
      fireFocusChanged(Gtk4.gtkWindowIsActive(window));
    }
  }

  /**
   * "realize" signal: the window now has a {@code GdkSurface}, whose "layout" signal reports every
   * size change (user resize, maximize, tiling) that GTK4 no longer exposes on the window itself.
   */
  @SuppressWarnings("unused")
  public void onWindowRealize(MemorySegment widget, MemorySegment ignoredData) {
    final var surface = Gtk4.gtkNativeGetSurface(window);
    if (surface.address() != 0L) {
      GLib.gSignalHandlersDisconnectByFunc(surface, layoutStub);
      GLib.gSignalConnect(surface, "layout", layoutStub, MemorySegment.NULL);
    }
  }

  /**
   * "layout" signal on the window's surface. The surface size includes the shadow, so the window's
   * allocation is read instead, from an idle callback that runs once GTK has allocated the widgets
   * for the new size.
   */
  @SuppressWarnings("unused")
  public void onSurfaceLayout(MemorySegment surface, int width, int height, MemorySegment data) {
    pendingDispatches.add(
        () -> {
          if (!closed && contentAttached) {
            final var size = getSizeImpl();
            fireResized(size.width(), size.height());
          }
        });
    GLib.gIdleAddFull(dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
  }

  /**
   * "script-message-received" signal on WebKitUserContentManager. jsValue is a JavaScriptCore
   * JSCValue wrapping the JS argument to postMessage().
   */
  @SuppressWarnings("unused")
  public void onScriptMessage(MemorySegment manager, MemorySegment jsValue, MemorySegment data) {
    onMessage(WebKit6.jscValueToString(jsValue));
  }

  /**
   * Shows the window the first time {@code WEBKIT_LOAD_FINISHED} fires so it only appears once
   * content is ready.
   */
  @SuppressWarnings("unused")
  public void onLoadChanged(MemorySegment wv, int loadEvent, MemorySegment data) {
    if (loadEvent == WebKit6.WEBKIT_LOAD_FINISHED) {
      attachContent(initialWidth, initialHeight);
      final var uri = WebKit6.webkitWebViewGetUri(wv);
      firePageLoaded(uri.address() == 0L ? "" : uri.reinterpret(Long.MAX_VALUE).getString(0));
    }
  }

  /**
   * NVIDIA GPUs on X11 can crash WebKitGTK's DMABuf renderer, so set
   * WEBKIT_DISABLE_DMABUF_RENDERER=1 before GTK starts unless it is already set.
   *
   * <p>Has to go through POSIX {@code setenv()}: WebKitGTK reads it with {@code getenv()} at the C
   * level, where a Java-side property or process environment change never shows up.
   */
  private static void applyDmabufWorkaround() {
    // Wayland is unaffected, and so is any machine without the NVIDIA driver loaded.
    if (System.getenv("WAYLAND_DISPLAY") != null
        || !new File("/sys/module/nvidia").isDirectory()
        || System.getenv("WEBKIT_DISABLE_DMABUF_RENDERER") != null) {
      return;
    }
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
    if (gtkInitDone) {
      return;
    }
    if (!Gtk4.gtkInitCheck()) {
      throw new RuntimeException("gtk_init_check() failed, no display?");
    }
    gtkInitDone = true;
  }

  /** Builds the three C function pointer stubs used as GLib/GTK signal callbacks. */
  private void buildUpcallStubs() {
    final var linker = Linker.nativeLinker();
    final var lookup = MethodHandles.lookup();
    try {
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

      // "load-changed": void(*)(WebKitWebView*, WebKitLoadEvent, gpointer)
      final var loadMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onLoadChanged",
                  MethodType.methodType(
                      void.class, MemorySegment.class, int.class, MemorySegment.class))
              .bindTo(this);
      loadChangedStub = linker.upcallStub(loadMh, WebKit6.LOAD_CHANGED_DESC, callbackArena);

      // "close-request": gboolean(*)(GtkWindow*, gpointer)
      final var closeMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onCloseRequest",
                  MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      closeRequestStub =
          linker.upcallStub(closeMh, Gtk4.CLOSE_REQUEST_SIGNAL_DESC, callbackArena);

      // "notify::is-active": void(*)(GObject*, GParamSpec*, gpointer)
      final var activeMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onActiveChanged",
                  MethodType.methodType(
                      void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      activeStub = linker.upcallStub(activeMh, Gtk4.NOTIFY_SIGNAL_DESC, callbackArena);

      // "realize": void(*)(GtkWidget*, gpointer)
      final var realizeMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onWindowRealize",
                  MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      realizeStub = linker.upcallStub(realizeMh, Gtk4.DESTROY_SIGNAL_DESC, callbackArena);

      // "layout" on GdkSurface: void(*)(GdkSurface*, gint, gint, gpointer)
      final var layoutMh =
          lookup
              .findVirtual(
                  GtkWebView.class,
                  "onSurfaceLayout",
                  MethodType.methodType(
                      void.class, MemorySegment.class, int.class, int.class, MemorySegment.class))
              .bindTo(this);
      layoutStub = linker.upcallStub(layoutMh, Gtk4.LAYOUT_SIGNAL_DESC, callbackArena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException("Failed to build upcall stubs", e);
    }
  }

  private void initWindowAndWebView(boolean debug) {
    window = Gtk4.gtkWindowNew();
    if (borderless) {
      if (outline) {
        Gtk4.gtkWindowHideTitlebar(window);
      } else {
        Gtk4.gtkWindowSetDecorated(window, false);
      }
    }
    if (transparent) {
      Gtk4.gtkMakeWindowTransparent(window);
    }
    GLib.gSignalConnect(window, "destroy", destroyStub, MemorySegment.NULL);
    GLib.gSignalConnect(window, "close-request", closeRequestStub, MemorySegment.NULL);
    GLib.gSignalConnect(window, "notify::is-active", activeStub, MemorySegment.NULL);
    GLib.gSignalConnect(window, "realize", realizeStub, MemorySegment.NULL);
    if (parentWindow.address() != 0L) {
      Gtk4.gtkWindowSetTransientFor(window, parentWindow);
      if (moveParentWithChild) {
        Gtk4.gtkWindowSetModal(window, true);
      }
      Gtk4.gtkWidgetSetSensitive(parentWindow, false);
    }

    webView = WebKit6.webkitWebViewNew();
    // webkit_web_view_new() hands back a floating reference. Sinking it clears the floating flag
    // and leaves a normal +1 ref owned by us.
    GLib.gObjectRefSink(webView);
    if (transparent) {
      WebKit6.webkitWebViewSetBackgroundColor(webView, 0f, 0f, 0f, 0f);
    }

    ucManager = WebKit6.webkitWebViewGetUserContentManager(webView);
    try (var a = Arena.ofConfined()) {
      // Registered before any load so window.webkit.messageHandlers.__webview__ is there from
      // the first navigation. world=NULL is the default script world, shared with page content.
      WebKit6.webkitUcmRegisterHandler(ucManager, a.allocateFrom(HANDLER_NAME));
    }
    // The "::detail" suffix limits this connection to a matching handler name, which is how
    // WebKitGTK
    // routes several named handlers through one signal.
    GLib.gSignalConnect(
        ucManager, "script-message-received::" + HANDLER_NAME, msgStub, MemorySegment.NULL);

    final var settings = WebKit6.webkitWebViewGetSettings(webView);
    WebKit6.webkitSettingsSetJsClipboard(settings, true);
    if (debug) {
      WebKit6.webkitSettingsSetConsoleToStdout(settings, true);
      WebKit6.webkitSettingsSetDevExtras(settings, true);
    }

    GLib.gSignalConnect(webView, "load-changed", loadChangedStub, MemorySegment.NULL);
    setupJsBridge(POST_FN);
  }

  /**
   * Puts the web view into the window once the first page has loaded or failed, and shows the
   * window unless {@link #hideWindow()} asked otherwise.
   */
  private void attachContent(int width, int height) {
    if (contentAttached) {
      return;
    }
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
    Gtk4.gtkWindowSetChild(window, webView);
    Gtk4.gtkWidgetSetVisible(webView, true);
    Gtk4.gtkWidgetGrabFocus(webView);
    if (visibleRequested) {
      Gtk4.gtkWidgetSetVisible(window, true);
    }
    contentAttached = true;
  }
}
