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

/**
 * A callback interface for handling function calls invoked from the JavaScript environment.
 *
 * <p>When a function is registered via {@link Webview#bind(String, WebviewBindCallback)}, calling
 * that function in JavaScript serializes the arguments into a JSON array and passes them to this
 * {@code apply} method.
 */
@FunctionalInterface
public interface WebviewBindCallback {

  /**
   * Processes a call from the webview's JavaScript context.
   *
   * @param jsonArgs A JSON-encoded string representing an array of arguments passed from JavaScript
   *     (e.g., {@code "[1, \"hello\", true]"}).
   * @return A JSON-encoded string representing the return value to be sent back to JavaScript.
   *     Return {@code "null"} (as a string) for void functions.
   * @throws Throwable Any exception thrown will be caught by the bridge and passed to the
   *     JavaScript Promise's {@code .catch()} handler.
   */
  String apply(String jsonArgs) throws Throwable;
}
