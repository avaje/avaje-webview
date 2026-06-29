package io.avaje.webview.macos;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewBase;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.avaje.webview.macos.ObjC.*;

/**
 * macOS Cocoa + WKWebView implementation via Objective-C runtime Panama FFI.
 *
 * Thread model: all Cocoa calls must happen on the OS main thread. The JVM's main thread
 * isn't automatically the OS main thread, so callers must pass -XstartOnFirstThread.
 * Cross-thread calls (dispatch()) queue a Runnable then use dispatch_async_f to wake the
 * main thread, which drains the queue via the drainStub C function pointer.
 *
 * JS→Java: WKWebView requires a WKScriptMessageHandler ObjC object. Since we can't implement
 * ObjC protocols in Java, we fabricate a class at runtime using objc_allocateClassPair +
 * class_addMethod, wiring the method implementation to a Panama upcall stub that calls back
 * into onScriptMessage(). See createScriptHandler() for the full setup.
 *
 * Must be created and {@link #run()} called on the first thread
 * (pass {@code -XstartOnFirstThread} to the JVM).
 */
final class CocoaWebView extends WebviewBase {

  // JS bridge: window.webkit.messageHandlers.__webview__.postMessage(json)
  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // NSWindow style mask bits (from NSWindow.h)
  private static final long NS_TITLED               = 1L;
  private static final long NS_CLOSABLE             = 2L;
  private static final long NS_MINIATURIZABLE        = 4L;
  private static final long NS_RESIZABLE            = 8L;
  private static final long NS_STANDARD_WINDOW_MASK = NS_TITLED | NS_CLOSABLE | NS_MINIATURIZABLE | NS_RESIZABLE;
  private static final long NS_BACKING_BUFFERED     = 2L;  // only valid backing type on modern macOS

  // WKUserScriptInjectionTimeAtDocumentStart = 0
  // Scripts injected here run before any page JS, so window.__webview__ is ready immediately.
  private static final long WK_INJECT_AT_DOCUMENT_START = 0L;

  // dispatch_async_f posts a C function pointer to the main queue, running it on the OS main thread.
  // _dispatch_main_q is the global main queue (an exported symbol, not a function).
  private static final MemorySegment DISPATCH_MAIN_QUEUE;
  private static final MethodHandle DISPATCH_ASYNC_F;

