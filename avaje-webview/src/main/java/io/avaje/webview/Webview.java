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
 * A native webview window.
 *
 * <p>Renders HTML or a URL, runs JavaScript in the page, and binds Java callbacks into the page's
 * JavaScript environment.
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
   * Opens {@code uri} in the user's default browser, or in their mail client for a {@code mailto:}
   * link.
   *
   * @param uri an {@code http}, {@code https} or {@code mailto} URI
   * @throws IllegalArgumentException for any other kind of URI
   * @throws UncheckedIOException if the operating system couldn't be asked to open it
   */
  static void openExternal(@NonNull URI uri) {
    WebviewUtil.openExternal(uri);
  }

  /**
   * Returns the native window handle/pointer.
   *
   * <p>Direct access to native memory. Only worth reaching for when integrating with another
   * native library, and only with the platform's threading rules in mind.
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
   * @param url the URL to load, such as "https://google.com", or {@code null}
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
   * Registers a script to run automatically on every page load. Iframes are excluded by default.
   *
   * @param script the JavaScript source code to run
   * @implNote The script is executed immediately after the {@code window.load} event.
   * @see #setInitScript(String, boolean)
   */
  void setInitScript(@NonNull String script);

  /**
   * Registers a script to run automatically on every page load.
   *
   * @param script the JavaScript source code to run
   * @param allowNestedAccess {@code true} to inject the script into nested iframes as well
   * @implNote The script is executed immediately after the {@code window.load} event.
   */
  void setInitScript(@NonNull String script, boolean allowNestedAccess);

  /**
   * Evaluates the provided JavaScript string immediately in the current context.
   *
   * <p>Nothing comes back from the script. Use {@link #evalResult} when you need a value.
   *
   * @param script the JavaScript source code to execute
   */
  void eval(@NonNull String script);

  /**
   * Evaluates a JavaScript expression in the current page and returns its value.
   *
   * <p>You can use {@code await} in the expression, and if it returns a {@code Promise} the future
   * waits for it. The value comes back as JSON from {@code JSON.stringify}, and {@code undefined}
   * comes back as {@code "null"}.
   *
   * {@snippet :
   * webview.evalResult("document.title")
   *     .thenAccept(title -> System.out.println(title)); // "My App" with its JSON quotes
   * webview.evalResult("await fetch('/status').then(r => r.json())")
   *     .thenAccept(json -> System.out.println(json));
   * }
   *
   * <p>The future completes on the UI thread, so {@code get()} and {@code join()} will throw {@link
   * IllegalStateException} if called on the UI thread.
   *
   * @param expression a single JavaScript expression, such as {@code "document.title"}. To run
   *     several statements, wrap them in an IIFE.
   * @return a future holding the result as JSON
   */
  CompletableFuture<String> evalResult(@NonNull String expression);

  /**
   * Binds a Java callback to a global JavaScript function.
   *
   * <p>Calling the function from JavaScript returns a {@code Promise} that settles once the Java
   * handler finishes, so the browser UI thread keeps running while Java works.
   *
   * @param name the name of the function on the JavaScript {@code window} object, such as
   *     "submitData"
   * @param handler the callback logic to execute when the function is invoked
   * @implNote Binds persist across page navigations. Callbacks are registered after {@code
   *     window.load}.
   */
  void bind(@NonNull String name, @NonNull WebviewBinding handler);

  /**
   * Removes a previously bound JavaScript function.
   *
   * @param name the name of the function to unbind
   */
  void unbind(@NonNull String name);

  /**
   * Schedules a task to be executed on the webview's internal event thread.
   *
   * <p>The safe way to touch webview state from a background thread.
   *
   * @param handler the task to run on the event thread
   */
  void dispatch(@NonNull Runnable handler);

  /**
   * Starts the webview event loop.
   *
   * <p>Blocks until the window closes or {@link #close()} is called, unless the webview is
   * configured in asynchronous mode.
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
   * Restores a maximized webview window back to its prior size and position.
   *
   * @return this Webview instance for chaining
   */
  Webview unmaximizeWindow();

  /**
   * Reports whether the webview window is currently maximized.
   *
   * @return {@code true} if the window is maximized
   */
  boolean isMaximized();

  /**
   * Switches the webview window to fullscreen mode.
   *
   * @return this Webview instance for chaining
   */
  Webview fullscreen();

  /**
   * Minimizes the webview window to the taskbar/dock.
   *
   * @return this Webview instance for chaining
   */
  Webview minimizeWindow();

  /**
   * Begins a native window-move operation, as if the user had grabbed the title bar and started
   * dragging.
   *
   * <p>Call this from a binding invoked on {@code mousedown} over a custom draggable area (e.g. a
   * custom title bar) to make that area drag the window.
   *
   * {@snippet :
   * webview.bind("startDrag", req -> {
   *   webview.startWindowDrag();
   *   return null;
   * });
   * }
   */
  void startWindowDrag();

  /**
   * Sets the icon for the webview window.
   *
   * @param path path to the icon file
   */
  void setIcon(Path path);

  /**
   * Sets the icon for the webview window, for icons held as classpath resources.
   *
   * @param uri location of the icon file
   */
  void setIcon(URI uri);

  /**
   * Hides the window without closing it. The page keeps running, and {@link #showWindow()} brings
   * the window back.
   *
   * @return this Webview instance for chaining
   */
  Webview hideWindow();

  /**
   * Shows the window if the window is hidden.
   *
   * @return this Webview instance for chaining
   * @see Builder#showOnFirstLoad(boolean)
   */
  Webview showWindow();

  /**
   * Restores the window after it was minimized.
   *
   * @return this Webview instance for chaining
   */
  Webview restoreWindow();

  /**
   * Returns whether the window is minimized.
   *
   * @return {@code true} if the window is minimized
   * @implNote On Linux this may always return {@code false}, because many Wayland compositors
   *     don't tell applications when they are minimized.
   */
  boolean isMinimized();

  /**
   * Leaves fullscreen and puts the window back how it was before.
   *
   * @return this Webview instance for chaining
   */
  Webview exitFullscreen();

  /**
   * Returns whether the window is fullscreen.
   *
   * @return {@code true} if the window is fullscreen
   */
  boolean isFullscreen();

  /**
   * Brings the window to the front and gives it keyboard focus, restoring it first if minimized.
   *
   * @return this Webview instance for chaining
   * @implNote Operating systems don't let applications steal focus freely. If another
   *     application is in use, the window may just flash in the taskbar or bounce in the dock.
   */
  Webview focusWindow();

  /**
   * Returns the size of the window's content area, the same size {@link #setSize(int, int)}
   * sets.
   *
   * @return the content size
   */
  WindowSize getSize();

  /**
   * Adds a check that runs when the user tries to close the window. Return {@code false} to keep
   * the window open, for example to ask the user whether they really want to close.
   *
   * <p>Calling {@link #close()} never runs this check.
   *
   * @param allowClose returns {@code true} to let the window close
   */
  void onCloseRequested(@NonNull BooleanSupplier allowClose);

  /**
   * Adds a listener that's called when the content size changes, whether the user resized the
   * window, it was maximized, or you called {@link #setSize(int, int)}.
   *
   * @param listener receives the new content size
   */
  void onResize(@NonNull Consumer<WindowSize> listener);

  /**
   * Adds a listener that's called when the window gains or loses keyboard focus.
   *
   * @param listener receives {@code true} on focus and {@code false} on blur
   */
  void onFocusChanged(@NonNull Consumer<Boolean> listener);

  /**
   * Adds a listener that's called every time a page finishes loading, whether from {@link
   * #navigate(String)}, {@link #setHTML(String)}, or the user following a link.
   *
   * @param listener receives the URL of the loaded page
   */
  void onPageLoaded(@NonNull Consumer<String> listener);

  /**
   * Adds a check that runs before the page navigates, whether the user clicked a link, a script
   * changed {@code location}, a form was submitted, or a server redirected. Return {@code false} to
   * cancel the navigation and stay on the current page. The check runs on the UI thread, so it
   * needs to return quickly.
   *
   * <p>Iframes are checked too, so an allowlist needs to include anything the page embeds.
   *
   * <p>Navigations started with {@link #navigate(String)} or {@link #setHTML(String)} do not trigger
   * this check.
   *
   * @param allow returns {@code true} to let the navigation go ahead based on the provided url
   */
  void onNavigate(@NonNull Predicate<String> allow);

  /**
   * Adds a listener that's called when the page asks for a new window, through a {@code
   * target="_blank"} link or {@code window.open}. The webview never opens a popup window itself.
   *
   * <p>Without any listener, links open in the default browser or mail client. Adding a listener
   * replaces that default, so you can open the URL in another {@code Webview}, in this one, or not
   * at all. Runs on the UI thread.
   *
   * @param listener receives the URL the page wanted to open
   */
  void onNewWindow(@NonNull Consumer<String> listener);

  /** Interface for configuring and instantiating {@link Webview} instances. */
  public interface Builder {

    /**
     * Sets the title of the webview window.
     *
     * @param title the window title
     * @return this builder
     */
    Builder title(String title);

    /**
     * Enables or disables browser developer tools, reached with right-click then Inspect.
     *
     * @param enableDeveloperTools {@code true} to enable them where the platform supports it
     * @return this builder
     */
    Builder enableDeveloperTools(boolean enableDeveloperTools);

    /**
     * Enables JS console redirection to {@link System.Logger}. Defaults to {@code false}.
     *
     * @param redirectConsole {@code true} to forward console output to the Java logger
     * @return this builder
     */
    Builder redirectConsole(boolean redirectConsole);

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
     * @param url the URL, such as "https://localhost:8080"
     * @return this builder
     */
    Builder navigate(String url);

    /**
     * Creates the window without native OS decorations (title bar, borders, and minimize/maximize/
     * close buttons). Defaults to {@code false}. The window remains resizable via its edges.
     *
     * <p>With no native title bar to grab, pair this with {@link Webview#startWindowDrag()} to
     * build a draggable title bar of your own.
     *
     * @param borderless {@code true} to remove native window decorations
     * @return this builder
     */
    Builder borderless(boolean borderless);

    /**
     * Creates the window without native OS decorations, with optional retention of the native
     * window outline (drop shadow and thin border).
     *
     * <p>With {@code outline} set, only the title bar goes and the surrounding border and drop
     * shadow stay. Platform behaviour:
     *
     * <ul>
     *   <li><b>Windows</b> removes only the title bar area; the left, right, and bottom borders
     *       remain, preserving the DWM drop shadow and 1-px outline.
     *   <li><b>macOS</b> uses a transparent, hidden title bar so the native window shadow and
     *       border are retained while web content covers the full window area.
     *   <li>on <b>Linux</b> this has no effect.
     * </ul>
     *
     * @param borderless {@code true} to remove native window decorations
     * @param outline {@code true} to keep the native window outline when borderless
     * @return this builder
     */
    Builder borderless(boolean borderless, boolean outline);

    /**
     * Marks this window as owned by {@code parent}, making it behave like a modal child/dialog
     * window.
     *
     * <p>The parent stops receiving mouse and keyboard input as soon as this window is built, and
     * gets it back when this window closes.
     *
     * @param parent the {@code Webview} that should be blocked while this window is open
     * @return this builder
     */
    Builder parent(Webview parent);

    /**
     * Marks this window as owned by {@code parent} and optionally locks their positions together.
     *
     * <p>With {@code moveParentWithChild} set, dragging the child moves the parent by the same
     * amount so the two stay visually locked.
     *
     * @param parent the {@code Webview} that should be blocked while this window is open
     * @param moveParentWithChild {@code true} to synchronise parent position with child
     * @return this builder
     */
    Builder parent(Webview parent, boolean moveParentWithChild);

    /**
     * Maximizes the window immediately after it is shown. Defaults to {@code false}.
     *
     * @param maximize {@code true} to start maximized
     * @return this builder
     */
    Builder maximize(boolean maximize);

    /**
     * Switches the window to fullscreen immediately after it is shown. Defaults to {@code false}.
     * Takes precedence over {@link #maximize(boolean)} when both are set.
     *
     * @param fullscreen {@code true} to start fullscreen
     * @return this builder
     */
    Builder fullscreen(boolean fullscreen);

    /**
     * Controls whether the maximize button is shown on the native title bar. Defaults to {@code
     * true}.
     *
     * <p>On Linux (GTK4) this has no effect.
     *
     * @param maximizable {@code false} to hide/disable the maximize button
     * @return this builder
     */
    Builder maximizable(boolean maximizable);

    /**
     * Sets the minimum size the user can resize the window to.
     *
     * @param width minimum width in pixels
     * @param height minimum height in pixels
     * @return this builder
     */
    Builder minSize(int width, int height);

    /**
     * Sets the maximum size the user can resize the window to.
     *
     * @param width maximum width in pixels
     * @param height maximum height in pixels
     * @return this builder
     */
    Builder maxSize(int width, int height);

    /**
     * Controls whether the user can resize the window. Defaults to {@code true}.
     *
     * <p>At {@code false} the window opens at the given {@link #width(int)} and {@link
     * #height(int)} and stays there, the same as calling {@link Webview#setFixedSize(int, int)}
     * right after build.
     *
     * @param resizable {@code false} to prevent user resizing
     * @return this builder
     */
    Builder resizable(boolean resizable);

    /**
     * Enables a transparent window background so web content with a transparent or semi-transparent
     * CSS background shows through to the desktop. Defaults to {@code false}.
     *
     * @param transparent {@code true} to enable transparency
     * @return this builder
     */
    Builder transparent(boolean transparent);

    /**
     * Whether the window shows itself when the first page has loaded. Defaults to {@code true}.
     *
     * <p>Pass {@code false} to keep it hidden until you call {@link Webview#showWindow()}.
     *
     * @param showOnFirstLoad decide starting load behavior
     * @return this builder
     */
    Builder showOnFirstLoad(boolean showOnFirstLoad);

    /**
     * Builds the Webview.
     *
     * @return the configured Webview
     */
    Webview build();
  }
}
