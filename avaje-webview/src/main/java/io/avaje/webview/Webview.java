/**
 * MIT LICENSE
 *
 * Copyright (c) 2024 Alex Bowles @ Casterlabs
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.avaje.webview;

import io.avaje.webview.platform.Platform;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import module java.base;

import static io.avaje.webview.WebviewNative.*;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Webview browser window.
 *
 * <pre>{@code
 *
 *    Webview wv = Webview.builder()
 *             .debug(true)
 *             .title("My App")
 *             .width(1000)
 *             .height(800)
 *             .url("http://localhost:" + port)
 *             .build();
 *
 *     wv.run(); // Run the webview event loop, the webview is fully disposed when this returns.
 *     wv.close(); // Free any resources allocated.
 *
 * }</pre>
 */
public final class Webview implements Closeable, Runnable {

  static final System.Logger log = System.getLogger("io.avaje.webview");

  private final WebviewNative N;
  private final MemorySegment webview;
  private final Arena arena = Arena.ofShared();

  public static WebviewBuilder builder() {
    return new WebviewBuilder();
  }

  Webview(
      WebviewNative n,
      boolean debug,
      @Nullable MemorySegment windowPointer,
      int width,
      int height) {
    this.N = n;
    this.webview =
        N.webview_create(debug, windowPointer == null ? MemorySegment.NULL : windowPointer);
    this.setSize(width, height);
  }

  /**
   * @deprecated Use this only if you absolutely know what you're doing.
   */
  @Deprecated
  public MemorySegment getNativeWindowPointer() {
    return N.webview_get_window(webview);
  }

  public void setHTML(@Nullable String html) {
    N.webview_set_html(webview, html);
  }

  public void loadURL(@Nullable String url) {
    if (url == null) {
      url = "about:blank";
    }
    N.webview_navigate(webview, url);
  }

  public void setTitle(@NonNull String title) {
    N.webview_set_title(webview, title);
  }

  public void setMinSize(int width, int height) {
    N.webview_set_size(webview, width, height, WV_HINT_MIN);
  }

  public void setMaxSize(int width, int height) {
    N.webview_set_size(webview, width, height, WV_HINT_MAX);
  }

  public void setSize(int width, int height) {
    N.webview_set_size(webview, width, height, WV_HINT_NONE);
  }

  public void setFixedSize(int width, int height) {
    N.webview_set_size(webview, width, height, WV_HINT_FIXED);
  }

  /**
   * Sets the script to be run on page load. Defaults to no nested access (false).
   *
   * @implNote This get's called AFTER window.load.
   * @param script
   * @see #setInitScript(String, boolean)
   */
  public void setInitScript(@NonNull String script) {
    this.setInitScript(script, false);
  }

  /**
   * Sets the script to be run on page load.
   *
   * @implNote This get's called AFTER window.load.
   * @param script
   * @param allowNestedAccess whether or not to inject the script into nested iframes.
   */
  public void setInitScript(@NonNull String script, boolean allowNestedAccess) {
    script =
        String.format(
            "(() => {\n"
                + "try {\n"
                + "if (window.top == window.self || %b) {\n"
                + "%s\n"
                + "}\n"
                + "} catch (e) {\n"
                + "console.error('[Webview]', 'An error occurred whilst evaluating init script:', %s, e);\n"
                + "}\n"
                + "})();",
            allowNestedAccess, script, '"' + _WebviewUtil.jsonEscape(script) + '"');

    N.webview_init(webview, script);
  }

  /**
   * Executes the given script NOW.
   *
   * @param script
   */
  public void eval(@NonNull String script) {
    this.dispatch(
        () -> {
          N.webview_eval(
              webview,
              String.format(
                  "try {\n"
                      + "%s\n"
                      + "} catch (e) {\n"
                      + "console.error('[Webview]', 'An error occurred whilst evaluating script:', %s, e);\n"
                      + "}",
                  script, '"' + _WebviewUtil.jsonEscape(script) + '"'));
        });
  }

