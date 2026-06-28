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
 * <p>Must be created and {@link #run()} called on the first thread
 * (pass {@code -XstartOnFirstThread} to the JVM).
 */
final class CocoaWebView extends WebviewBase {

  private static final String HANDLER_NAME = "__webview__";
  private static final String POST_FN =
      "function(message){return window.webkit.messageHandlers.__webview__.postMessage(message);}";

  // NSWindow style mask constants
  private static final long NS_TITLED               = 1L;
  private static final long NS_CLOSABLE             = 2L;
  private static final long NS_MINIATURIZABLE        = 4L;
  private static final long NS_RESIZABLE            = 8L;
  private static final long NS_STANDARD_WINDOW_MASK = NS_TITLED | NS_CLOSABLE | NS_MINIATURIZABLE | NS_RESIZABLE;
  private static final long NS_BACKING_BUFFERED     = 2L;

  // WKUserScriptInjectionTime
  private static final long WK_INJECT_AT_DOCUMENT_START = 0L;

  // libdispatch for cross-thread dispatch to main queue
  private static final MemorySegment DISPATCH_MAIN_QUEUE;
  private static final MethodHandle DISPATCH_ASYNC_F;

  // Shared Cocoa state
  private static volatile boolean nsAppInitDone = false;
  private static final AtomicInteger openWindows = new AtomicInteger(0);

  static {
    var libDispatch = SymbolLookup.libraryLookup("libdispatch.dylib", Arena.global());
    var linker = Linker.nativeLinker();
    DISPATCH_MAIN_QUEUE =
        libDispatch
            .find("_dispatch_main_q")
            .orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));
    DISPATCH_ASYNC_F =
        linker.downcallHandle(
            libDispatch.find("dispatch_async_f").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
  }

  // Instance state
  private MemorySegment nsWindow;
  private MemorySegment wkWebView;
  private MemorySegment ucController;
  private volatile boolean closed = false;
  private final CountDownLatch windowClosedLatch = new CountDownLatch(1);
  private final Arena callbackArena = Arena.ofShared();
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
    try (Arena a = Arena.ofConfined()) {
      MemorySegment app = send0(ObjC.getClass(a,"NSApplication"), sel(a, "sharedApplication"));
      sendVoid0(app, sel(a, "run"));
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    dispatchImpl(() -> {
      try (Arena a = Arena.ofConfined()) {
        sendVoid1(nsWindow, sel(a, "close"), MemorySegment.NULL);
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
  // WebviewBase — platform impls (called on main thread via dispatchImpl)
  // -------------------------------------------------------------------------

  @Override
  protected void navigateImpl(String url) {
    try (Arena a = Arena.ofConfined()) {
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
      // Remove resizable style mask bit
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
      sendVoid1(
          wkWebView,
          sel(a, "loadHTMLString:baseURL:"),
          nsString(a, html)); // passing just html; 2-arg form needs special MSG_SEND_2
    }
  }

  @Override
  protected void evalImpl(String js) {
    try (Arena a = Arena.ofConfined()) {
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
  // Upcall targets
  // -------------------------------------------------------------------------

  @SuppressWarnings("unused")
  public void drainDispatchQueue(MemorySegment ctx) {
    Runnable r;
    while ((r = pendingDispatches.poll()) != null) r.run();
  }

  /** Called by WKScriptMessageHandler implementation: (id, SEL, UCController, WKScriptMessage). */
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

  private static void initNSApp() {
    if (nsAppInitDone) return;
    try (Arena a = Arena.ofConfined()) {
      MemorySegment NSApp = ObjC.getClass(a, "NSApplication");
      MemorySegment app = send0(NSApp, sel(a, "sharedApplication"));
      // NSApplicationActivationPolicyRegular = 0
      Linker.nativeLinker()
          .downcallHandle(
              MSG_SEND_ADDR,
              FunctionDescriptor.of(
                  ValueLayout.JAVA_BYTE,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_LONG))
          .invokeExact(app, sel(a, "setActivationPolicy:"), 0L);
      MacOSHelper.createMenus();
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
    nsAppInitDone = true;
  }

  private void initWindowAndWebView(boolean debug, int width, int height) {
    try (Arena a = Arena.ofConfined()) {
      // WKWebViewConfiguration
      MemorySegment WKConfig = ObjC.getClass(a, "WKWebViewConfiguration");
      MemorySegment config = send0(send0(WKConfig, sel(a, "alloc")), sel(a, "init"));

      // WKUserContentController
      ucController = send0(config, sel(a, "userContentController"));

      // Register script message handler (WKScriptMessageHandler impl)
      MemorySegment handler = createScriptHandler(a);
      send2(
          ucController,
          sel(a, "addScriptMessageHandler:name:"),
          handler,
          nsString(a, HANDLER_NAME));

      // WKWebView
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

      // NSWindow
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

      sendVoid1(nsWindow, sel(a, "setContentView:"), wkWebView);
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);

      // Activate app
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

  private MemorySegment createScriptHandler(Arena a) {
    try {
      // Create a new ObjC class that implements WKScriptMessageHandler
      MemorySegment superCls = ObjC.getClass(a, "NSObject");
      // Use a unique class name per instance to avoid conflicts
      String clsName = "JavaWebviewHandler_" + System.identityHashCode(this);
      MemorySegment cls =
          (MemorySegment) ALLOC_CLASS_PAIR.invokeExact(superCls, a.allocateFrom(clsName), 0L);

      // Build upcall stub for the handler method
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
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS,
                      ValueLayout.ADDRESS),
                  callbackArena);

      // Add method: - (void)userContentController:(WKUserContentController*)ucc
      //                     didReceiveScriptMessage:(WKScriptMessage*)message
      // Type encoding: "v@:@@"
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
