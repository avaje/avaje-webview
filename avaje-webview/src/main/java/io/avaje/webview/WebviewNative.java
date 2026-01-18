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

  private static final SymbolLookup LIBRARY;
  private static final Linker LINKER = Linker.nativeLinker();

  // Memory layouts for webview_version structures
  private static final StructLayout WEBVIEW_VERSION_T_LAYOUT =
      MemoryLayout.structLayout(
          JAVA_INT.withName("major"), JAVA_INT.withName("minor"), JAVA_INT.withName("patch"));

  private static final StructLayout WEBVIEW_VERSION_INFO_T_LAYOUT =
      MemoryLayout.structLayout(
          WEBVIEW_VERSION_T_LAYOUT.withName("version"),
          MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("version_number"),
          MemoryLayout.sequenceLayout(48, JAVA_BYTE).withName("pre_release"),
          MemoryLayout.sequenceLayout(48, JAVA_BYTE).withName("build_metadata"));

  // Cached method handles
  private static final MethodHandle webview_version;
  private static final MethodHandle webview_create;
  private static final MethodHandle webview_get_window;
  private static final MethodHandle webview_set_html;
  private static final MethodHandle webview_navigate;
  private static final MethodHandle webview_set_title;
  private static final MethodHandle webview_set_size;
  private static final MethodHandle webview_run;
  private static final MethodHandle webview_destroy;
  private static final MethodHandle webview_terminate;
  private static final MethodHandle webview_eval;
  private static final MethodHandle webview_init;
  private static final MethodHandle webview_bind;
  private static final MethodHandle webview_unbind;
  private static final MethodHandle webview_return;
  private static final MethodHandle webview_dispatch;

  static {

    LIBRARY = SymbolLookup.libraryLookup(System.mapLibraryName("webview"), Arena.global());

    // Initialize all method handles
    webview_version = downcallHandle("webview_version", FunctionDescriptor.of(ADDRESS));
    webview_create =
        downcallHandle("webview_create", FunctionDescriptor.of(ADDRESS, JAVA_BOOLEAN, ADDRESS));
    webview_get_window =
        downcallHandle("webview_get_window", FunctionDescriptor.of(ADDRESS, ADDRESS));
    webview_set_html =
        downcallHandle("webview_set_html", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_navigate =
        downcallHandle("webview_navigate", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_set_title =
        downcallHandle("webview_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_set_size =
        downcallHandle(
            "webview_set_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    webview_run = downcallHandle("webview_run", FunctionDescriptor.ofVoid(ADDRESS));
    webview_destroy = downcallHandle("webview_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    webview_terminate = downcallHandle("webview_terminate", FunctionDescriptor.ofVoid(ADDRESS));
    webview_eval = downcallHandle("webview_eval", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_init = downcallHandle("webview_init", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_bind =
        downcallHandle(
            "webview_bind", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    webview_unbind = downcallHandle("webview_unbind", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    webview_return =
        downcallHandle(
            "webview_return", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_BOOLEAN, ADDRESS));
    webview_dispatch =
        downcallHandle("webview_dispatch", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
  }

  WebviewNative() {}

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
      return (MemorySegment)
          webview_create.invoke(debug, window == null ? MemorySegment.NULL : window);
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
      return (MemorySegment) webview_get_window.invoke(webview);
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
    try (var arena = Arena.ofConfined()) {
      webview_set_html.invoke(webview, arena.allocateFrom(html));
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
    try (var arena = Arena.ofConfined()) {
      webview_navigate.invoke(webview, arena.allocateFrom(url));
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
    try (var arena = Arena.ofConfined()) {
      webview_set_title.invoke(webview, arena.allocateFrom(title));
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
      webview_set_size.invoke(webview, width, height, hint);
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
      webview_run.invoke(webview);
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
      webview_destroy.invoke(webview);
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
      webview_terminate.invoke(webview);
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
    try (var arena = Arena.ofConfined()) {
      webview_eval.invoke(webview, arena.allocateFrom(js));
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
    try (var arena = Arena.ofConfined()) {
      webview_init.invoke(webview, arena.allocateFrom(js));
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
    try (var arena = Arena.ofConfined()) {
      webview_bind.invoke(webview, arena.allocateFrom(name), callback, arg);
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
    try (var arena = Arena.ofConfined()) {
      webview_unbind.invoke(webview, arena.allocateFrom(name));
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
    try (var arena = Arena.ofConfined()) {
      webview_return.invoke(webview, seq, isError, arena.allocateFrom(result));
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
      webview_dispatch.invoke(webview, callback, arg);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /** Get the library's version information. */
  public String webview_version() {
    try {
      MemorySegment result = (MemorySegment) webview_version.invoke();

      // Reinterpret the returned pointer with the struct layout
      MemorySegment versionInfo = result.reinterpret(WEBVIEW_VERSION_INFO_T_LAYOUT.byteSize());

      // Extract version_number string
      long versionNumberOffset =
          WEBVIEW_VERSION_INFO_T_LAYOUT.byteOffset(
              MemoryLayout.PathElement.groupElement("version_number"));
      return readCString(versionInfo, versionNumberOffset, 32);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to get webview version", e);
    }
  }

  /** Helper method to read a null-terminated C string from memory */
  private static String readCString(MemorySegment segment, long offset, int maxLen) {
    byte[] bytes = new byte[maxLen];
    int len = 0;

    for (int i = 0; i < maxLen; i++) {
      byte b = segment.get(JAVA_BYTE, offset + i);
      if (b == 0) break;
      bytes[len++] = b;
    }

    return new String(bytes, 0, len);
  }

  private static MethodHandle downcallHandle(String name, FunctionDescriptor descriptor) {
    return LIBRARY
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, descriptor))
        .orElseThrow(() -> new UnsatisfiedLinkError("Unable to find symbol: " + name));
  }
}