  /**
   * Binds a function to the JavaScript environment on page load.
   *
   * @implNote This get's called AFTER window.load.
   * @implSpec After calling the function in JavaScript you will get a Promise instead of the value.
   *     This is to prevent you from locking up the browser while waiting on your Java code to
   *     execute and generate a return value.
   * @param name The name to be used for the function, e.g "foo" to get foo().
   * @param handler The callback handler, accepts a JsonArray (which are all arguments passed to the
   *     function()) and returns a value which is of type JsonElement (can be null). Exceptions are
   *     automatically passed back to JavaScript.
   */
  public void bind(@NonNull String name, @NonNull WebviewBindCallback handler) {
    BindCallback callback =
        (seq, req, arg) -> {
          try {
            req = _WebviewUtil.forceSafeChars(req);

            String result = handler.apply(req);
            if (result == null) {
              result = "null";
            }

            N.webview_return(webview, seq, false, _WebviewUtil.forceSafeChars(result));
          } catch (Throwable e) {
            e.printStackTrace();

            String exceptionJson =
                '"' + _WebviewUtil.jsonEscape(_WebviewUtil.getExceptionStack(e)) + '"';

            N.webview_return(webview, seq, true, exceptionJson);
          }
        };

    // Create upcall stub for the callback
    MemorySegment callbackStub =
        Linker.nativeLinker()
            .upcallStub(createBindCallbackHandle(callback), BindCallback.DESCRIPTOR, arena);

    N.webview_bind(webview, name, callbackStub, 0);
  }

  private static MethodHandle createBindCallbackHandle(BindCallback callback) {
    try {
      return MethodHandles.lookup()
          .bind(
              callback,
              "callback",
              MethodType.methodType(
                  void.class, long.class, MemorySegment.class, long.class))
          .asType(
              MethodType.methodType(
                  void.class, long.class, MemorySegment.class, long.class));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create callback handle", e);
    }
  }

  /**
   * Unbinds a function, removing it from future pages.
   *
   * @param name The name of the function.
   */
  public void unbind(@NonNull String name) {
    N.webview_unbind(webview, name);
  }

  /**
   * Executes an event on the event thread.
   *
   * @deprecated Use this only if you absolutely know what you're doing.
   */
  @Deprecated
  public void dispatch(@NonNull Runnable handler) {
    DispatchCallback callback = (wv, arg) -> handler.run();

    // Create upcall stub for the dispatch callback
    MemorySegment callbackStub =
        Linker.nativeLinker()
            .upcallStub(createDispatchCallbackHandle(callback), DispatchCallback.DESCRIPTOR, arena);

    N.webview_dispatch(webview, callbackStub, 0);
  }

  private static MethodHandle createDispatchCallbackHandle(
      DispatchCallback callback) {
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

  /**
   * Executes the webview event loop until the user presses "X" on the window.
   *
   * @see #close()
   */
  @Override
  public void run() {
    N.webview_run(webview);
    log.log(DEBUG, "destroy and terminate");
    N.webview_destroy(webview);
    N.webview_terminate(webview);
  }

  /**
   * Executes the webview event loop asynchronously until the user presses "X" on the window.
   *
   * @see #close()
   */
  public void runAsync() {
    Thread t = new Thread(this);
    t.setDaemon(false);
    t.setName("Webview RunAsync Thread - #" + this.hashCode());
    t.start();
  }

  /** Closes the webview, call this to end the event loop and free up resources. */
  @Override
  public void close() {
    log.log(DEBUG, "close");
    N.webview_terminate(webview);
    arena.close();
  }

  public void setDarkAppearance(boolean shouldAppearDark) {
    switch (Platform.osFamily) {
      case WINDOWS:
        _WindowsHelper.setWindowAppearance(this, shouldAppearDark);
        break;

      default: // NOOP
        break;
    }
  }

  public String getVersion() {
    VersionInfo versionInfo = N.webview_version();
    return versionInfo.versionNumber();
  }
}
