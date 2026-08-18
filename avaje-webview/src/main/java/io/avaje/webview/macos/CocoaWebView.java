package io.avaje.webview.macos;

import static io.avaje.webview.macos.ObjC.ALLOC_CLASS_PAIR;
import static io.avaje.webview.macos.ObjC.CLASS_ADD_METHOD;
import static io.avaje.webview.macos.ObjC.CLASS_CREATE_INSTANCE;
import static io.avaje.webview.macos.ObjC.MSG_SEND_0;
import static io.avaje.webview.macos.ObjC.MSG_SEND_ADDR;
import static io.avaje.webview.macos.ObjC.MSG_SEND_EVAL_JS;
import static io.avaje.webview.macos.ObjC.MSG_SEND_NSWINDOW_INIT;
import static io.avaje.webview.macos.ObjC.MSG_SEND_SET_CONTENT_SIZE;
import static io.avaje.webview.macos.ObjC.MSG_SEND_SET_SIZE;
import static io.avaje.webview.macos.ObjC.MSG_SEND_SET_TITLE;
import static io.avaje.webview.macos.ObjC.MSG_SEND_WKUSERSCRIPT_INIT;
import static io.avaje.webview.macos.ObjC.MSG_SEND_WKWEBVIEW_INIT;
import static io.avaje.webview.macos.ObjC.REGISTER_CLASS_PAIR;
import static io.avaje.webview.macos.ObjC.fromNSString;
import static io.avaje.webview.macos.ObjC.nsString;
import static io.avaje.webview.macos.ObjC.sel;
import static io.avaje.webview.macos.ObjC.send0;
import static io.avaje.webview.macos.ObjC.send1;
import static io.avaje.webview.macos.ObjC.send2;
import static io.avaje.webview.macos.ObjC.sendVoid0;
import static io.avaje.webview.macos.ObjC.sendVoid1;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

/**
 * macOS Cocoa + WKWebView, driven through the Objective-C runtime with Panama.
 *
 * <p>Cocoa only accepts calls on the OS main thread, and the JVM main thread is that thread only
 * under {@code -XstartOnFirstThread}. Hence the first window has to be built there, and its {@link
 * #run()} is what calls [NSApplication run].
 *
 * <p>Later windows can come from any thread: they push their init onto the main queue with
 * dispatch_async_f and wait for it, and their {@link #run()} parks on a {@link CountDownLatch}.
 * dispatch() follows the same route: queue the Runnable, wake the main thread, let drainStub run
 * it.
 */
public final class CocoaWebView extends WebviewBase {

  // JS bridge: window.webkit.messageHandlers.__webview__.postMessage(json)
  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // NSWindow style mask bits (from NSWindow.h)
  private static final long NS_TITLED = 1L;
  private static final long NS_CLOSABLE = 2L;
  private static final long NS_MINIATURIZABLE = 4L;
  private static final long NS_RESIZABLE = 8L;
  private static final long NS_STANDARD_WINDOW_MASK =
      NS_TITLED | NS_CLOSABLE | NS_MINIATURIZABLE | NS_RESIZABLE;
  // NSWindowStyleMaskFullSizeContentView: content view extends under the title bar
  private static final long NS_FULL_SIZE_CONTENT_VIEW = 0x8000L;

  /** NSBackingStoreBuffered = 2, the only backing store type left on macOS. */
  private static final long NS_BACKING_BUFFERED = 2L;

  /**
   * WKUserScriptInjectionTimeAtDocumentStart = 0. Runs once the DOM exists but before any page
   * script, so {@code window.__webview__} is in place before app code can call a binding.
   */
  private static final long WK_INJECT_AT_DOCUMENT_START = 0L;

  /**
   * {@code _dispatch_main_q}, the GCD main queue. It is a data symbol rather than a function, so it
   * is looked up as a plain address. The only queue that drains on the OS main thread, so all
   * AppKit work from other threads goes here.
   */
  private static final MemorySegment DISPATCH_MAIN_QUEUE;

