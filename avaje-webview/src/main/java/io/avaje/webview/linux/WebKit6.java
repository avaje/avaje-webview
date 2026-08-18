package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings for WebKitGTK 6.0 ({@code libwebkitgtk-6.0}) and its embedded JavaScriptCore ({@code
 * libjavascriptcoregtk-6.0}).
 *
 * <p>Everything here belongs on the GTK thread. WebKitGTK runs the page in its own process over
 * IPC, but the widget API in this process is as thread-hostile as the rest of GTK.
 */
final class WebKit6 {

  /**
   * {@code WEBKIT_USER_CONTENT_INJECT_TOP_FRAME}. Passed to {@link #WEBKIT_USER_SCRIPT_NEW}, it
   * keeps the script to the top-level browsing context and out of {@code <iframe>} sub-frames.
   */
  static final int WEBKIT_USER_CONTENT_INJECT_TOP_FRAME = 1;

  /**
   * {@code WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START}, which runs the script before the page's
   * own {@code <script>} tags, so {@code window.__webview__} is there when app code looks for it.
   */
  static final int WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START = 0;

  /**
   * {@code FunctionDescriptor} for the {@code "script-message-received"} signal.
   *
   * <p>C callback signature: {@code void(*)(WebKitUserContentManager* manager, JSCValue*
   * js_message, gpointer user_data)}
   *
   * <p>The {@code "::<handler_name>"} suffix used with {@link GLib#gSignalConnect} narrows
   * delivery to the {@code __webview__} handler, leaving any other handler on the same UCM alone.
   */
  static final FunctionDescriptor SCRIPT_MESSAGE_RECEIVED_DESC =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

  /** {@code WebKitLoadEvent} value for a completed page load. */
  static final int WEBKIT_LOAD_FINISHED = 3;

  /**
   * {@code FunctionDescriptor} for the {@code "load-changed"} signal.
   *
   * <p>C signature: {@code void(*)(WebKitWebView* web_view, WebKitLoadEvent load_event, gpointer
   * user_data)}. {@code WebKitLoadEvent} is a GEnum (int).
   */
  static final FunctionDescriptor LOAD_CHANGED_DESC =
      FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /** Lookup for {@code libwebkitgtk-6.0.so.4} */
  private static final SymbolLookup WEBKIT_LIB =
      SymbolLookup.libraryLookup("libwebkitgtk-6.0.so.4", Arena.global());

  /** Lookup for {@code libjavascriptcoregtk-6.0.so.1} */
  private static final SymbolLookup JSC_LIB =
      SymbolLookup.libraryLookup("libjavascriptcoregtk-6.0.so.1", Arena.global());

  /** Combined lookup: try WebKit first, then JSC. */
  private static final SymbolLookup LOOKUP = WEBKIT_LIB.or(JSC_LIB);

