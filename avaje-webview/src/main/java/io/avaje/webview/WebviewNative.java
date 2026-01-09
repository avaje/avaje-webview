package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import module java.base;

final class WebviewNative {

  static WebviewBuilder builder() {
    return new WebviewBuilder();
  }

  private final SymbolLookup library;

  WebviewNative(String libraryName) {
    this.library = SymbolLookup.libraryLookup(libraryName, Arena.global());
    Arena.ofAuto();
  }

  WebviewNative() {
    this(
        System.getProperty("os.name").toLowerCase().contains("win")
            ? "webview.dll"
            : System.getProperty("os.name").toLowerCase().contains("mac")
                ? "libwebview.dylib"
                : "libwebview.so");
  }

  /** Version information structure */
  record VersionInfo(
      int major,
      int minor,
      int patch,
      String versionNumber,
      String preRelease,
      String buildMetadata) {

    static final StructLayout LAYOUT =
        MemoryLayout.structLayout(
            JAVA_INT.withName("major"),
            JAVA_INT.withName("minor"),
            JAVA_INT.withName("patch"),
            MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("version_number"),
            MemoryLayout.sequenceLayout(48, JAVA_BYTE).withName("pre_release"),
            MemoryLayout.sequenceLayout(48, JAVA_BYTE).withName("build_metadata"));

    static VersionInfo fromMemorySegment(MemorySegment segment) {
      int major = segment.get(JAVA_INT, 0);
      int minor = segment.get(JAVA_INT, 4);
      int patch = segment.get(JAVA_INT, 8);

      String versionNumber = readCString(segment, 12, 32);
      String preRelease = readCString(segment, 44, 48);
      String buildMetadata = readCString(segment, 92, 48);

      return new VersionInfo(major, minor, patch, versionNumber, preRelease, buildMetadata);
    }

    private static String readCString(MemorySegment segment, long offset, int maxLen) {
      byte[] bytes = new byte[maxLen];
      for (int i = 0; i < maxLen; i++) {
        byte b = segment.get(JAVA_BYTE, offset + i);
        if (b == 0) {
          return new String(bytes, 0, i, StandardCharsets.UTF_8);
        }
        bytes[i] = b;
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  /**
   * Creates a new webview instance. If debug is true - developer tools will be enabled (if the
   * platform supports them). Window parameter can be a pointer to the native window handle. If it's
   * non-null - then child WebView is embedded into the given parent window. Otherwise a new window
   * is created. Depending on the platform, a GtkWindow, NSWindow or HWND pointer can be passed
   * here.
   *
   * @param debug Enables developer tools if true (if supported)
   * @param window A pointer to a native window handle, for embedding the webview in a window.
   *     (Either a GtkWindow, NSWindow, or HWND pointer)
   */
  public MemorySegment webview_create(boolean debug, MemorySegment window) {
    try {
      MethodHandle handle =
          downcallHandle("webview_create", FunctionDescriptor.of(ADDRESS, JAVA_BOOLEAN, ADDRESS));
      return (MemorySegment) handle.invoke(debug, window == null ? MemorySegment.NULL : window);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return a native window handle pointer.
   * @param webview The instance pointer of the webview
   * @implNote This is either a pointer to a GtkWindow, NSWindow, or HWND.
   */
  public MemorySegment webview_get_window(MemorySegment webview) {
    try {
      MethodHandle handle =
          downcallHandle("webview_get_window", FunctionDescriptor.of(ADDRESS, ADDRESS));
      return (MemorySegment) handle.invoke(webview);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Load raw HTML content onto the window.
   *
   * @param webview The instance pointer of the webview
   * @param html The raw HTML string.
   */
  public void webview_set_html(MemorySegment webview, String html) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_set_html", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment htmlSegment = tempArena.allocateFrom(html);
      handle.invoke(webview, htmlSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Navigates to the given URL.
   *
   * @param webview The instance pointer of the webview
   * @param url The target url, can be a data uri.
   */
  public void webview_navigate(MemorySegment webview, String url) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_navigate", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment urlSegment = tempArena.allocateFrom(url);
      handle.invoke(webview, urlSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Sets the title of the webview window.
   *
   * @param webview The instance pointer of the webview
   * @param title
   */
  public void webview_set_title(MemorySegment webview, String title) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment titleSegment = tempArena.allocateFrom(title);
      handle.invoke(webview, titleSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Updates the webview's window size, see {@link WV_HINT_NONE}, {@link WV_HINT_MIN}, {@link
   * WV_HINT_MAX}, and {@link WV_HINT_FIXED}
   *
   * @param webview The instance pointer of the webview
   * @param width
   * @param height
   * @param hint
   */
  public void webview_set_size(MemorySegment webview, int width, int height, int hint) {
    try {
      MethodHandle handle =
          downcallHandle(
              "webview_set_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
      handle.invoke(webview, width, height, hint);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Runs the main loop until it's terminated. You must destroy the webview after this method
   * returns.
   *
   * @param webview The instance pointer of the webview
   */
  public void webview_run(MemorySegment webview) {
    try {
      MethodHandle handle = downcallHandle("webview_run", FunctionDescriptor.ofVoid(ADDRESS));
      handle.invoke(webview);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Destroys a webview and closes the native window.
   *
   * @param webview The instance pointer of the webview
   */
  public void webview_destroy(MemorySegment webview) {
    try {
      MethodHandle handle = downcallHandle("webview_destroy", FunctionDescriptor.ofVoid(ADDRESS));
      handle.invoke(webview);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Stops the webview loop, which causes {@link #webview_run(MemorySegment)} to return.
   *
   * @param webview The instance pointer of the webview
   */
  public void webview_terminate(MemorySegment webview) {
    try {
      MethodHandle handle = downcallHandle("webview_terminate", FunctionDescriptor.ofVoid(ADDRESS));
      handle.invoke(webview);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Evaluates arbitrary JavaScript code asynchronously.
   *
   * @param webview The instance pointer of the webview
   * @param js The script to execute
   */
  public void webview_eval(MemorySegment webview, String js) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_eval", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment jsSegment = tempArena.allocateFrom(js);
      handle.invoke(webview, jsSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Injects JavaScript code at the initialization of the new page.
   *
   * @implSpec It is guaranteed to be called before window.onload.
   * @param webview The instance pointer of the webview
   * @param js The script to execute
   */
  public void webview_init(MemorySegment webview, String js) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment jsSegment = tempArena.allocateFrom(js);
      handle.invoke(webview, jsSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Binds a native callback so that it will appear under the given name as a global JavaScript
   * function. Internally it uses webview_init().
   *
   * @param webview The instance pointer of the webview
   * @param name The name of the function to be exposed in Javascript
   * @param callback The callback to be called
   * @param arg Unused
   */
  public void webview_bind(MemorySegment webview, String name, MemorySegment callback, long arg) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle(
              "webview_bind", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
      MemorySegment nameSegment = tempArena.allocateFrom(name);
      handle.invoke(webview, nameSegment, callback, arg);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Remove the native callback specified.
   *
   * @param webview The instance pointer of the webview
   * @param name The name of the callback
   */
  public void webview_unbind(MemorySegment webview, String name) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_unbind", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
      MemorySegment nameSegment = tempArena.allocateFrom(name);
      handle.invoke(webview, nameSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Allows to return a value from the native binding. Original request pointer must be provided to
   * help internal RPC engine match requests with responses.
   *
   * @param webview The instance pointer of the webview
   * @param seq The seq of the callback
   * @param isError Whether or not `result` should be thrown as an exception
   * @param result The result (in json)
   */
  public void webview_return(MemorySegment webview, long seq, boolean isError, String result) {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle(
              "webview_return",
              FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_BOOLEAN, ADDRESS));
      MemorySegment resultSegment = tempArena.allocateFrom(result);
      handle.invoke(webview, seq, isError, resultSegment);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Dispatches the callback on the UI thread, only effective while {@link
   * #webview_run(MemorySegment)} is blocking.
   *
   * @param webview The instance pointer of the webview
   * @param callback The callback to be called
   * @param arg Unused
   */
  public void webview_dispatch(MemorySegment webview, MemorySegment callback, long arg) {
    try {
      MethodHandle handle =
          downcallHandle(
              "webview_dispatch", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
      handle.invoke(webview, callback, arg);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the version info. */
  public VersionInfo webview_version() {
    try (Arena tempArena = Arena.ofConfined()) {
      MethodHandle handle =
          downcallHandle("webview_version", FunctionDescriptor.of(VersionInfo.LAYOUT));
      MemorySegment result = (MemorySegment) handle.invoke(tempArena);
      return VersionInfo.fromMemorySegment(result);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
    return library
        .find(name)
        .map(addr -> Linker.nativeLinker().downcallHandle(addr, descriptor))
        .orElseThrow(() -> new UnsatisfiedLinkError("Unable to find symbol: " + name));
  }
}
