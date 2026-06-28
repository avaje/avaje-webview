package io.avaje.webview.linux;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

final class GtkWebView extends WebviewBase {

  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // Shared GTK state — all GTK calls must happen on gtkThread
  private static volatile Thread gtkThread;
  private static volatile boolean gtkInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);

  // Instance state
  private MemorySegment window;
  private MemorySegment webView;
  private MemorySegment ucManager;
  private boolean windowShown = false;
  private volatile boolean windowDestroyed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);
  private final Arena callbackArena = Arena.ofShared();
  private MemorySegment dispatchStub;
  private MemorySegment destroyStub;
  private MemorySegment msgStub;
  private final ConcurrentLinkedQueue<Runnable> pendingDispatches = new ConcurrentLinkedQueue<>();
  private final int initialWidth;
  private final int initialHeight;

  GtkWebView(boolean debug, int width, int height) {
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
      applyDmabufWorkaround();
      buildUpcallStubs();
      var initLatch = new CountDownLatch(1);
      pendingDispatches.add(() -> {
        initWindowAndWebView(debug);
        initLatch.countDown();
      });
      GLib.gIdleAddFull(GLib.G_PRIORITY_HIGH_IDLE, dispatchStub,
          MemorySegment.NULL, MemorySegment.NULL);
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
      while (openWindows.get() > 0) {
        GLib.gMainContextIteration(MemorySegment.NULL, 1);
      }
    } else {
      try {
        windowClosedLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    if (!windowDestroyed && window != null && window.address() != 0L) {
      GLib.gSignalHandlersDisconnectByData(window, MemorySegment.NULL);
      Gtk4.gtkWindowClose(window);
      depletePending();
      window = MemorySegment.NULL;
    }
    if (webView != null && webView.address() != 0L) {
      GLib.gObjectUnref(webView);
      webView = MemorySegment.NULL;
    }
    callbackArena.close();
  }

  // -------------------------------------------------------------------------
  // Webview — native pointer & metadata
  // -------------------------------------------------------------------------

  @Override
  public MemorySegment nativeWindowPointer() {
    return window != null ? window : MemorySegment.NULL;
  }

  @Override
  public String version() {
    return "WebKitGTK 6.0";
  }

  // -------------------------------------------------------------------------
  // WebviewBase — platform-specific impls (called on GTK thread)
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadUri(webView, a.allocateFrom(url));
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    try (Arena a = Arena.ofConfined()) {
      Gtk4.gtkWindowSetTitle(window, a.allocateFrom(title));
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    Gtk4.gtkWindowSetResizable(window, true);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    Gtk4.gtkWidgetSetSizeRequest(webView, width, height);
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    // GTK4 removed geometry hints; no-op
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    Gtk4.gtkWindowSetResizable(window, false);
    Gtk4.gtkWindowSetDefaultSize(window, width, height);
  }

  @Override
  protected void setHtmlImpl(String html) {
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewLoadHtml(webView, a.allocateFrom(html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    MemorySegment uri = WebKit6.webkitWebViewGetUri(webView);
    if (uri.address() == 0L) return;
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitWebViewEvaluateJavascript(webView, a.allocateFrom(js), -1L);
    }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    if (Thread.currentThread() == gtkThread) {
      r.run();
    } else {
      pendingDispatches.add(r);
      GLib.gIdleAddFull(GLib.G_PRIORITY_HIGH_IDLE,
          dispatchStub, MemorySegment.NULL, MemorySegment.NULL);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment script = WebKit6.webkitUserScriptNew(
          a.allocateFrom(js),
          WebKit6.WEBKIT_USER_CONTENT_INJECT_TOP_FRAME,
          WebKit6.WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START);
      WebKit6.webkitUcmAddScript(ucManager, script);
      WebKit6.webkitUserScriptUnref(script);
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
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
    // GTK4 uses GtkWindow.set_icon_name / application icons; file-based icon not trivially settable
    // No-op for now — app icon is typically set via .desktop file on Linux
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
  // Upcall stub targets
  // -------------------------------------------------------------------------

  @SuppressWarnings("unused")
  public int drainDispatchQueue(MemorySegment ignoredData) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
    return 0; // G_SOURCE_REMOVE
  }

  @SuppressWarnings("unused")
  public void onWindowDestroy(MemorySegment widget, MemorySegment ignoredData) {
    window = MemorySegment.NULL;
    windowDestroyed = true;
    openWindows.decrementAndGet();
    windowClosedLatch.countDown();
  }

  @SuppressWarnings("unused")
  public void onScriptMessage(MemorySegment manager, MemorySegment jsValue, MemorySegment data) {
    onMessage(WebKit6.jscValueToString(jsValue));
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private static void applyDmabufWorkaround() {
    if (System.getenv("WAYLAND_DISPLAY") != null) return;
    if (!new java.io.File("/sys/module/nvidia").isDirectory()) return;
    if (System.getenv("WEBKIT_DISABLE_DMABUF_RENDERER") != null) return;
    try {
      var libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
      var setenv = Linker.nativeLinker().downcallHandle(
          libc.find("setenv").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      try (Arena a = Arena.ofConfined()) {
        int _ = (int) setenv.invokeExact(
            a.allocateFrom("WEBKIT_DISABLE_DMABUF_RENDERER"),
            a.allocateFrom("1"), 1);
      }
    } catch (Throwable ignored) {}
  }

  private static void initGtk() {
    if (gtkInitDone) return;
    if (!Gtk4.gtkInitCheck()) throw new RuntimeException("gtk_init_check() failed — no display?");
    gtkInitDone = true;
  }

  private void buildUpcallStubs() {
    var linker = Linker.nativeLinker();
    var lookup = MethodHandles.lookup();
    try {
      var drainMh = lookup.findVirtual(GtkWebView.class, "drainDispatchQueue",
          MethodType.methodType(int.class, MemorySegment.class)).bindTo(this);
      dispatchStub = linker.upcallStub(drainMh, GLib.GSOURCE_FUNC_DESC, callbackArena);

      var destroyMh = lookup.findVirtual(GtkWebView.class, "onWindowDestroy",
          MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
      destroyStub = linker.upcallStub(destroyMh, Gtk4.DESTROY_SIGNAL_DESC, callbackArena);

      var msgMh = lookup.findVirtual(GtkWebView.class, "onScriptMessage",
          MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
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
    GLib.gObjectRefSink(webView);

    ucManager = WebKit6.webkitWebViewGetUserContentManager(webView);
    try (Arena a = Arena.ofConfined()) {
      WebKit6.webkitUcmRegisterHandler(ucManager, a.allocateFrom(HANDLER_NAME));
    }
    GLib.gSignalConnect(ucManager,
        "script-message-received::" + HANDLER_NAME,
        msgStub, MemorySegment.NULL);

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

  private void depletePending() {
    boolean[] done = {false};
    dispatchImpl(() -> done[0] = true);
    while (!done[0]) {
      GLib.gMainContextIteration(MemorySegment.NULL, 1);
    }
  }
}