  /**
   * {@code dispatch_async_f(dispatch_queue_t queue, void* context, dispatch_function_t work)}.
   *
   * <p>Used instead of block-based {@code dispatch_async} because it takes a plain {@code
   * void(*)(void*)}, the exact shape of a Panama upcall stub, with no block descriptor to allocate.
   * Context is always {@code NULL}; the drain stub is already bound to {@code this}.
   */
  private static final MethodHandle DISPATCH_ASYNC_F;

  /**
   * {@code dispatch_time(dispatch_time_t when, int64_t delta) -> dispatch_time_t}. {@code
   * DISPATCH_TIME_NOW} is {@code 0}; {@code delta} is nanoseconds from that base. Used to compute
   * the deadline for {@link #DISPATCH_AFTER_F}.
   */
  private static final MethodHandle DISPATCH_TIME;

  /**
   * {@code dispatch_after_f(dispatch_time_t when, dispatch_queue_t queue, void* context,
   * dispatch_function_t work)}. Same {@code void(*)(void*)} C-function-pointer shape as {@link
   * #DISPATCH_ASYNC_F}, only deferred to a deadline.
   */
  private static final MethodHandle DISPATCH_AFTER_F;

  // NSApplication is a process singleton; only init once across all CocoaWebView instances.
  private static volatile boolean nsAppInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);
  // The thread that owns [NSApplication run]. Set on first window creation (must be main thread).
  // Subsequent windows created from other threads dispatch their init to this thread and wait.
  private static final AtomicReference<Thread> nsAppThread = new AtomicReference<>();

  static {
    // WebKit.framework has to be dlopen'd before any ObjC class lookup for WKWebView,
    // WKWebViewConfiguration, WKUserScript etc. Skip it and objc_getClass("WKWebView") quietly
    // hands back NULL, which costs hours to work out.
    SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/WebKit.framework/WebKit", Arena.global());

    final var linker = Linker.nativeLinker();
    final var lookup = SymbolLookup.loaderLookup().or(linker.defaultLookup());
    DISPATCH_MAIN_QUEUE =
        lookup
            .find("_dispatch_main_q")
            .orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));
    DISPATCH_ASYNC_F =
        linker.downcallHandle(
            lookup.find("dispatch_async_f").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    DISPATCH_TIME =
        linker.downcallHandle(
            lookup.find("dispatch_time").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    DISPATCH_AFTER_F =
        linker.downcallHandle(
            lookup.find("dispatch_after_f").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS));
  }

  private volatile MemorySegment nsWindow; // NSWindow*
  private volatile MemorySegment wkWebView; // WKWebView*
  private volatile MemorySegment ucController; // WKUserContentController*

  /** Intercepts clicks intended for the parent and redirects them into a flash of this window */
  private volatile MemorySegment parentClickGuard = MemorySegment.NULL;

  private volatile boolean closed = false;
  private final AtomicBoolean windowClosed = new AtomicBoolean(false);
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  private double childLastX, childLastY;
  private boolean syncMoving;
  private volatile boolean disableZoom;

  /**
   * Arena that owns the Panama upcall stubs (drainStub, script handler stub, window delegate stub).
   */
  private final Arena callbackArena = Arena.ofShared();

  // C function pointer (upcall stub) passed to dispatch_async_f to drain the pending queue
  private MemorySegment drainStub;
  private final ConcurrentLinkedQueue<Runnable> pendingDispatches = new ConcurrentLinkedQueue<>();

  public CocoaWebView(
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
    openWindows.incrementAndGet();
    buildDrainStub();

    final var current = Thread.currentThread();
    if (nsAppThread.compareAndSet(null, current)) {
      // First window has to land on the OS main thread.
      if (!MacOSHelper.startedOnFirstThread()) {
        nsAppThread.set(null);
        throw new IllegalStateException(
            "First CocoaWebView must be created on the first thread. Pass -XstartOnFirstThread.");
      }
      initWindowAndWebView(debug, width, height);
      initNSApp();
    } else if (current == nsAppThread.get()) {
      // Already on the main thread, init inline.
      initWindowAndWebView(debug, width, height);
    } else {
      // Other threads hand init to the main queue and wait; [NSApplication run] drains it.
      final var initLatch = new CountDownLatch(1);
      pendingDispatches.add(
          () -> {
            initWindowAndWebView(debug, width, height);
            initLatch.countDown();
          });
      try {
        DISPATCH_ASYNC_F.invokeExact(DISPATCH_MAIN_QUEUE, MemorySegment.NULL, drainStub);
        initLatch.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted waiting for Cocoa window init", e);
      } catch (final Throwable t) {
        throw new RuntimeException(t);
      }
    }
  }

  @Override
  public void run() {
    if (Thread.currentThread() == nsAppThread.get()) {
      // Drives the Cocoa event loop, returns when [app stop:] is sent.
      try (var a = Arena.ofConfined()) {
        final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
        sendVoid0(app, sel(a, "run"));
      }
    } else {
      // Otherwise wait for our own delegate's onWindowWillClose.
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
    dispatchImpl(
        () -> {
          try (var a = Arena.ofConfined()) {
            sendVoid1(nsWindow, sel(a, "orderOut:"), MemorySegment.NULL);
            sendVoid0(nsWindow, sel(a, "close"));
          }
        });
  }

  @Override
  public MemorySegment nativeWindowPointer() {
    return nsWindow != null ? nsWindow : MemorySegment.NULL;
  }

  @Override
  protected void navigateImpl(String url) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var nsUrl =
          send1(ObjC.getClass(a, "NSURL"), sel(a, "URLWithString:"), nsString(a, url));
      final var request = send1(ObjC.getClass(a, "NSURLRequest"), sel(a, "requestWithURL:"), nsUrl);
      sendVoid1(wkWebView, sel(a, "loadRequest:"), request);
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_TITLE.invokeExact(nsWindow, sel(a, "setTitle:"), nsString(a, title));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMinSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMaxSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void disableMaximizeImpl() {
    disableZoom = true;
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var buttonMh =
          Linker.nativeLinker()
              .downcallHandle(
                  MSG_SEND_ADDR,
                  FunctionDescriptor.of(
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.JAVA_LONG));
      final var hideMh =
          Linker.nativeLinker()
              .downcallHandle(
                  MSG_SEND_ADDR,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      final var zoomBtn =
          (MemorySegment) buttonMh.invokeExact(nsWindow, sel(a, "standardWindowButton:"), 2L);
      if (zoomBtn.address() != 0) {
        hideMh.invokeExact(zoomBtn, sel(a, "setHidden:"), 1);
      }

      final long behavior =
          ((MemorySegment) MSG_SEND_0.invokeExact(nsWindow, sel(a, "collectionBehavior")))
              .address();
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
          .invokeExact(nsWindow, sel(a, "setCollectionBehavior:"), behavior | (1L << 9));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
      // Clearing NS_RESIZABLE is the only way to block user resizing short of subclassing
      // NSWindow. styleMask is an NSUInteger, so it comes back in a pointer-width register and
      // MSG_SEND_0 picks up the raw bits; the setter needs a one-off (ADDRESS, ADDRESS, JAVA_LONG)
      // descriptor since MSG_SEND_VOID_1 is all ADDRESS.
      final var styleSel = sel(a, "styleMask");
      var mask = ((MemorySegment) MSG_SEND_0.invokeExact(nsWindow, styleSel)).address();
      mask &= ~NS_RESIZABLE;
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
          .invokeExact(nsWindow, sel(a, "setStyleMask:"), mask);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setHtmlImpl(String html) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      send2(wkWebView, sel(a, "loadHTMLString:baseURL:"), nsString(a, html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      // NULL completionHandler: results come back over the postMessage bridge, not from here.
      MSG_SEND_EVAL_JS.invokeExact(
          wkWebView,
          sel(a, "evaluateJavaScript:completionHandler:"),
          nsString(a, js),
          MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    // Queue first, then kick the main thread. The drain stub polls when it fires.
    pendingDispatches.add(r);
    try {
      DISPATCH_ASYNC_F.invokeExact(DISPATCH_MAIN_QUEUE, MemorySegment.NULL, drainStub);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Runs {@code r} on the main thread after {@code delayMillis}. Builds a one-off upcall stub per
   * call rather than going through the shared queue {@link #dispatchImpl} drains, since the delay
   * belongs to this callback alone.
   */
  private void dispatchAfterImpl(long delayMillis, Runnable r) {
    try {
      final var when = (long) DISPATCH_TIME.invokeExact(0L, delayMillis * 1_000_000L);
      final var runIt =
          MethodHandles.lookup()
              .findVirtual(Runnable.class, "run", MethodType.methodType(void.class))
              .bindTo(r);
      final var stub =
          Linker.nativeLinker()
              .upcallStub(
                  MethodHandles.dropArguments(runIt, 0, MemorySegment.class),
                  FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                  callbackArena);
      DISPATCH_AFTER_F.invokeExact(when, DISPATCH_MAIN_QUEUE, MemorySegment.NULL, stub);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var WKUserScript = ObjC.getClass(a, "WKUserScript");
      final var script =
          (MemorySegment)
              MSG_SEND_WKUSERSCRIPT_INIT.invokeExact(
                  send0(WKUserScript, sel(a, "alloc")),
                  sel(a, "initWithSource:injectionTime:forMainFrameOnly:"),
                  nsString(a, js),
                  WK_INJECT_AT_DOCUMENT_START,
                  1 /* mainFrameOnly = YES */);
      sendVoid1(ucController, sel(a, "addUserScript:"), script);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    if (closed) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      sendVoid0(ucController, sel(a, "removeAllUserScripts"));
    }
  }

  @Override
  public void setDarkAppearance(boolean shouldAppearDark) {
    dispatchImpl(() -> MacOSHelper.setWindowAppearance(nsWindow, shouldAppearDark));
  }

  @Override
  public Webview maximizeWindow() {
    dispatchImpl(() -> MacOSHelper.maximize(nsWindow));
    return this;
  }

  @Override
  public Webview fullscreen() {
    dispatchImpl(() -> MacOSHelper.fullscreen(nsWindow));
    return this;
  }

  @Override
  public Webview minimizeWindow() {
    dispatchImpl(() -> MacOSHelper.minimize(nsWindow));
    return this;
  }

  @Override
  protected void startWindowDragImpl() {
    MacOSHelper.startWindowDrag(nsWindow);
  }

  @Override
  public void setIcon(Path path) {
    dispatchImpl(() -> MacOSHelper.setIcon(path));
  }

  /** Called by libdispatch on the main thread when dispatchImpl fires. ctx is always NULL. */
  @SuppressWarnings("unused")
  public void drainDispatchQueue(MemorySegment ctx) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
  }

  /** Called by the synthetic WKScriptMessageHandler class when JS posts a message. */
  @SuppressWarnings("unused")
  public void onScriptMessage(
      MemorySegment self, MemorySegment cmd, MemorySegment controller, MemorySegment message) {
    try (var a = Arena.ofConfined()) {
      final var body = send0(message, sel(a, "body"));
      final var json = fromNSString(a, body);
      onMessage(json);
    }
  }

  /**
   * Called by the synthetic WKNavigationDelegate once the first navigation finishes. Shows the
   * window and clears the delegate so later navigations don't show it again.
   */
  @SuppressWarnings("unused")
  public void onNavigationFinished(
      MemorySegment self, MemorySegment cmd, MemorySegment wv, MemorySegment nav) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(wv, sel(a, "setNavigationDelegate:"), MemorySegment.NULL);
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);
      final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      Linker.nativeLinker()
          .downcallHandle(
              ObjC.MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
          .invokeExact(app, sel(a, "activateIgnoringOtherApps:"), 1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Fires on the main thread as the window is about to close. Guarded by an AtomicBoolean since
   * either the user or {@link #close()} can get here first.
   */
  @SuppressWarnings("unused")
  public void onWindowWillClose(MemorySegment self, MemorySegment cmd, MemorySegment notification) {
    if (!windowClosed.compareAndSet(false, true)) {
      return;
    }
    closed = true;
    if (parentWindow.address() != 0) {
      MacOSHelper.removeChildWindow(parentWindow, nsWindow);
      MacOSHelper.removeClickGuard(parentClickGuard);
      try (var a = Arena.ofConfined()) {
        sendVoid1(parentWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);
      }
    }
    if (openWindows.decrementAndGet() == 0) {
      try (var a = Arena.ofConfined()) {
        final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
        sendVoid1(app, sel(a, "stop:"), MemorySegment.NULL);
      }
    }
    windowClosedLatch.countDown();
  }

  /**
   * Fires when the child window moves, and shifts the parent by the same delta to keep the two
   * locked together. Moves the parent itself caused (via addChildWindow) are skipped by comparing
   * the two deltas.
   */
  @SuppressWarnings("unused")
  public void onWindowDidMove(MemorySegment self, MemorySegment cmd, MemorySegment notification) {
    if (syncMoving || parentWindow.address() == 0) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var frameSel = sel(a, "frame");
      final var cf =
          (MemorySegment)
              ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, nsWindow, frameSel);
      final double cx = cf.get(ValueLayout.JAVA_DOUBLE, 0), cy = cf.get(ValueLayout.JAVA_DOUBLE, 8);
      final double cdx = cx - childLastX, cdy = cy - childLastY;
      childLastX = cx;
      childLastY = cy;

      if (cdx == 0 && cdy == 0) {
        return;
      }

      final var pf =
          (MemorySegment)
              ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, parentWindow, frameSel);
      final double px = pf.get(ValueLayout.JAVA_DOUBLE, 0), py = pf.get(ValueLayout.JAVA_DOUBLE, 8);

      syncMoving = true;
      try {
        ObjC.MSG_SEND_SET_SIZE.invokeExact(
            parentWindow, sel(a, "setFrameOrigin:"), px + cdx, py + cdy);
      } finally {
        syncMoving = false;
      }
    } catch (final Throwable ignored) {
    }
  }

  @SuppressWarnings("unused")
  public byte onWindowShouldZoom(
      MemorySegment self, MemorySegment cmd, MemorySegment window, MemorySegment frame) {
    return disableZoom ? (byte) 0 : (byte) 1;
  }

  /**
   * Called by the click-guard overlay (see {@link #createClickGuardView}) on a click anywhere in
   * {@link #parentWindow} while this window owns it. The click is swallowed, not forwarded, and
   * this window flashes instead so the user sees where the input has to go.
   */
  @SuppressWarnings("unused")
  public void onParentClickBlocked(MemorySegment self, MemorySegment cmd, MemorySegment event) {
    flashChildWindow();
  }

  /** Brings this window to the front and briefly dips/restores its opacity as a "flash" cue. */
  private void flashChildWindow() {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);
    }
    MacOSHelper.setAlphaValue(nsWindow, 0.35d);
    dispatchAfterImpl(140L, () -> MacOSHelper.setAlphaValue(nsWindow, 1.0d));
  }

  /**
   * Wraps {@link #drainDispatchQueue} in a Panama upcall stub for libdispatch to call. {@code
   * bindTo(this)} pins the stub to one {@code CocoaWebView}, so it always drains this window's
   * queue no matter which window triggered the {@code dispatch_async_f}.
   */
  private void buildDrainStub() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "drainDispatchQueue",
                  MethodType.methodType(void.class, MemorySegment.class))
              .bindTo(this);
      drainStub =
          Linker.nativeLinker()
              .upcallStub(mh, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), callbackArena);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * One-time NSApplication setup. Activation policy Regular (=0) makes this a normal foreground app
   * with a Dock icon; without it the process stays a background agent.
   */
  private static void initNSApp() {
    if (nsAppInitDone) {
      return;
    }
    try (var a = Arena.ofConfined()) {
      final var NSApp = ObjC.getClass(a, "NSApplication");
      final var app = send0(NSApp, sel(a, "sharedApplication"));
      // setActivationPolicy: takes an NSInteger, so no preset descriptor fits
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
          .invokeExact(app, sel(a, "setActivationPolicy:"), 0L); // 0 = Regular
      MacOSHelper.createMenus();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    nsAppInitDone = true;
  }

  /**
   * Creates NSWindow + WKWebView and wires the JS message handler.
   *
   * <p>Order matters: the WKScriptMessageHandler has to be on the WKUserContentController before
   * the WKWebView is created, otherwise it misses the first load.
   */
  private void initWindowAndWebView(boolean debug, int width, int height) {
    try (var a = Arena.ofConfined()) {
      // WKWebView reads this at creation time and ignores later changes.
      final var WKConfig = ObjC.getClass(a, "WKWebViewConfiguration");
      final var config = send0(send0(WKConfig, sel(a, "alloc")), sel(a, "init"));

      ucController = send0(config, sel(a, "userContentController"));

      // Synthetic WKScriptMessageHandler, so JS can call postMessage().
      final var handler = createScriptHandler(a);
      send2(
          ucController,
          sel(a, "addScriptMessageHandler:name:"),
          handler,
          nsString(a, HANDLER_NAME));

      if (debug) {
        // developerExtrasEnabled has no setter, but KVC reaches it on every macOS version.
        final var prefs = send0(config, sel(a, "preferences"));
        final var nsYes =
            (MemorySegment)
                Linker.nativeLinker()
                    .downcallHandle(
                        MSG_SEND_ADDR,
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT))
                    .invokeExact(ObjC.getClass(a, "NSNumber"), sel(a, "numberWithBool:"), 1);
        Linker.nativeLinker()
            .downcallHandle(
                MSG_SEND_ADDR,
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS))
            .invokeExact(
                prefs, sel(a, "setValue:forKey:"), nsYes, nsString(a, "developerExtrasEnabled"));
      }

      wkWebView =
          (MemorySegment)
              MSG_SEND_WKWEBVIEW_INIT.invokeExact(
                  send0(ObjC.getClass(a, "WKWebView"), sel(a, "alloc")),
                  sel(a, "initWithFrame:configuration:"),
                  0d,
                  0d,
                  (double) width,
                  (double) height,
                  config);

      if (debug) {
        // WKWebView.inspectable landed in macOS 13.3, and objc_msgSend on a selector the class
        // doesn't have is undefined behaviour, so ask respondsToSelector: first.
        final var inspSel = sel(a, "setInspectable:");
        final var responds =
            (byte)
                Linker.nativeLinker()
                    .downcallHandle(
                        MSG_SEND_ADDR,
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_BYTE,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS))
                    .invokeExact(wkWebView, sel(a, "respondsToSelector:"), inspSel);
        if (responds != 0) {
          Linker.nativeLinker()
              .downcallHandle(
                  MSG_SEND_ADDR,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
              .invokeExact(wkWebView, inspSel, 1);
        }
      }

      final long styleMask;
      if (borderless && outline) {
        // Full-size content view keeps the native shadow and border; the title bar is made
        // transparent below so content reaches the top of the window.
        styleMask = NS_STANDARD_WINDOW_MASK | NS_FULL_SIZE_CONTENT_VIEW;
      } else if (borderless) {
        styleMask = NS_RESIZABLE | NS_MINIATURIZABLE;
      } else {
        styleMask = NS_STANDARD_WINDOW_MASK;
      }
      nsWindow =
          (MemorySegment)
              MSG_SEND_NSWINDOW_INIT.invokeExact(
                  send0(ObjC.getClass(a, "NSWindow"), sel(a, "alloc")),
                  sel(a, "initWithContentRect:styleMask:backing:defer:"),
                  0d,
                  0d,
                  (double) width,
                  (double) height,
                  styleMask,
                  NS_BACKING_BUFFERED,
                  0 /* defer=NO, create the window now */);
      if (borderless && outline) {
        // Make the title bar transparent so web content renders underneath it.
        Linker.nativeLinker()
            .downcallHandle(
                ObjC.MSG_SEND_ADDR,
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            .invokeExact(nsWindow, sel(a, "setTitlebarAppearsTransparent:"), 1);
        // Hide the title text.
        Linker.nativeLinker()
            .downcallHandle(
                ObjC.MSG_SEND_ADDR,
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
            .invokeExact(nsWindow, sel(a, "setTitleVisibility:"), 1L /* NSWindowTitleHidden */);
        // Hide the traffic-light buttons.
        final var buttonMh =
            Linker.nativeLinker()
                .downcallHandle(
                    ObjC.MSG_SEND_ADDR,
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG));
        final var hideMh =
            Linker.nativeLinker()
                .downcallHandle(
                    ObjC.MSG_SEND_ADDR,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        final var stdBtn = sel(a, "standardWindowButton:");
        final var setHidden = sel(a, "setHidden:");
        for (long btn = 0L; btn <= 2L; btn++) {
          final var button = (MemorySegment) buttonMh.invokeExact(nsWindow, stdBtn, btn);
          if (button.address() != 0) {
            hideMh.invokeExact(button, setHidden, 1);
          }
        }
      }

      if (transparent) {
        final var boolMh =
            Linker.nativeLinker()
                .downcallHandle(
                    MSG_SEND_ADDR,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        boolMh.invokeExact(nsWindow, sel(a, "setOpaque:"), 0);
        sendVoid1(
            nsWindow,
            sel(a, "setBackgroundColor:"),
            send0(ObjC.getClass(a, "NSColor"), sel(a, "clearColor")));
        // Otherwise WKWebView paints its default white fill over the transparency.
        final var falseNum =
            (MemorySegment)
                Linker.nativeLinker()
                    .downcallHandle(
                        MSG_SEND_ADDR,
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT))
                    .invokeExact(ObjC.getClass(a, "NSNumber"), sel(a, "numberWithBool:"), 0);
        Linker.nativeLinker()
            .downcallHandle(
                MSG_SEND_ADDR,
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS))
            .invokeExact(
                wkWebView, sel(a, "setValue:forKey:"), falseNum, nsString(a, "drawsBackground"));
      }

      if (parentWindow.address() != 0) {
        MacOSHelper.centerOnParent(nsWindow, parentWindow);
      } else {
        MacOSHelper.center(nsWindow);
      }

      sendVoid1(nsWindow, sel(a, "setDelegate:"), createWindowDelegate(a));
      sendVoid1(nsWindow, sel(a, "setContentView:"), wkWebView);
      // The navigation delegate shows the window once the first page has loaded.
      sendVoid1(wkWebView, sel(a, "setNavigationDelegate:"), createNavigationDelegate(a));

      if (parentWindow.address() != 0) {
        if (!moveParentWithChild) {
          MacOSHelper.addChildWindow(parentWindow, nsWindow);
        } else {
          final var frameSel = sel(a, "frame");
          final var cf =
              (MemorySegment)
                  ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, nsWindow, frameSel);
          childLastX = cf.get(ValueLayout.JAVA_DOUBLE, 0);
          childLastY = cf.get(ValueLayout.JAVA_DOUBLE, 8);
        }
        parentClickGuard = createClickGuardView(a);
        MacOSHelper.installFillAutoresizeMask(parentClickGuard);
        MacOSHelper.attachClickGuard(parentWindow, parentClickGuard);
      }

      setupJsBridge(POST_FN);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Synthesizes an NSWindowDelegate at runtime to intercept windowWillClose:. */
  private MemorySegment createWindowDelegate(Arena a) {
    try {
      final var superCls = ObjC.getClass(a, "NSObject");
      final var clsName = "JavaWebviewDelegate_" + System.identityHashCode(this);
      final var cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "onWindowWillClose",
                  MethodType.methodType(
                      void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      final var stub =
          Linker.nativeLinker()
              .upcallStub(
                  mh,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, // self
                      ValueLayout.ADDRESS, // cmd
                      ValueLayout.ADDRESS), // NSNotification*
                  callbackArena);

      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls, sel(a, "windowWillClose:"), stub, a.allocateFrom("v@:@"));

      final var shouldZoomMh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "onWindowShouldZoom",
                  MethodType.methodType(
                      byte.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class))
              .bindTo(this);
      final var shouldZoomStub =
          Linker.nativeLinker()
              .upcallStub(
                  shouldZoomMh,
                  FunctionDescriptor.of(
                      ValueLayout.JAVA_BYTE,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ObjC.NS_RECT_LAYOUT),
                  callbackArena);
      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls,
                  sel(a, "windowShouldZoom:toFrame:"),
                  shouldZoomStub,
                  a.allocateFrom("c@:@{NSRect={NSPoint=dd}{NSSize=dd}}"));

      if (moveParentWithChild && parentWindow.address() != 0) {
        final var moveMh =
            MethodHandles.lookup()
                .findVirtual(
                    CocoaWebView.class,
                    "onWindowDidMove",
                    MethodType.methodType(
                        void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
                .bindTo(this);
        final var moveStub =
            Linker.nativeLinker()
                .upcallStub(
                    moveMh,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    callbackArena);
        final var _ =
            (byte)
                CLASS_ADD_METHOD.invokeExact(
                    cls, sel(a, "windowDidMove:"), moveStub, a.allocateFrom("v@:@"));
      }

      REGISTER_CLASS_PAIR.invokeExact(cls);
      return (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Synthesizes a {@code WKNavigationDelegate} that shows the window after the first navigation
   * finishes, then clears itself so it only fires once.
   */
  private MemorySegment createNavigationDelegate(Arena a) {
    try {
      final var superCls = ObjC.getClass(a, "NSObject");
      final var clsName = "JavaWebviewNavDelegate_" + System.identityHashCode(this);
      final var cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "onNavigationFinished",
                  MethodType.methodType(
                      void.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class))
              .bindTo(this);
      final var stub =
          Linker.nativeLinker()
              .upcallStub(
                  mh,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, // self
                      ValueLayout.ADDRESS, // cmd
                      ValueLayout.ADDRESS, // WKWebView*
                      ValueLayout.ADDRESS), // WKNavigation*
                  callbackArena);

      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls, sel(a, "webView:didFinishNavigation:"), stub, a.allocateFrom("v@:@@"));

      REGISTER_CLASS_PAIR.invokeExact(cls);
      return (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Synthesizes an {@code NSView} subclass whose {@code mouseDown:}/{@code rightMouseDown:} call
   * {@link #onParentClickBlocked} and never chain to {@code super}, so clicks never reach the
   * content below. One instance sits over {@link #parentWindow}'s content view while this window is
   * open.
   */
  private MemorySegment createClickGuardView(Arena a) {
    try {
      final var superCls = ObjC.getClass(a, "NSView");
      final var clsName = "JavaWebviewClickGuard_" + System.identityHashCode(this);
      final var cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "onParentClickBlocked",
                  MethodType.methodType(
                      void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class))
              .bindTo(this);
      final var stub =
          Linker.nativeLinker()
              .upcallStub(
                  mh,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, // self
                      ValueLayout.ADDRESS, // cmd
                      ValueLayout.ADDRESS), // NSEvent*
                  callbackArena);

      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(cls, sel(a, "mouseDown:"), stub, a.allocateFrom("v@:@"));
      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls, sel(a, "rightMouseDown:"), stub, a.allocateFrom("v@:@"));

      REGISTER_CLASS_PAIR.invokeExact(cls);
      final var raw = (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
      return (MemorySegment)
          ObjC.MSG_SEND_INIT_WITH_FRAME.invokeExact(
              raw, sel(a, "initWithFrame:"), 0d, 0d, 100_000d, 100_000d);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Synthesizes an ObjC class that implements WKScriptMessageHandler entirely at runtime. */
  private MemorySegment createScriptHandler(Arena a) {
    try {
      final var superCls = ObjC.getClass(a, "NSObject");
      final var clsName = "JavaWebviewHandler_" + System.identityHashCode(this);
      final var cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      final var mh =
          MethodHandles.lookup()
              .findVirtual(
                  CocoaWebView.class,
                  "onScriptMessage",
                  MethodType.methodType(
                      void.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class,
                      MemorySegment.class))
              .bindTo(this);
      final var stub =
          Linker.nativeLinker()
              .upcallStub(
                  mh,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS, // self
                      ValueLayout.ADDRESS, // cmd
                      ValueLayout.ADDRESS, // WKUserContentController*
                      ValueLayout.ADDRESS), // WKScriptMessage*
                  callbackArena);

      final var _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls,
                  sel(a, "userContentController:didReceiveScriptMessage:"),
                  stub,
                  a.allocateFrom("v@:@@"));

      REGISTER_CLASS_PAIR.invokeExact(cls);
      return (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