  /**
   * {@code webkit_web_view_new() -> WebKitWebView*}
   *
   * <p>Creates a {@code WebKitWebView} with its own {@code WebKitWebViewConfiguration}, outside
   * any shared process pool. To GTK it is an ordinary {@code GtkWidget*} and can go straight in as
   * a {@code GtkWindow}'s content widget.
   *
   * <p>The reference comes back floating, so {@link GLib#gObjectRefSink} has to claim it before
   * GTK's container handling can free the view out from under the caller.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_NEW =
      downcall("webkit_web_view_new", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code webkit_web_view_get_user_content_manager(WebKitWebView* wv) ->
   * WebKitUserContentManager*}
   *
   * <p>Returns the view's {@code WebKitUserContentManager}, which holds the active user scripts
   * and message handlers.
   *
   * <p>A borrowed reference, so no {@code g_object_ref} or {@code g_object_unref} on it. The web
   * view owns it and frees it on destruction.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER =
      downcall("webkit_web_view_get_user_content_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_get_settings(WebKitWebView* wv) -> WebKitSettings*}
   *
   * <p>Returns the view's {@code WebKitSettings}, covering JS clipboard access, developer extras
   * and the rest of the per-view engine settings.
   *
   * <p>Borrowed and owned by the web view, same as {@link
   * #WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER}.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_GET_SETTINGS =
      downcall("webkit_web_view_get_settings", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_load_uri(WebKitWebView* wv, const gchar* uri) -> void}
   *
   * <p>Navigates the web view to an absolute URI with a scheme, such as {@code "https://..."} or
   * {@code "file:///..."}. Asynchronous, so nothing is loaded yet when this returns.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_LOAD_URI =
      downcall("webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_load_html(WebKitWebView* wv, const gchar* content, const gchar*
   * base_uri) -> void}
   *
   * <p>Loads raw HTML content directly into the web view.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_LOAD_HTML =
      downcall("webkit_web_view_load_html", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_evaluate_javascript(WebKitWebView* wv, const gchar* script, gssize
   * length, const gchar* world_name, const gchar* source_uri, GCancellable* cancellable,
   * GAsyncReadyCallback callback, gpointer user_data) -> void}
   *
   * <p>Evaluates JavaScript in the web view asynchronously. The eight C arguments map as:
   *
   * <ol>
   *   <li>{@code wv}: the web view
   *   <li>{@code script}: null-terminated JS source (ADDRESS)
   *   <li>{@code length}: byte length of {@code script}, or {@code -1} to use {@code strlen()}
   *       (JAVA_LONG = {@code gssize})
   *   <li>{@code world_name}: JS world name; {@code NULL} = default world
   *   <li>{@code source_uri}: displayed in DevTools as the script origin; {@code NULL} = none
   *   <li>{@code cancellable}: {@code GCancellable*}; {@code NULL} = not cancellable
   *   <li>{@code callback}: {@code GAsyncReadyCallback} for the result; {@code NULL} =
   *       fire-and-forget
   *   <li>{@code user_data}: passed to {@code callback}; {@code NULL} since callback is NULL
   * </ol>
   *
   * <p>The last four are always {@code NULL}. Eval is only used for side effects here, injecting
   * binding stubs and delivering results through {@code window.__webview__.onReply}, never to read
   * a JS return value.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT =
      downcall(
          "webkit_web_view_evaluate_javascript",
          FunctionDescriptor.ofVoid(
              ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_get_uri(WebKitWebView* wv) -> const gchar*}
   *
   * <p>Returns the URI of the currently loaded page, or {@code NULL} if no page is loaded yet.
   *
   * <p>Owned by the web view, so the pointer must not be freed, and it lasts only until the next
   * navigation or destruction.
   *
   * <p>Checked before {@link #webkitWebViewEvaluateJavascript}, since eval on a view with no page
   * loaded trips the {@code WEBKIT_IS_WEB_VIEW} assert and crashes debug builds.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_GET_URI =
      downcall("webkit_web_view_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_settings_set_javascript_can_access_clipboard(WebKitSettings* s, gboolean enabled)
   * -> void}
   *
   * <p>Controls whether JS running in the web view can read and write the system clipboard via
   * {@code document.execCommand("copy")} and the Clipboard API.
   */
  private static final MethodHandle WEBKIT_SETTINGS_SET_JS_CLIPBOARD =
      downcall(
          "webkit_settings_set_javascript_can_access_clipboard",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code webkit_settings_set_enable_write_console_messages_to_stdout(WebKitSettings* s, gboolean
   * enabled) -> void}
   *
   * <p>When enabled, WebKit writes {@code console.log()} etc. from the web process to the parent
   * process's stdout.
   */
  private static final MethodHandle WEBKIT_SETTINGS_SET_CONSOLE_TO_STDOUT =
      downcall(
          "webkit_settings_set_enable_write_console_messages_to_stdout",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code webkit_settings_set_enable_developer_extras(WebKitSettings* s, gboolean enabled) ->
   * void}
   *
   * <p>Enables the WebKit Inspector, reached through right-click then Inspect Element. Has to be
   * set before a page loads or the menu item never appears.
   */
  private static final MethodHandle WEBKIT_SETTINGS_SET_DEV_EXTRAS =
      downcall(
          "webkit_settings_set_enable_developer_extras",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code webkit_user_content_manager_register_script_message_handler( WebKitUserContentManager*
   * ucm, const gchar* name, const gchar* world_name) -> gboolean}
   *
   * <p>Registers a named JS message handler so that JavaScript inside the web view can post
   * messages to Java by calling: {@code window.webkit.messageHandlers.<name>.postMessage(data)}
   *
   * <p>Once registered, posting a message fires {@code "script-message-received::<name>"} on the
   * UCM, which {@link GtkWebView} connects its upcall stub to during window setup.
   *
   * <p>The {@code gboolean} return is discarded; it only goes false when the name is already
   * registered.
   */
  private static final MethodHandle WEBKIT_UCM_REGISTER_HANDLER =
      downcall(
          "webkit_user_content_manager_register_script_message_handler",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_content_manager_add_script(WebKitUserContentManager* ucm, WebKitUserScript*
   * script) -> void}
   *
   * <p>Adds a user script to the content manager. It runs on every page load, at the injection
   * time and frame scope it was created with.
   *
   * <p>The UCM takes its own reference, so {@link #webkitUserScriptUnref} can follow immediately.
   * The script stays active until {@link #webkitUcmRemoveAllScripts} or the UCM goes away.
   */
  private static final MethodHandle WEBKIT_UCM_ADD_SCRIPT =
      downcall(
          "webkit_user_content_manager_add_script", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_content_manager_remove_all_scripts(WebKitUserContentManager* ucm) -> void}
   *
   * <p>Removes every user script from the UCM. Each {@code bind()} or {@code unbind()} replaces
   * the whole set this way, which avoids tracking individual script objects through WebKit's script
   * identity API.
   */
  private static final MethodHandle WEBKIT_UCM_REMOVE_ALL_SCRIPTS =
      downcall(
          "webkit_user_content_manager_remove_all_scripts", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code webkit_user_script_new(const gchar* source, WebKitUserContentInjectedFrames frames,
   * WebKitUserScriptInjectionTime time, const gchar* const* allow_list, const gchar* const*
   * block_list) -> WebKitUserScript*}
   *
   * <p>Creates a new {@code WebKitUserScript} object.
   *
   * <ul>
   *   <li>{@code source}: the JavaScript source string
   *   <li>{@code frames}: {@link #WEBKIT_USER_CONTENT_INJECT_TOP_FRAME} = top frame only
   *   <li>{@code time}: {@link #WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START} = before page JS
   *   <li>{@code allow_list}: {@code const gchar**} array of URI patterns; {@code NULL} = all
   *       origins are allowed
   *   <li>{@code block_list}: {@code const gchar**} array of URI patterns to exclude; {@code NULL}
   *       = no exclusions
   * </ul>
   *
   * <p>Both list arguments are {@code NULL}, so the script applies to every page.
   *
   * <p>Returns a new reference, released with {@link #webkitUserScriptUnref} once the UCM has it.
   */
  private static final MethodHandle WEBKIT_USER_SCRIPT_NEW =
      downcall(
          "webkit_user_script_new",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_script_unref(WebKitUserScript* script) -> void}
   *
   * <p>Decrements the reference count of a {@code WebKitUserScript}. Call this after {@link
   * #webkitUcmAddScript} because the UCM has taken its own reference, so the caller copy is no
   * longer needed. Failing to unref leaks a small heap allocation per script.
   */
  private static final MethodHandle WEBKIT_USER_SCRIPT_UNREF =
      downcall("webkit_user_script_unref", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code jsc_value_to_string(JSCValue* value) -> gchar*}
   *
   * <p>Converts a JavaScriptCore value to a UTF-8 {@code gchar*}. postMessage hands the bridge
   * JSON over as a {@code JSCValue}, and this pulls the string back out for Java to parse.
   *
   * <p>The {@code gchar*} is a fresh GLib allocation owned by the caller, so {@link GLib#gFree}
   * has to follow the copy into Java. See {@link #jscValueToString}.
   */
  private static final MethodHandle JSC_VALUE_TO_STRING =
      downcall("jsc_value_to_string", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_set_background_color(WebKitWebView* wv, const GdkRGBA* rgba) -> void}
   *
   * <p>Sets the base colour the web view paints under page content. At {@code alpha=0} WebKit
   * stops painting its opaque white base, which is what lets the GTK window behind show through.
   *
   * <p>{@code GdkRGBA} is four {@code float}s (red, green, blue, alpha), 16 bytes with no padding,
   * passed by pointer.
   */
  private static final MethodHandle WEBKIT_WEB_VIEW_SET_BACKGROUND_COLOR =
      downcall("webkit_web_view_set_background_color", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(sym)
            .orElseThrow(() -> new UnsatisfiedLinkError("WebKit6 symbol not found: " + sym)),
        desc);
  }

  /**
   * Creates a new {@code WebKitWebView} GTK widget.
   *
   * @return a {@code WebKitWebView*} cast to {@link MemorySegment}; starts with a floating GObject
   *     reference that must be sunk with {@link GLib#gObjectRefSink}
   */
  static MemorySegment webkitWebViewNew() {
    try {
      return (MemorySegment) WEBKIT_WEB_VIEW_NEW.invokeExact();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the borrowed {@code WebKitUserContentManager} owned by the given web view.
   *
   * <p>Owned by {@code wv}, so do NOT ref or unref the returned pointer.
   *
   * @param wv a {@code WebKitWebView*}
   * @return a borrowed {@code WebKitUserContentManager*}
   */
  static MemorySegment webkitWebViewGetUserContentManager(MemorySegment wv) {
    try {
      return (MemorySegment) WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER.invokeExact(wv);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the borrowed {@code WebKitSettings} owned by the given web view.
   *
   * <p>Owned by {@code wv}, so do NOT ref or unref the returned pointer.
   *
   * @param wv a {@code WebKitWebView*}
   * @return a borrowed {@code WebKitSettings*}
   */
  static MemorySegment webkitWebViewGetSettings(MemorySegment wv) {
    try {
      return (MemorySegment) WEBKIT_WEB_VIEW_GET_SETTINGS.invokeExact(wv);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Navigates the web view to the given URI.
   *
   * @param wv a {@code WebKitWebView*}
   * @param uri a null-terminated absolute URI (e.g. {@code "https://example.com"})
   */
  static void webkitWebViewLoadUri(MemorySegment wv, MemorySegment uri) {
    try {
      WEBKIT_WEB_VIEW_LOAD_URI.invokeExact(wv, uri);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Loads raw HTML into the web view.
   *
   * @param wv a {@code WebKitWebView*}
   * @param html null-terminated UTF-8 HTML source
   * @param baseUri base URI for relative resource loads; {@code NULL} blocks external resources
   */
  static void webkitWebViewLoadHtml(MemorySegment wv, MemorySegment html, MemorySegment baseUri) {
    try {
      WEBKIT_WEB_VIEW_LOAD_HTML.invokeExact(wv, html, baseUri);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Evaluates JavaScript in the web view asynchronously (fire-and-forget).
   *
   * <p>The trailing optional arguments ({@code world_name}, {@code source_uri}, {@code
   * cancellable}, {@code callback}, {@code user_data}) are all {@code NULL}, as nothing here reads
   * a JS return value.
   *
   * @param wv a {@code WebKitWebView*}
   * @param js null-terminated JavaScript source
   * @param length byte length of {@code js}, or {@code -1} to infer from null terminator
   */
  static void webkitWebViewEvaluateJavascript(MemorySegment wv, MemorySegment js, long length) {
    try {
      WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT.invokeExact(
          wv,
          js,
          length,
          // world_name: default JS world, the one page scripts run in
          MemorySegment.NULL,
          // source_uri: no DevTools origin label
          MemorySegment.NULL,
          // cancellable
          MemorySegment.NULL,
          // callback: fire-and-forget
          MemorySegment.NULL,
          // user_data
          MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the URI of the currently loaded page.
   *
   * <p>Borrowed from the web view, so do NOT free it. A zero-address segment means no page has
   * loaded yet, which is the guard used before {@link #webkitWebViewEvaluateJavascript}.
   *
   * @param wv a {@code WebKitWebView*}
   * @return a borrowed {@code const gchar*}, or a zero-address segment if no page is loaded
   */
  static MemorySegment webkitWebViewGetUri(MemorySegment wv) {
    try {
      return (MemorySegment) WEBKIT_WEB_VIEW_GET_URI.invokeExact(wv);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables JS clipboard access for this web view's settings.
   *
   * @param settings a borrowed {@code WebKitSettings*} from {@link #webkitWebViewGetSettings}
   * @param enable {@code true} to allow JS clipboard read/write
   */
  static void webkitSettingsSetJsClipboard(MemorySegment settings, boolean enable) {
    try {
      WEBKIT_SETTINGS_SET_JS_CLIPBOARD.invokeExact(settings, enable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables forwarding of web-process console messages to stdout.
   *
   * <p>Handy during development, best left off in production so internal log output stays out of
   * stdout.
   *
   * @param settings a borrowed {@code WebKitSettings*}
   * @param enable {@code true} to forward console messages
   */
  static void webkitSettingsSetConsoleToStdout(MemorySegment settings, boolean enable) {
    try {
      WEBKIT_SETTINGS_SET_CONSOLE_TO_STDOUT.invokeExact(settings, enable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables the WebKit Inspector (DevTools).
   *
   * <p>Must be enabled before a page is loaded for the right-click context menu item to appear.
   *
   * @param settings a borrowed {@code WebKitSettings*}
   * @param enable {@code true} to show the Inspector menu item
   */
  static void webkitSettingsSetDevExtras(MemorySegment settings, boolean enable) {
    try {
      WEBKIT_SETTINGS_SET_DEV_EXTRAS.invokeExact(settings, enable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Registers a named message handler so JS can post messages to Java.
   *
   * <p>After this call, {@code window.webkit.messageHandlers.<name>.postMessage(data)} from JS
   * fires the {@code "script-message-received::<name>"} GObject signal on the UCM.
   *
   * <p>The {@code gboolean} success return is discarded; it only goes false on a name that is
   * already registered, which would be a bug caught long before release.
   *
   * @param manager a borrowed {@code WebKitUserContentManager*}
   * @param name null-terminated handler name, {@code "__webview__"} here
   */
  static void webkitUcmRegisterHandler(MemorySegment manager, MemorySegment name) {
    try {
      final var _ =
          (int) WEBKIT_UCM_REGISTER_HANDLER.invokeExact(manager, name, MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Adds a user script to the content manager.
   *
   * <p>The UCM takes its own reference, so the caller may immediately call {@link
   * #webkitUserScriptUnref} on the script object.
   *
   * @param manager a borrowed {@code WebKitUserContentManager*}
   * @param script a {@code WebKitUserScript*} from {@link #webkitUserScriptNew}
   */
  static void webkitUcmAddScript(MemorySegment manager, MemorySegment script) {
    try {
      WEBKIT_UCM_ADD_SCRIPT.invokeExact(manager, script);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Removes all user scripts from the content manager.
   *
   * <p>Used before rebuilding the binding script so the entire set is replaced atomically.
   *
   * @param manager a borrowed {@code WebKitUserContentManager*}
   */
  static void webkitUcmRemoveAllScripts(MemorySegment manager) {
    try {
      WEBKIT_UCM_REMOVE_ALL_SCRIPTS.invokeExact(manager);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Creates a new user script that runs in the top frame at document start.
   *
   * <p>Returns a new reference. After passing the script to {@link #webkitUcmAddScript}, call
   * {@link #webkitUserScriptUnref} to release the caller's copy (the UCM holds its own).
   *
   * @param source null-terminated JavaScript source
   * @param injectedFrames frame scope constant ({@link #WEBKIT_USER_CONTENT_INJECT_TOP_FRAME})
   * @param injectionTime timing constant ({@link #WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START})
   * @return a {@code WebKitUserScript*} with refcount 1
   */
  static MemorySegment webkitUserScriptNew(
      MemorySegment source, int injectedFrames, int injectionTime) {
    try {
      return (MemorySegment)
          WEBKIT_USER_SCRIPT_NEW.invokeExact(
              source,
              injectedFrames,
              injectionTime,
              // allow_list: every origin
              MemorySegment.NULL,
              // block_list: no exclusions
              MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Releases the caller's reference to a user script.
   *
   * <p>Call this immediately after {@link #webkitUcmAddScript} since the UCM now holds its own
   * reference. Failing to call this leaks the script object.
   *
   * @param script a {@code WebKitUserScript*} returned by {@link #webkitUserScriptNew}
   */
  static void webkitUserScriptUnref(MemorySegment script) {
    try {
      WEBKIT_USER_SCRIPT_UNREF.invokeExact(script);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Converts a JavaScriptCore value to a Java {@code String} and frees the native buffer.
   *
   * @param jscValue a {@code JSCValue*} received in the {@code script-message-received} callback
   * @return the string value as a Java {@code String}
   */
  static String jscValueToString(MemorySegment jscValue) {
    MemorySegment raw;
    try {
      raw = (MemorySegment) JSC_VALUE_TO_STRING.invokeExact(jscValue);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    // FFM carries no size for a natively allocated string, hence the reinterpret.
    final var s = raw.reinterpret(Long.MAX_VALUE).getString(0);
    // Allocated by GLib, so g_free is the only correct way to release it.
    GLib.gFree(raw);
    return s;
  }

  /**
   * Sets the base color painted beneath page content, e.g. {@code (0,0,0,0)} for fully transparent
   * so a transparent GTK window shows through wherever the page itself doesn't paint.
   *
   * @param wv a {@code WebKitWebView*}
   * @param red red channel, {@code 0.0}-{@code 1.0}
   * @param green green channel, {@code 0.0}-{@code 1.0}
   * @param blue blue channel, {@code 0.0}-{@code 1.0}
   * @param alpha alpha channel, from {@code 0.0} (transparent) to {@code 1.0} (opaque)
   */
  static void webkitWebViewSetBackgroundColor(
      MemorySegment wv, float red, float green, float blue, float alpha) {
    try (var a = Arena.ofConfined()) {
      final var rgba = a.allocate(4 * JAVA_FLOAT.byteSize());
      rgba.set(JAVA_FLOAT, 0, red);
      rgba.set(JAVA_FLOAT, 4, green);
      rgba.set(JAVA_FLOAT, 8, blue);
      rgba.set(JAVA_FLOAT, 12, alpha);
      WEBKIT_WEB_VIEW_SET_BACKGROUND_COLOR.invokeExact(wv, rgba);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private WebKit6() {}
}
