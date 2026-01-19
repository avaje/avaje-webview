/**
 * MIT LICENSE
 *
 * <p>Copyright (c) 2024 Alex Bowles @ Casterlabs
 *
 * <p>Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * <p>The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * <p>THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.avaje.webview;

import static io.avaje.webview.platform.OSDistribution.MACOS;
import static io.avaje.webview.platform.OSFamily.WINDOWS;
import static io.avaje.webview.platform.Platform.OS_DISTRIBUTION;
import static io.avaje.webview.platform.Platform.OS_FAMILY;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import module java.base;
import module org.jspecify;

/**
 * Webview browser window.
 *
 * <pre>{@code
 * Webview wv = Webview.builder()
 *          .debug(true)
 *          .title("My App")
 *          .width(1000)
 *          .height(800)
 *          .url("http://localhost:" + port)
 *          .build();
 *
 *  wv.run(); // Run the webview event loop, the webview is fully disposed when this returns.
 *  wv.close(); // Free any resources allocated.
 *
 * }</pre>
 */
final class DWebView implements Webview {

  private static final System.Logger log = System.getLogger("io.avaje.webview");
  private static final String MACOS_RELOAD =
      "Reload the application with -XstartOnFirstThread to fix this.";

  private static final String ERROR_MAC_NO_XSTART_ON_FIRST_THREAD =
      "Process was not started with -XstartOnFirstThread. ";

  private static final String ERROR_MAC_NOT_MAIN_THREAD =
      "Cannot create webview on a non-main thread on MacOS.";

