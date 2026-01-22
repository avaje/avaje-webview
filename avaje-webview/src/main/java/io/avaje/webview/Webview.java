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

/**
 * Provides a high-level interface for creating and managing a native Webview window.
 *
 * <p>A {@code Webview} instance allows for rendering HTML/URL content, executing JavaScript, and
 * binding Java callbacks to the JavaScript environment.
 */
public interface Webview extends Closeable, Runnable {

  /**
   * Creates a new builder to configure and instantiate a {@code Webview}.
   *
   * @return a new Builder instance
   */
  static Builder builder() {
    return new WebviewBuilder();
  }

  /**
   * Returns the native window handle/pointer.
   *
   * <p><strong>Caution:</strong> This provides direct access to the underlying native memory. Use
   * this only if you are integrating with other native libraries and understand the threading
   * implications.
   *
   * @return the {@link MemorySegment} pointing to the native window
   */
  MemorySegment nativeWindowPointer();

  /**
   * Sets the HTML content of the webview directly.
   *
   * @param html the HTML string to render, or {@code null} to clear
   */
  void setHTML(@Nullable String html);

  /**
   * Navigates the webview to the specified URL.
   *
   * @param url the URL to load (e.g., "https://google.com"), or {@code null}
   */
  void navigate(@Nullable String url);

  /**
   * Sets the title of the native window.
   *
   * @param title the window title
   */
  void setTitle(@NonNull String title);

  /**
   * Sets the minimum window dimensions.
   *
   * @param width the minimum width in pixels
   * @param height the minimum height in pixels
   */
  void setMinSize(int width, int height);

  /**
   * Sets the maximum window dimensions.
   *
   * @param width the maximum width in pixels
   * @param height the maximum height in pixels
   */
  void setMaxSize(int width, int height);

  /**
   * Resizes the window to the specified dimensions.
   *
   * @param width the width in pixels
   * @param height the height in pixels
   */
  void setSize(int width, int height);

  /**
   * Sets the window size and prevents the user from manually resizing it.
   *
   * @param width the width in pixels
   * @param height the height in pixels
   */
  void setFixedSize(int width, int height);

  /**
   * Registers a script to be executed automatically whenever a new page is loaded. Defaults to no
   * nested access (script will not run in iframes).
   *
   * @param script the JavaScript source code to run
   * @implNote The script is executed immediately after the {@code window.load} event.
   * @see #setInitScript(String, boolean)
   */
  void setInitScript(@NonNull String script);

  /**
   * Registers a script to be executed automatically whenever a new page is loaded.
   *
   * @param script the JavaScript source code to run
   * @param allowNestedAccess if {@code true}, the script will also be injected into nested iframes
   * @implNote The script is executed immediately after the {@code window.load} event.
   */
  void setInitScript(@NonNull String script, boolean allowNestedAccess);

  /**
   * Evaluates the provided JavaScript string immediately in the current context.
   *
   * @param script the JavaScript source code to execute
   */
  void eval(@NonNull String script);

  /**
   * Binds a Java callback to a global JavaScript function.
   *
   * <p>When the function is called in JavaScript, it returns a {@code Promise} that resolves when
   * the Java handler completes. This prevents the browser UI thread from locking up during Java
   * execution.
   *
   * @param name the name of the function in the JavaScript {@code window} object (e.g.,
   *     "submitData")
   * @param handler the callback logic to execute when the function is invoked
   * @implNote Binds persist across page navigations. Callbacks are registered after {@code
   *     window.load}.
   */
  void bind(@NonNull String name, @NonNull WebviewBindCallback handler);

  /**
   * Removes a previously bound JavaScript function.
   *
   * @param name the name of the function to unbind
   */
  void unbind(@NonNull String name);

  /**
   * Schedules a task to be executed on the webview's internal event thread.
   *
   * <p>Use this for thread-safe interaction with the webview state from external background
   * threads.
   *
   * @param handler the task to run on the event thread
   */
  void dispatch(@NonNull Runnable handler);

  /**
   * Starts the webview event loop.
   *
   * <p>This call is blocking and will not return until the window is closed or {@link #close()} is
   * called, unless the webview is configured in asynchronous mode.
   *
   * @see #close()
   */
  @Override
  void run();

  /**
   * Closes the webview window and releases all associated native resources. This will cause the
   * {@link #run()} loop to exit.
   */
  @Override
  void close();

  /**
   * Requests the window to use a dark theme appearance.
   *
   * @param shouldAppearDark {@code true} for dark mode, {@code false} for light mode
   */
  void setDarkAppearance(boolean shouldAppearDark);

  /**
   * Maximizes the webview window to fill the screen.
   *
   * @return this Webview instance for chaining
   */
  Webview maximizeWindow();

  /**
   * Switches the webview window to fullscreen mode.
   *
   * @return this Webview instance for chaining
   */
  Webview fullscreen();

  /**
   * Returns the version string of the underlying webview engine.
   *
   * @return the engine version
   */
  String version();

  /**
   * Sets the icon for the webview window
   *
   * @param path to the icon file
   */
  void setIcon(Path path);

  /**
   * Sets the icon for the webview window, use this for classpath resources
   *
   * @param URI to the icon file
   */
  void setIcon(URI uri);

  /** Interface for configuring and instantiating {@link Webview} instances. */
  public interface Builder {

    /**
     * Configures the builder to extract native libraries to the system's temporary directory.
     *
     * @param extractToTemp if {@code true}, uses {@code java.io.tmpdir}
     * @return this builder
     */
    Builder extractToTemp(boolean extractToTemp);

    /**
     * Configures the builder to extract native libraries to a persistent directory in the user's
     * home folder ({@code ${user.home}/.avaje-webview/}).
     *
     * <p><strong>Performance Note:</strong> When enabled, libraries are only extracted once,
     * significantly reducing startup time for subsequent executions.
     *
     * @param extractToUserHome if {@code true}, caches libraries in the user's home directory
     * @return this builder
     */
    Builder extractToUserHome(boolean extractToUserHome);

    /**
     * Sets the title of the webview window.
     *
     * @param title the window title
     * @return this builder
     */
    Builder title(String title);

    /**
     * Enables or disables browser developer tools (Right-click > Inspect).
     *
     * @param enableDeveloperTools {@code true} to enable tools (if supported by the platform)
     * @return this builder
     */
    Builder enableDeveloperTools(boolean enableDeveloperTools);

    /**
     * Attaches the webview to an existing native window handle.
     *
     * @param windowPointer a {@link MemorySegment} pointing to a native window handle
     * @return this builder
     */
    Builder windowPointer(MemorySegment windowPointer);

    /**
     * Sets the initial width of the window. Defaults to 800.
     *
     * @param width width in pixels
     * @return this builder
     */
    Builder width(int width);

    /**
     * Sets the initial height of the window. Defaults to 600.
     *
     * @param height height in pixels
     * @return this builder
     */
    Builder height(int height);

    /**
     * Sets the initial HTML content to be rendered.
     *
     * @param html raw HTML string
     * @return this builder
     */
    Builder html(String html);

    /**
     * Sets the initial URL for the webview to load.
     *
     * @param url the URL (e.g., "https://localhost:8080")
     * @return this builder
     */
    Builder navigate(String url);

    /**
     * Determines if a JVM shutdown hook should be registered to automatically clean up native
     * resources. Defaults to {@code true}.
     *
     * @param shutdownHook {@code true} to enable automatic cleanup
     * @return this builder
     */
    Builder shutdownHook(boolean shutdownHook);

    /**
     * Builds a Webview using the configuration
     *
     * @return a configured Webview instance
     */
    Webview build();
  }
}
