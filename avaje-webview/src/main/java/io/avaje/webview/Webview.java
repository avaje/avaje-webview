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

import module java.base;

import module org.jspecify;

public interface Webview extends Closeable, Runnable {

  static WebviewBuilder builder() {
    return new WebviewBuilder();
  }

  /** Use this only if you absolutely know what you're doing. */
  MemorySegment nativeWindowPointer();

  void setHTML(@Nullable String html);

  void loadURL(@Nullable String url);

  void setTitle(@NonNull String title);

  void setMinSize(int width, int height);

  void setMaxSize(int width, int height);

  void setSize(int width, int height);

  void setFixedSize(int width, int height);

  /**
   * Sets the script to be run on page load. Defaults to no nested access (false).
   *
   * @implNote This get's called AFTER window.load.
   * @param script
   * @see #setInitScript(String, boolean)
   */
  void setInitScript(@NonNull String script);

  /**
   * Sets the script to be run on page load.
   *
   * @implNote This get's called AFTER window.load.
   * @param script
   * @param allowNestedAccess whether or not to inject the script into nested iframes.
   */
  void setInitScript(@NonNull String script, boolean allowNestedAccess);

  /**
   * Executes the given script NOW.
   *
   * @param script
   */
  void eval(@NonNull String script);

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
  void bind(@NonNull String name, @NonNull WebviewBindCallback handler);

  /**
   * Unbinds a function, removing it from future pages.
   *
   * @param name The name of the function.
   */
  void unbind(@NonNull String name);

  /**
   * Executes an event on the event thread.
   *
   * @implNote Use this only if you absolutely know what you're doing.
   */
  void dispatch(@NonNull Runnable handler);

  /**
   * Executes the webview event loop until the user presses "X" on the window. Will block the
   * calling thread if webview is not in asynchronous mode
   *
   * @see #close()
   */
  @Override
  void run();

  /** Closes the webview, call this to end the event loop and free up resources. */
  @Override
  void close();

  void setDarkAppearance(boolean shouldAppearDark);

  Webview maximizeWindow();

  Webview fullscreen();

  String version();
}
