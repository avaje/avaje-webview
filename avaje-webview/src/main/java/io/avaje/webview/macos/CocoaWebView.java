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
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

/**
 * macOS Cocoa + WKWebView implementation via Objective-C runtime Panama FFI.
 *
 * <p>Thread model: all Cocoa calls must happen on the OS main thread. The JVM's main thread isn't
 * automatically the OS main thread, so callers must pass -XstartOnFirstThread. Cross-thread calls
 * (dispatch()) queue a Runnable then use dispatch_async_f to wake the main thread, which drains the
 * queue via the drainStub C function pointer.
 *
 * <p>The first window must be created on the OS main thread (pass {@code -XstartOnFirstThread}).
 * Additional windows may be created from any thread after the first window's {@link #run()} is
 * active - they dispatch their init to the main thread via dispatch_async_f and block until done.
 * Their {@link #run()} blocks on a {@link CountDownLatch} rather than calling [NSApplication run].
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

  /**
   * NSBackingStoreBuffered = 2 - the only backing store type on modern macOS.
   *
   * <p>The other enum values ({@code NSBackingStoreRetained = 0}, {@code NSBackingStoreNonretained
   * = 1}) were removed from AppKit. Passing any value other than 2 causes an exception at window
   * creation time on macOS 10.13+.
   */
  private static final long NS_BACKING_BUFFERED = 2L;

  /**
   * WKUserScriptInjectionTimeAtDocumentStart = 0.
   *
   * <p>Scripts injected at document-start run after the DOM is created but <em>before</em> any page
   * scripts execute. This guarantees {@code window.__webview__} is defined before any application
   * JavaScript runs, preventing race conditions where app code calls a binding that hasn't been
   * registered yet.
   */
  private static final long WK_INJECT_AT_DOCUMENT_START = 0L;

  /**
   * The GCD main dispatch queue, loaded as a raw exported symbol (not a function).
   *
   * <p>{@code _dispatch_main_q} is the global main queue object exported by libdispatch. We load it
   * as a {@link MemorySegment} address (not via a function call) because it is a data symbol, not a
   * function. The main queue is the only GCD queue guaranteed to drain on the OS main thread,
   * making it the correct target for all AppKit/Cocoa work dispatched from background threads.
   */
  private static final MemorySegment DISPATCH_MAIN_QUEUE;

  /**
   * {@code dispatch_async_f(dispatch_queue_t queue, void* context, dispatch_function_t work)}
   *
   * <p>Enqueues a C function pointer for asynchronous execution on {@code queue}. We prefer this
   * over the block-based {@code dispatch_async()} because it takes a plain {@code void(*)(void*)} C
   * function pointer rather than an Obj-C block - no block descriptor allocation needed, and Panama
   * upcall stubs are exactly {@code void(*)(void*)} shaped. We pass {@code NULL} as context because
   * the drain stub already captures {@code this} via Panama's {@code bindTo} binding.
   */
  private static final MethodHandle DISPATCH_ASYNC_F;

  // NSApplication is a process singleton; only init once across all CocoaWebView instances.
  private static volatile boolean nsAppInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);
  // The thread that owns [NSApplication run]. Set on first window creation (must be main thread).
  // Subsequent windows created from other threads dispatch their init to this thread and wait.
  private static final AtomicReference<Thread> nsAppThread = new AtomicReference<>();

  static {
    // Turns out WebKit.framework must be explicitly dlopen'd before any ObjC class lookup for
    // WKWebView,
    // WKWebViewConfiguration, WKUserScript, etc. Without this, objc_getClass("WKWebView") returns
    // NULL silently, and you go insane for hours trying to figure out what is happening.
    SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/WebKit.framework/WebKit", Arena.global());

    final var linker = Linker.nativeLinker();
    final var lookup = SymbolLookup.loaderLookup().or(linker.defaultLookup());
    // _dispatch_main_q is a data symbol (a dispatch_queue_t struct), not a function.
    // We load its address directly and pass it as the queue argument to dispatch_async_f.
    DISPATCH_MAIN_QUEUE =
        lookup
            .find("_dispatch_main_q")
            .orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));
    // dispatch_async_f(queue, context, work) - work is void(*)(void*)
    // We pass NULL context; drainStub already captures `this` via the upcall binding.
    DISPATCH_ASYNC_F =
        linker.downcallHandle(
            lookup.find("dispatch_async_f").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  }

  private volatile MemorySegment nsWindow; // NSWindow*
  private volatile MemorySegment wkWebView; // WKWebView*
  private volatile MemorySegment ucController; // WKUserContentController*

  private volatile boolean closed = false;
  private final AtomicBoolean windowClosed = new AtomicBoolean(false);
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  /**
   * Arena that owns the Panama upcall stubs (drainStub, script handler stub, window delegate stub).
   */
  private final Arena callbackArena = Arena.ofShared();

  // C function pointer (upcall stub) passed to dispatch_async_f to drain the pending queue
  private MemorySegment drainStub;
  private final ConcurrentLinkedQueue<Runnable> pendingDispatches = new ConcurrentLinkedQueue<>();

  public CocoaWebView(boolean debug, int width, int height) {
    openWindows.incrementAndGet();
    buildDrainStub();
    initNSApp();

    final var current = Thread.currentThread();
    if (nsAppThread.compareAndSet(null, current)) {
      // First window - must be on the OS main thread (requires -XstartOnFirstThread).
      if (!MacOSHelper.startedOnFirstThread()) {
        nsAppThread.set(null);
        throw new IllegalStateException(
            "First CocoaWebView must be created on the first thread. Pass -XstartOnFirstThread.");
      }
      initWindowAndWebView(debug, width, height);
    } else if (current == nsAppThread.get()) {
      // Additional window created from the main thread itself.
      initWindowAndWebView(debug, width, height);
    } else {
      // Background thread - dispatch init to the main thread and block until done.
      // dispatch_async_f always targets the OS main queue, which [NSApplication run] drains.
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
      // Main thread drives the Cocoa event loop; blocks until [app stop:] is sent.
      try (var a = Arena.ofConfined()) {
        final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
        sendVoid0(app, sel(a, "run"));
      }
    } else {
      // Non-main thread blocks until this window's delegate fires onWindowWillClose.
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
    dispatchImpl(
        () -> {
          try (var a = Arena.ofConfined()) {
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
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      // NSURL → NSURLRequest → [WKWebView loadRequest:]
      final var nsUrl =
          send1(ObjC.getClass(a, "NSURL"), sel(a, "URLWithString:"), nsString(a, url));
      final var request = send1(ObjC.getClass(a, "NSURLRequest"), sel(a, "requestWithURL:"), nsUrl);
      sendVoid1(wkWebView, sel(a, "loadRequest:"), request);
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_TITLE.invokeExact(nsWindow, sel(a, "setTitle:"), nsString(a, title));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMinSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMaxSize:"), (double) width, (double) height);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
      // NSWindow.styleMask is an NSUInteger (pointer-sized). objc_msgSend returns it in a
      // pointer-width register, so MSG_SEND_0 (ADDRESS return) captures the raw bits correctly.
      // We strip the NS_RESIZABLE bit (bit 3) with a bitwise AND and write it back via a one-shot
      // descriptor - there is no pre-declared handle for (window, sel, NSUInteger)→void because
      // the layout (ADDRESS, ADDRESS, JAVA_LONG) differs from the all-ADDRESS MSG_SEND_VOID_1.
      // This is the only way to prevent user resizing in AppKit without subclassing NSWindow.
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
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      send2(wkWebView, sel(a, "loadHTMLString:baseURL:"), nsString(a, html), MemorySegment.NULL);
    }
  }

  @Override
  protected void evalImpl(String js) {
    if (closed) return;
    try (var a = Arena.ofConfined()) {
      // NULL completionHandler = fire-and-forget. WKWebView evaluates the script asynchronously;
      // results flow back through the postMessage JS bridge, not through this completion handler.
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
    // Queue first, then kick the main thread. The drain stub polls the queue when it fires.
    pendingDispatches.add(r);
    try {
      DISPATCH_ASYNC_F.invokeExact(DISPATCH_MAIN_QUEUE, MemorySegment.NULL, drainStub);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    if (closed) return;
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
    if (closed) return;
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
  public void setIcon(Path path) {
    dispatchImpl(() -> MacOSHelper.setIcon(path));
  }

  @Override
  public void setIcon(URI uri) {
    try {
      setIcon(Path.of(uri));
    } catch (final Exception ignored) {
    }
  }

  /**
   * Called by libdispatch on the OS main thread when dispatchImpl fires. ctx is always NULL here.
   */
  @SuppressWarnings("unused")
  public void drainDispatchQueue(MemorySegment ctx) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
  }

  /** Called by our synthetic WKScriptMessageHandler class when JS posts a message. */
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
   * Fires on the main thread whenever the window is about to close. The AtomicBoolean ensures the
   * shutdown sequence runs exactly once regardless of who initiated the close.
   */
  @SuppressWarnings("unused")
  public void onWindowWillClose(MemorySegment self, MemorySegment cmd, MemorySegment notification) {
    if (!windowClosed.compareAndSet(false, true)) return;
    closed = true;
    if (openWindows.decrementAndGet() == 0) {
      try (var a = Arena.ofConfined()) {
        final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
        sendVoid1(app, sel(a, "stop:"), MemorySegment.NULL);
      }
    }
    windowClosedLatch.countDown();
  }

  /**
   * Wraps {@link #drainDispatchQueue} in a Panama upcall stub
   *
   * <p>Panama's {@code upcallStub} allocates a small native trampoline in executable memory. When
   * libdispatch calls the stub, the trampoline marshals the C {@code void*} argument to a {@link
   * MemorySegment} and invokes the bound {@link java.lang.invoke.MethodHandle}. {@code
   * bindTo(this)} locks the stub to this specific {@code CocoaWebView} instance so that {@code
   * pendingDispatches.poll()} always drains the correct queue, regardless of which window triggered
   * the {@code dispatch_async_f} call.
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
   * One-time NSApplication setup. setActivationPolicy:NSApplicationActivationPolicyRegular (=0)
   * makes this a normal foreground app with a Dock icon; without it the process is a background
   * agent.
   */
  private static void initNSApp() {
    if (nsAppInitDone) return;
    try (var a = Arena.ofConfined()) {
      final var NSApp = ObjC.getClass(a, "NSApplication");
      final var app = send0(NSApp, sel(a, "sharedApplication"));
      // setActivationPolicy: takes NSInteger - needs a custom descriptor (not in ObjC presets)
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
   * <p>Order matters: the WKScriptMessageHandler must be registered on the WKUserContentController
   * *before* WKWebView is created so it's present from the first load.
   */
  private void initWindowAndWebView(boolean debug, int width, int height) {
    try (var a = Arena.ofConfined()) {
      // Configuration object that WKWebView reads at creation time (can't change it after).
      final var WKConfig = ObjC.getClass(a, "WKWebViewConfiguration");
      final var config = send0(send0(WKConfig, sel(a, "alloc")), sel(a, "init"));

      ucController = send0(config, sel(a, "userContentController"));

      // Register our synthetic WKScriptMessageHandler so JS can call postMessage().
      final var handler = createScriptHandler(a);
      send2(
          ucController,
          sel(a, "addScriptMessageHandler:name:"),
          handler,
          nsString(a, HANDLER_NAME));

      if (debug) {
        // WKPreferences.developerExtrasEnabled - KVC setValue:forKey: works on all macOS versions.
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
        // WKWebView.inspectable = YES - macOS 13.3+ (Sonoma). Guarded with respondsToSelector:
        // because calling an unknown selector via objc_msgSend is undefined behavior - the slot
        // in the vtable may contain garbage or NULL on older macOS. respondsToSelector: queries
        // the runtime dispatch table safely before we attempt the call.
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

      // NS_BACKING_BUFFERED is the only backing type that works on modern macOS.
      // defer=0 (NO) means create the window now rather than lazily when first displayed.
      nsWindow =
          (MemorySegment)
              MSG_SEND_NSWINDOW_INIT.invokeExact(
                  send0(ObjC.getClass(a, "NSWindow"), sel(a, "alloc")),
                  sel(a, "initWithContentRect:styleMask:backing:defer:"),
                  0d,
                  0d,
                  (double) width,
                  (double) height,
                  NS_STANDARD_WINDOW_MASK,
                  NS_BACKING_BUFFERED,
                  0 /* defer=NO */);

      sendVoid1(nsWindow, sel(a, "setDelegate:"), createWindowDelegate(a));
      sendVoid1(nsWindow, sel(a, "setContentView:"), wkWebView);
      // makeKeyAndOrderFront: shows the window AND gives it keyboard focus (makes it the key
      // window). Passing NULL as the sender is conventional; AppKit ignores it for this selector.
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);

      // activateIgnoringOtherApps:YES brings the app to the foreground. Without this call,
      // launching from a terminal leaves the terminal as the active app and the new window
      // appears behind it. YES is required - NO would only activate if already foreground, which
      // is never the case for a freshly launched process.
      final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
          .invokeExact(app, sel(a, "activateIgnoringOtherApps:"), 1);

      setupJsBridge(POST_FN);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Synthesizes an NSWindowDelegate at runtime to intercept windowWillClose:.
   *
   * <p>Same pattern as createScriptHandler: allocate a class pair, add a method wired to a Panama
   * upcall stub, register the pair, and return an instance.
   */
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

      REGISTER_CLASS_PAIR.invokeExact(cls);
      return (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
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

      // Bind the upcall stub to `this` so callbacks hit the right CocoaWebView instance.
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

      // "v@:@@" - ObjC type encoding for (void)(id, SEL, id, id)
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