  private static final int WV_HINT_NONE = 0;
  private static final int WV_HINT_MIN = 1;
  private static final int WV_HINT_MAX = 2;
  private static final int WV_HINT_FIXED = 3;
  private static final FunctionDescriptor BIND_DESCRIPTOR =
      FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS);
  private static final FunctionDescriptor DISPATCH_DESCRIPTOR =
      FunctionDescriptor.ofVoid(
          ADDRESS, // webview pointer
          JAVA_LONG // arg
          );

  private final MemorySegment webview;
  private final WebviewNative wbNative;

  private final Arena arena = Arena.ofAuto();

  public static WebviewBuilder builder() {
    return new WebviewBuilder();
  }

  DWebView(
      WebviewNative webNative,
      boolean debug,
      @Nullable MemorySegment windowPointer,
      int width,
      int height) {

    checkEnvironment();
    wbNative = webNative;
    webview =
        wbNative.webview_create(
            debug, windowPointer == null ? MemorySegment.NULL : windowPointer);

    this.setSize(width, height);
    if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.createMenus();
    }
  }

  @Override
  public MemorySegment nativeWindowPointer() {
    return wbNative.webview_get_window(webview);
  }

  @Override
  public void setHTML(@Nullable String html) {
    wbNative.webview_set_html(webview, html);
  }

  @Override
  public void navigate(@Nullable String url) {
    wbNative.webview_navigate(webview, url == null ? "about:blank" : url);
  }

  @Override
  public void setTitle(@NonNull String title) {
    wbNative.webview_set_title(webview, title);
    if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.setApplicationName(title);
    }
  }

  @Override
  public void setMinSize(int width, int height) {
    wbNative.webview_set_size(webview, width, height, WV_HINT_MIN);
  }

  @Override
  public void setMaxSize(int width, int height) {
    wbNative.webview_set_size(webview, width, height, WV_HINT_MAX);
  }

  @Override
  public void setSize(int width, int height) {
    wbNative.webview_set_size(webview, width, height, WV_HINT_NONE);
  }

  @Override
  public void setFixedSize(int width, int height) {
    wbNative.webview_set_size(webview, width, height, WV_HINT_FIXED);
  }

  /**
   * Sets the script to be run on page load. Defaults to no nested access (false).
   *
   * @implNote This get's called AFTER window.load.
   * @param script
   * @see #setInitScript(String, boolean)
   */
  @Override
  public void setInitScript(@NonNull String script) {
    setInitScript(script, false);
  }

  @Override
  public void setInitScript(@NonNull String script, boolean allowNestedAccess) {
    var script1 = String.format(
            """
            (() => {
            try {
            if (window.top == window.self || %b) {
            %s
            }
            } catch (e) {
            console.error('[Webview]', 'An error occurred whilst evaluating init script:', %s, e);
            }
            })();""",
              allowNestedAccess, script, '"' + WebviewUtil.jsonEscape(script) + '"');

      wbNative.webview_init(webview, script1);
  }

  @Override
  public void eval(@NonNull String script) {
    wbNative.webview_eval(
        webview,
        String.format(
            """
      try {
      %s
      } catch (e) {
      console.error('[Webview]', 'An error occurred whilst evaluating script:', %s, e);
      }""",
            script, '"' + WebviewUtil.jsonEscape(script) + '"'));
  }

  @Override
  public void bind(@NonNull String name, @NonNull WebviewBindCallback handler) {
    bindCallback(name, handler);
  }

  private void bindCallback(String name, WebviewBindCallback handler) {
    BindCallback callback =
        (seq, req) -> {
          try {
            req = WebviewUtil.forceSafeChars(req);

            String result = handler.apply(req);
            if (result == null) {
              result = "null";
            }

            wbNative.webview_return(webview, seq, false, WebviewUtil.forceSafeChars(result));
          } catch (Throwable e) {
            String stacktrace = WebviewUtil.getExceptionStack(e);
            log.log(ERROR, stacktrace);

            String exceptionJson = '"' + WebviewUtil.jsonEscape(stacktrace) + '"';

            wbNative.webview_return(webview, seq, true, exceptionJson);
          }
        };

    // Create upcall stub for the callback
    MemorySegment callbackStub =
        Linker.nativeLinker()
            .upcallStub(createBindCallbackHandle(callback), BIND_DESCRIPTOR, arena);

    wbNative.webview_bind(webview, name, callbackStub, 0);
  }

  @SuppressWarnings("unused")
  private static void bindCallbackInvoke(BindCallback callback, long seq, MemorySegment req) {
    callback.callback(seq, req.reinterpret(Long.MAX_VALUE).getString(0));
  }

  private static MethodHandle createBindCallbackHandle(BindCallback callback) {
    try {
      return MethodHandles.insertArguments(
          MethodHandles.lookup()
              .findStatic(
                  DWebView.class,
                  "bindCallbackInvoke",
                  MethodType.methodType(
                      void.class, BindCallback.class, long.class, MemorySegment.class)),
          0,
          callback);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create callback handle", e);
    }
  }

  @Override
  public void unbind(@NonNull String name) {
    wbNative.webview_unbind(webview, name);
  }

  @Override
  public void dispatch(@NonNull Runnable handler) {
    // Create upcall stub for the dispatch callback
    MemorySegment callbackStub =
        Linker.nativeLinker()
            .upcallStub(
                createDispatchCallbackHandle((_, _) -> handler.run()), DISPATCH_DESCRIPTOR, arena);

    wbNative.webview_dispatch(webview, callbackStub, 0);
  }

  private static MethodHandle createDispatchCallbackHandle(DispatchCallback callback) {
    try {
      return MethodHandles.lookup()
          .bind(
              callback,
              "callback",
              MethodType.methodType(void.class, MemorySegment.class, long.class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create callback handle", e);
    }
  }

  @Override
  public void run() {
    wbNative.webview_run(webview);
    log.log(DEBUG, "destroy and terminate");
    wbNative.webview_destroy(webview);
    wbNative.webview_terminate(webview);
  }

  @Override
  public void close() {
    log.log(DEBUG, "close");
    wbNative.webview_terminate(webview);
  }

  @Override
  public void setDarkAppearance(boolean shouldAppearDark) {
    if (WINDOWS == OS_FAMILY) {
      WindowsHelper.setWindowAppearance(this, shouldAppearDark);
    } else if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.setWindowAppearance(this, shouldAppearDark);
    }
  }

  @Override
  public Webview maximizeWindow() {
    if (WINDOWS == OS_FAMILY) {
      WindowsHelper.maximizeWindow(this);
    } else if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.maximizeWindow(this);
    }
    return this;
  }

  @Override
  public Webview fullscreen() {
    if (WINDOWS == OS_FAMILY) {
      WindowsHelper.fullscreen(this);
    } else if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.fullscreen(this);
    }
    return this;
  }

  @Override
  public String version() {
    return wbNative.webview_version();
  }

  @Override
  public void setIcon(Path iconPath) {
    if (!Files.exists(iconPath)) {
      throw new IllegalArgumentException("Icon file not found: " + iconPath);
    }
    if (WINDOWS == OS_FAMILY) {
      WindowsHelper.setIcon(this, iconPath);
    } else if (OS_DISTRIBUTION == MACOS) {
      MacOSHelper.setIcon(this, iconPath);
    }
  }

  @Override
  public void setIcon(URI classPath) {
    try {
      Path tempFile = Files.createTempFile("webview_icon_", ".ico");
      tempFile.toFile().deleteOnExit();
      try (InputStream is = classPath.toURL().openStream()) {
        Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
      }
      setIcon(tempFile);
    } catch (IOException e) {
      throw new RuntimeException("Failed to extract resource to temp file", e);
    }
  }

  /**
   * Checks the environment to ensure compatibility with the current platform.
   *
   * <p>This method performs platform-specific checks to verify that the application is running in a
   * supported environment.
   *
   * @throws UnsupportedOperationException if the environment does not meet the required conditions.
   */
  private void checkEnvironment() {
    if (OS_DISTRIBUTION == MACOS) {
      var mainThread = "main".equals(Thread.currentThread().getName());
      if (!mainThread) {
        throw new UnsupportedOperationException(ERROR_MAC_NOT_MAIN_THREAD);
      }
      if (!MacOSHelper.startedOnFirstThread()) {
        throw new UnsupportedOperationException(ERROR_MAC_NO_XSTART_ON_FIRST_THREAD + MACOS_RELOAD);
      }
    }
  }

  /** Used in {@code webview_bind} */
  @FunctionalInterface
  private interface BindCallback {
    /**
     * @param seq The request id, used in {@code webview_return}
     * @param req The javascript arguments converted to a json array (string)
     */
    void callback(long seq, String req);
  }

  /** Used in {@code webview_dispatch} */
  @FunctionalInterface
  private interface DispatchCallback {
    /**
     * @param webview The pointer of the webview
     * @param arg Unused
     */
    void callback(MemorySegment webview, long arg);
  }
}