  // NSApplication is a process singleton; only init once across all CocoaWebView instances.
  private static volatile boolean nsAppInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);

  static {
    var libDispatch = SymbolLookup.libraryLookup("libdispatch.dylib", Arena.global());
    var linker = Linker.nativeLinker();
    DISPATCH_MAIN_QUEUE =
        libDispatch
            .find("_dispatch_main_q")
            .orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));
    // dispatch_async_f(queue, context, work) — work is void(*)(void*)
    // We pass NULL context; drainStub already captures `this` via the upcall binding.
    DISPATCH_ASYNC_F =
        linker.downcallHandle(
            libDispatch.find("dispatch_async_f").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  }

  private MemorySegment nsWindow;      // NSWindow*
  private MemorySegment wkWebView;     // WKWebView*
  private MemorySegment ucController;  // WKUserContentController*

  private volatile boolean closed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);

  // ofShared() because close() may be called from a non-main thread after the stubs are created
  private final Arena callbackArena = Arena.ofShared();

  // C function pointer (upcall stub) passed to dispatch_async_f to drain the pending queue
  private MemorySegment drainStub;
  private final ConcurrentLinkedQueue<Runnable> pendingDispatches = new ConcurrentLinkedQueue<>();

  CocoaWebView(boolean debug, int width, int height) {
    if (!MacOSHelper.startedOnFirstThread()) {
      throw new IllegalStateException(
          "CocoaWebView must be created on the first thread. Pass -XstartOnFirstThread to the JVM.");
    }
    openWindows.incrementAndGet();
    buildDrainStub();
    initNSApp();
    initWindowAndWebView(debug, width, height);
  }

  // -------------------------------------------------------------------------
  // Webview — event loop
  // -------------------------------------------------------------------------

  @Override
  public void run() {
    // [NSApplication run] starts the Cocoa event loop; blocks until [app stop:] is sent.
    try (Arena a = Arena.ofConfined()) {
      MemorySegment app = send0(ObjC.getClass(a,"NSApplication"), sel(a, "sharedApplication"));
      sendVoid0(app, sel(a, "run"));
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    // Dispatch to main thread — Cocoa objects can only be touched there.
    dispatchImpl(() -> {
      try (Arena a = Arena.ofConfined()) {
        sendVoid1(nsWindow, sel(a, "close"), MemorySegment.NULL);
        // When the last window closes, stop the event loop so run() returns.
        if (openWindows.decrementAndGet() == 0) {
          MemorySegment app = send0(ObjC.getClass(a,"NSApplication"), sel(a, "sharedApplication"));
          sendVoid1(app, sel(a, "stop:"), MemorySegment.NULL);
        }
      }
      windowClosedLatch.countDown();
    });
  }

  // -------------------------------------------------------------------------
  // Webview — native pointer & metadata
  // -------------------------------------------------------------------------

  @Override
  public MemorySegment nativeWindowPointer() {
    return nsWindow != null ? nsWindow : MemorySegment.NULL;
  }

  // -------------------------------------------------------------------------
  // WebviewBase — platform impls (always called on the main thread via dispatchImpl)
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    try (Arena a = Arena.ofConfined()) {
      // NSURL → NSURLRequest → [WKWebView loadRequest:]
      MemorySegment nsUrl =
          send1(ObjC.getClass(a, "NSURL"), sel(a, "URLWithString:"), nsString(a, url));
      MemorySegment request =
          send1(ObjC.getClass(a, "NSURLRequest"), sel(a, "requestWithURL:"), nsUrl);
      sendVoid1(wkWebView, sel(a, "loadRequest:"), request);
    }
  }

  @Override
  protected void setTitleImpl(String title) {
    try (Arena a = Arena.ofConfined()) {
      MSG_SEND_SET_TITLE.invokeExact(nsWindow, sel(a, "setTitle:"), nsString(a, title));
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setSizeImpl(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      // setContentSize: takes NSSize (two doubles) — hence the specialised handle
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMinSizeImpl(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMinSize:"), (double) width, (double) height);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setMaxSizeImpl(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setMaxSize:"), (double) width, (double) height);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setFixedSizeImpl(int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      MSG_SEND_SET_CONTENT_SIZE.invokeExact(
          nsWindow, sel(a, "setContentSize:"), (double) width, (double) height);
      // Read the current styleMask (returned as address-sized int), strip NS_RESIZABLE, write back.
      MemorySegment styleSel = sel(a, "styleMask");
      long mask = ((MemorySegment) MSG_SEND_0.invokeExact(nsWindow, styleSel)).address();
      mask &= ~NS_RESIZABLE;
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
          .invokeExact(nsWindow, sel(a, "setStyleMask:"), mask);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void setHtmlImpl(String html) {
    try (Arena a = Arena.ofConfined()) {
      // loadHTMLString:baseURL: is a two-arg call but we drop the baseURL (pass NULL implicitly).
      sendVoid1(
          wkWebView,
          sel(a, "loadHTMLString:baseURL:"),
          nsString(a, html)); // passing just html; 2-arg form needs special MSG_SEND_2
    }
  }

  @Override
  protected void evalImpl(String js) {
    try (Arena a = Arena.ofConfined()) {
      // NULL completionHandler = fire-and-forget; we don't need the return value.
      MSG_SEND_EVAL_JS.invokeExact(
          wkWebView,
          sel(a, "evaluateJavaScript:completionHandler:"),
          nsString(a, js),
          MemorySegment.NULL);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void dispatchImpl(Runnable r) {
    // Queue first, then kick the main thread. The drain stub polls the queue when it fires.
    pendingDispatches.add(r);
    try {
      DISPATCH_ASYNC_F.invokeExact(DISPATCH_MAIN_QUEUE, MemorySegment.NULL, drainStub);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void nativeAddUserScript(String js) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment WKUserScript = ObjC.getClass(a, "WKUserScript");
      MemorySegment script =
          (MemorySegment)
              MSG_SEND_WKUSERSCRIPT_INIT.invokeExact(
                  send0(WKUserScript, sel(a, "alloc")),
                  sel(a, "initWithSource:injectionTime:forMainFrameOnly:"),
                  nsString(a, js),
                  WK_INJECT_AT_DOCUMENT_START,
                  1 /* mainFrameOnly = YES */);
      sendVoid1(ucController, sel(a, "addUserScript:"), script);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Override
  protected void nativeRemoveAllUserScripts() {
    try (Arena a = Arena.ofConfined()) {
      sendVoid0(ucController, sel(a, "removeAllUserScripts"));
    }
  }

  // -------------------------------------------------------------------------
  // Webview — appearance/chrome
  // -------------------------------------------------------------------------

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
    } catch (Exception ignored) {
    }
  }

  // -------------------------------------------------------------------------
  // Upcall targets — called FROM native code via Panama upcall stubs
  // -------------------------------------------------------------------------

  /**
   * Called by libdispatch on the OS main thread when dispatchImpl fires.
   * Signature void(*)(void*) matches dispatch_function_t; ctx is always NULL here.
   */
  @SuppressWarnings("unused")
  public void drainDispatchQueue(MemorySegment ctx) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
  }

  /**
   * Called by our synthetic WKScriptMessageHandler class when JS posts a message.
   *
   * ObjC method:  - (void)userContentController:(WKUserContentController*)ucc
   *                       didReceiveScriptMessage:(WKScriptMessage*)message
   * As a C function: (id self, SEL cmd, id controller, id message)
   * Type encoding:  "v@:@@"
   */
  @SuppressWarnings("unused")
  public void onScriptMessage(
      MemorySegment self, MemorySegment cmd, MemorySegment controller, MemorySegment message) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment body = send0(message, sel(a, "body"));
      String json = fromNSString(a, send0(body, sel(a, "description")));
      onMessage(json);
    }
  }

  // -------------------------------------------------------------------------
  // Initialisation
  // -------------------------------------------------------------------------

  /**
   * Wraps drainDispatchQueue in a Panama upcall stub — a real C function pointer that
   * libdispatch can call. bindTo(this) locks the stub to this instance's queue.
   */
  private void buildDrainStub() {
    try {
      var mh =
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
   * One-time NSApplication setup.
   * setActivationPolicy:NSApplicationActivationPolicyRegular (=0) makes this a normal
   * foreground app with a Dock icon; without it the process is a background agent.
   */
  private static void initNSApp() {
    if (nsAppInitDone) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment NSApp = ObjC.getClass(a, "NSApplication");
      MemorySegment app = send0(NSApp, sel(a, "sharedApplication"));
      // setActivationPolicy: takes NSInteger — needs a custom descriptor (not in ObjC presets)
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.of(
                  ValueLayout.JAVA_BYTE,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_LONG))
          .invokeExact(app, sel(a, "setActivationPolicy:"), 0L); // 0 = Regular
      MacOSHelper.createMenus();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
    nsAppInitDone = true;
  }

  /**
   * Creates NSWindow + WKWebView and wires the JS message handler.
   *
   * Order matters: the WKScriptMessageHandler must be registered on the
   * WKUserContentController *before* WKWebView is created so it's present from the first load.
   */
  private void initWindowAndWebView(boolean debug, int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      // Configuration object that WKWebView reads at creation time (can't change it after).
      MemorySegment WKConfig = ObjC.getClass(a, "WKWebViewConfiguration");
      MemorySegment config = send0(send0(WKConfig, sel(a, "alloc")), sel(a, "init"));

      ucController = send0(config, sel(a, "userContentController"));

      // Register our synthetic WKScriptMessageHandler so JS can call postMessage().
      MemorySegment handler = createScriptHandler(a);
      send2(
          ucController,
          sel(a, "addScriptMessageHandler:name:"),
          handler,
          nsString(a, HANDLER_NAME));

      // NSRect is passed as 4 inline doubles (x=0, y=0, w, h) on arm64.
      wkWebView =
          (MemorySegment)
              MSG_SEND_WKWEBVIEW_INIT.invokeExact(
                  send0(ObjC.getClass(a, "WKWebView"), sel(a, "alloc")),
                  sel(a, "initWithFrame:configuration:"),
                  0d, 0d, (double) width, (double) height,
                  config);

      // NS_BACKING_BUFFERED is the only backing type that works on modern macOS.
      // defer=0 (NO) means create the window now rather than lazily when first displayed.
      nsWindow =
          (MemorySegment)
              MSG_SEND_NSWINDOW_INIT.invokeExact(
                  send0(ObjC.getClass(a, "NSWindow"), sel(a, "alloc")),
                  sel(a, "initWithContentRect:styleMask:backing:defer:"),
                  0d, 0d, (double) width, (double) height,
                  NS_STANDARD_WINDOW_MASK, NS_BACKING_BUFFERED,
                  0 /* defer=NO */);

      sendVoid1(nsWindow, sel(a, "setContentView:"), wkWebView);
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);

      // activateIgnoringOtherApps:YES is needed when launching from a terminal;
      // without it the window appears but the app doesn't come to the foreground.
      MemorySegment app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
          .invokeExact(app, sel(a, "activateIgnoringOtherApps:"), 1);

      setupJsBridge(POST_FN);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Synthesises an ObjC class that implements WKScriptMessageHandler entirely at runtime.
   *
   * We can't implement ObjC protocols in Java, so we use the ObjC runtime C API:
   *   1. objc_allocateClassPair  — reserve a new class (subclass of NSObject)
   *   2. upcallStub              — Panama turns onScriptMessage into a C function pointer
   *   3. class_addMethod         — register the C function pointer as the method implementation
   *      type encoding "v@:@@" = void, id self, SEL cmd, id arg, id arg
   *   4. objc_registerClassPair  — finalise (must happen after all addMethod calls)
   *   5. class_createInstance    — allocate an instance (no -init needed; handler is stateless)
   *
   * Unique class name per instance avoids runtime conflicts when multiple windows exist.
   */
  private MemorySegment createScriptHandler(Arena a) {
    try {
      MemorySegment superCls = ObjC.getClass(a, "NSObject");
      String clsName = "JavaWebviewHandler_" + System.identityHashCode(this);
      MemorySegment cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      // Bind the upcall stub to `this` so callbacks hit the right CocoaWebView instance.
      var mh =
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
      MemorySegment stub =
          Linker.nativeLinker()
              .upcallStub(
                  mh,
                  FunctionDescriptor.ofVoid(
                      ValueLayout.ADDRESS,  // self
                      ValueLayout.ADDRESS,  // cmd
                      ValueLayout.ADDRESS,  // WKUserContentController*
                      ValueLayout.ADDRESS), // WKScriptMessage*
                  callbackArena);

      // "v@:@@" — ObjC type encoding for (void)(id, SEL, id, id)
      byte _ =
          (byte)
              CLASS_ADD_METHOD.invokeExact(
                  cls,
                  sel(a, "userContentController:didReceiveScriptMessage:"),
                  stub,
                  a.allocateFrom("v@:@@"));

      REGISTER_CLASS_PAIR.invokeExact(cls);
      return (MemorySegment) CLASS_CREATE_INSTANCE.invokeExact(cls, 0L);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
