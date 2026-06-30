package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM bindings for WebKitGTK 6.0 ({@code libwebkitgtk-6.0}) and its embedded JavaScriptCore
 * ({@code libjavascriptcoregtk-6.0}).
 *
 * <p><b>All calls must happen on the GTK thread.</b> WebKitGTK is not thread-safe; it runs its own
 * web-process via IPC, but the GTK widget API itself must only be touched from the thread that
 * called {@code gtk_init}.
 */
final class WebKit6 {

  /**
   * Injection frame scope: top frame only.
   *
   * <p>Corresponds to {@code WEBKIT_USER_CONTENT_INJECT_TOP_FRAME} (value {@code 1}) in the
   * WebKitGTK C headers. When passed to {@link #WEBKIT_USER_SCRIPT_NEW}, the script runs only in
   * the top-level browsing context, not in {@code <iframe>} sub-frames.
   *
   * <p>We restrict to the top frame because our JS bridge ({@code window.__webview__}) uses {@code
   * window.webkit.messageHandlers.__webview__.postMessage()}, which is only wired on the top-level
   * frame's {@code window}. Running the bridge initialisation in an iframe would give it a
   * different {@code window} object and a different {@code postMessage} target, breaking the
   * binding protocol.
   */
  static final int WEBKIT_USER_CONTENT_INJECT_TOP_FRAME = 1;

  /**
   * Injection time: at document start, before any page scripts.
   *
   * <p>Corresponds to {@code WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START} (value {@code 0}).
   * Scripts injected at this point run before the page's own {@code <script>} tags, ensuring that
   * {@code window.__webview__} exists by the time application code tries to call it. Using {@code
   * AT_DOCUMENT_END} (value {@code 1}) would create a race where early-running page scripts could
   * call bridge functions before they exist.
   */
  static final int WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START = 0;

  /**
   * {@code FunctionDescriptor} for the {@code "script-message-received"} signal.
   *
   * <p>C callback signature: {@code void(*)(WebKitUserContentManager* manager, JSCValue*
   * js_message, gpointer user_data)}
   *
   * <p>Note the signal name suffix {@code "::<handler_name>"} registered via {@link
   * GLib#gSignalConnect}: this scopes delivery so only messages for the {@code __webview__} handler
   * fire our callback, even if other message handlers are registered on the same UCM.
   */
  static final FunctionDescriptor SCRIPT_MESSAGE_RECEIVED_DESC =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /**
   * Lookup for {@code libwebkitgtk-6.0.so.4} (soname {@code .4}, package {@code libwebkitgtk-6.0-4}
   * on Debian/Ubuntu). The GTK4 port of WebKitGTK changed the soname from the GTK3 era's {@code
   * libwebkit2gtk-4.0.so.37}.
   */
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
   * <p>Creates a new {@code WebKitWebView} widget with a private, per-instance {@code
   * WebKitWebViewConfiguration} (separate from any shared process pool). The widget appears as a
   * normal {@code GtkWidget*} to GTK and can be embedded as the content widget of a {@code
   * GtkWindow}.
   *
   * <p><b>Reference:</b> returns a floating {@code GObject} reference. Caller must call {@link
   * GLib#gObjectRefSink} to claim ownership before GTK's container management can inadvertently
   * free it.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_NEW =
      downcall("webkit_web_view_new", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code webkit_web_view_get_user_content_manager(WebKitWebView* wv) ->
   * WebKitUserContentManager*}
   *
   * <p>Returns the {@code WebKitUserContentManager} associated with this web view. This object
   * controls which user scripts and message handlers are active.
   *
   * <p><b>Borrowed reference:</b> do NOT call {@code g_object_ref} or {@code g_object_unref} on the
   * returned pointer. It is owned by the {@code WebKitWebView} and is freed when the view is
   * destroyed.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER =
      downcall("webkit_web_view_get_user_content_manager", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_get_settings(WebKitWebView* wv) -> WebKitSettings*}
   *
   * <p>Returns the {@code WebKitSettings} object for this view, which controls JS clipboard access,
   * developer extras, and many other per-view engine settings.
   *
   * <p><b>Borrowed reference:</b> same semantics as {@link
   * #WEBKIT_WEB_VIEW_GET_USER_CONTENT_MANAGER}. Owned by the web view.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_GET_SETTINGS =
      downcall("webkit_web_view_get_settings", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_load_uri(WebKitWebView* wv, const gchar* uri) -> void}
   *
   * <p>Navigates the web view to the given URI. The URI must be an absolute URI with a scheme (e.g.
   * {@code "https://..."}, {@code "file:///..."}). The navigation is asynchronous; the page is not
   * loaded when this returns.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_LOAD_URI =
      downcall("webkit_web_view_load_uri", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_load_html(WebKitWebView* wv, const gchar* content, const gchar*
   * base_uri) -> void}
   *
   * <p>Loads raw HTML content directly into the web view.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_LOAD_HTML =
      downcall("webkit_web_view_load_html", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_evaluate_javascript(WebKitWebView* wv, const gchar* script, gssize
   * length, const gchar* world_name, const gchar* source_uri, GCancellable* cancellable,
   * GAsyncReadyCallback callback, gpointer user_data) -> void}
   *
   * <p>Evaluates JavaScript in the web view asynchronously. The eight C arguments map as:
   *
   * <ol>
   *   <li>{@code wv} - the web view
   *   <li>{@code script} - null-terminated JS source (ADDRESS)
   *   <li>{@code length} - byte length of {@code script}, or {@code -1} to use {@code strlen()}
   *       (JAVA_LONG = {@code gssize})
   *   <li>{@code world_name} - JS world name; {@code NULL} = default world
   *   <li>{@code source_uri} - displayed in DevTools as the script origin; {@code NULL} = none
   *   <li>{@code cancellable} - {@code GCancellable*}; {@code NULL} = not cancellable
   *   <li>{@code callback} - {@code GAsyncReadyCallback} for the result; {@code NULL} =
   *       fire-and-forget (we don't need JS return values here)
   *   <li>{@code user_data} - passed to {@code callback}; {@code NULL} since callback is NULL
   * </ol>
   *
   * We pass {@code NULL} for the last four arguments because we only use eval for side effects
   * (injecting binding stubs, returning results via {@code window.__webview__.onReply}), not to
   * capture JS return values.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_EVALUATE_JAVASCRIPT =
      downcall(
          "webkit_web_view_evaluate_javascript",
          FunctionDescriptor.ofVoid(
              ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_web_view_get_uri(WebKitWebView* wv) -> const gchar*}
   *
   * <p>Returns the URI of the currently loaded page, or {@code NULL} if no page is loaded yet.
   *
   * <p><b>Owned by the web view:</b> do NOT free the returned pointer. It is valid until the web
   * view navigates to a new URI or is destroyed.
   *
   * <p>We use this before calling {@link #webkitWebViewEvaluateJavascript} to guard against calling
   * eval on a bare (no-page) web view, which would assert {@code WEBKIT_IS_WEB_VIEW} and crash in
   * debug builds.
   */
  static final MethodHandle WEBKIT_WEB_VIEW_GET_URI =
      downcall("webkit_web_view_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code webkit_settings_set_javascript_can_access_clipboard(WebKitSettings* s, gboolean enabled)
   * -> void}
   *
   * <p>Controls whether JS running in the web view can read and write the system clipboard via
   * {@code document.execCommand("copy")} and the Clipboard API. Enabled unconditionally so app code
   * can use clipboard features without extra permission prompts.
   */
  static final MethodHandle WEBKIT_SETTINGS_SET_JS_CLIPBOARD =
      downcall(
          "webkit_settings_set_javascript_can_access_clipboard",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code webkit_settings_set_enable_write_console_messages_to_stdout(WebKitSettings* s, gboolean
   * enabled) -> void}
   *
   * <p>When enabled, WebKit writes {@code console.log()} etc. from the web process to the parent
   * process's stdout. We enable this only in debug mode so console output is visible during
   * development without the overhead in production.
   */
  static final MethodHandle WEBKIT_SETTINGS_SET_CONSOLE_TO_STDOUT =
      downcall(
          "webkit_settings_set_enable_write_console_messages_to_stdout",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code webkit_settings_set_enable_developer_extras(WebKitSettings* s, gboolean enabled) ->
   * void}
   *
   * <p>Enables the WebKit Inspector (right-click -> Inspect Element). Must be true before a page is
   * loaded for the context menu item to appear. We only enable in debug mode.
   */
  static final MethodHandle WEBKIT_SETTINGS_SET_DEV_EXTRAS =
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
   * <p>After this call, posting a message fires the {@code "script-message-received::<name>"}
   * signal on the UCM. We connect our upcall stub to that signal in {@link GtkWebView} during
   * window initialisation.
   *
   * <p>Returns {@code gboolean}: non-zero if registration succeeded (fails only if the name is
   * already registered). We discard the return value.
   */
  static final MethodHandle WEBKIT_UCM_REGISTER_HANDLER =
      downcall(
          "webkit_user_content_manager_register_script_message_handler",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_content_manager_add_script(WebKitUserContentManager* ucm, WebKitUserScript*
   * script) -> void}
   *
   * <p>Adds a user script to the content manager. The script will run on every page load according
   * to the injection time and frame scope set when the script was created.
   *
   * <p>The UCM retains its own reference to the script, so the caller may call {@link
   * #webkitUserScriptUnref} immediately after this call. The script remains active until {@link
   * #webkitUcmRemoveAllScripts} is called or the UCM is destroyed.
   */
  static final MethodHandle WEBKIT_UCM_ADD_SCRIPT =
      downcall(
          "webkit_user_content_manager_add_script", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_content_manager_remove_all_scripts(WebKitUserContentManager* ucm) -> void}
   *
   * <p>Removes all user scripts from the UCM. Used when the set of JS bindings changes (a new
   * {@code bind()} or {@code unbind()} call) so we can atomically replace the entire binding script
   * with the updated version. Removing and re-adding is simpler than WebKit's script identity API,
   * which requires tracking individual script objects.
   */
  static final MethodHandle WEBKIT_UCM_REMOVE_ALL_SCRIPTS =
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
   *   <li>{@code source} - the JavaScript source string
   *   <li>{@code frames} - {@link #WEBKIT_USER_CONTENT_INJECT_TOP_FRAME} = top frame only
   *   <li>{@code time} - {@link #WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START} = before page JS
   *   <li>{@code allow_list} - {@code const gchar**} array of URI patterns; {@code NULL} = all
   *       origins are allowed
   *   <li>{@code block_list} - {@code const gchar**} array of URI patterns to exclude; {@code NULL}
   *       = no exclusions
   * </ul>
   *
   * <p>We pass {@code NULL} for both list args (the last two {@code ADDRESS} params) to apply the
   * script to every page unconditionally.
   *
   * <p>Returns a new reference; caller must call {@link #webkitUserScriptUnref} when done (after
   * handing it to the UCM).
   */
  static final MethodHandle WEBKIT_USER_SCRIPT_NEW =
      downcall(
          "webkit_user_script_new",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

  /**
   * {@code webkit_user_script_unref(WebKitUserScript* script) -> void}
   *
   * <p>Decrements the reference count of a {@code WebKitUserScript}. Call this after {@link
   * #webkitUcmAddScript} because the UCM has taken its own reference - our caller copy is no longer
   * needed. Failing to unref leaks a small heap allocation per script.
   */
  static final MethodHandle WEBKIT_USER_SCRIPT_UNREF =
      downcall("webkit_user_script_unref", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code jsc_value_to_string(JSCValue* value) -> gchar*}
   *
   * <p>Converts a JavaScriptCore value to a UTF-8 {@code gchar*} string. The JS postMessage call
   * passes our bridge JSON as a {@code JSCValue}; this extracts the actual string so Java can parse
   * it.
   *
   * <p><b>Caller owns the string:</b> the returned {@code gchar*} is a fresh GLib allocation. The
   * caller <em>must</em> call {@link GLib#gFree} on it after copying to Java - see {@link
   * #jscValueToString}.
   */
  static final MethodHandle JSC_VALUE_TO_STRING =
      downcall("jsc_value_to_string", FunctionDescriptor.of(ADDRESS, ADDRESS));

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
   * <p>Do NOT ref or unref the returned pointer - it is owned by {@code wv}.
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
   * <p>Do NOT ref or unref the returned pointer - it is owned by {@code wv}.
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
   * <p>All trailing optional arguments ({@code world_name}, {@code source_uri}, {@code
   * cancellable}, {@code callback}, {@code user_data}) are passed as {@code NULL} because we do not
   * need the JS return value.
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
          // world_name=NULL -> default JS world (same as page scripts)
          MemorySegment.NULL,
          // source_uri=NULL -> no DevTools origin label needed
          MemorySegment.NULL,
          // cancellable=NULL -> not cancellable
          MemorySegment.NULL,
          // callback=NULL -> fire-and-forget; we don't need the return value
          MemorySegment.NULL,
          // user_data=NULL -> no data to pass to callback
          MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the URI of the currently loaded page.
   *
   * <p>Returns a borrowed pointer owned by the web view - do NOT free it. Returns a zero-address
   * segment if no page has been loaded yet, which we use as a guard before calling {@link
   * #webkitWebViewEvaluateJavascript}.
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
    // gboolean = C int; pass 1/0
    try {
      WEBKIT_SETTINGS_SET_JS_CLIPBOARD.invokeExact(settings, enable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables forwarding of web-process console messages to stdout.
   *
   * <p>Useful during development; should be disabled in production to avoid leaking internal log
   * output.
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
   * <p>The return value (gboolean success) is discarded - failure only occurs if the name is
   * already registered, which would be a programming error caught in development.
   *
   * @param manager a borrowed {@code WebKitUserContentManager*}
   * @param name null-terminated handler name (we use {@code "__webview__"})
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
              // allow_list=NULL -> apply to all origins
              MemorySegment.NULL,
              // block_list=NULL -> do not exclude any origin
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
   * <p>{@code jsc_value_to_string} returns a fresh {@code gchar*} allocated by GLib's allocator.
   * Two steps are required to read it safely in Panama:
   *
   * <ol>
   *   <li>{@code raw.reinterpret(Long.MAX_VALUE)} - Panama creates {@link MemorySegment} objects
   *       with a declared byte size for bounds-checking. Because this memory was allocated by
   *       native code, Panama has no size metadata for it and sets the size to 0, which would make
   *       {@code getString(0)} throw immediately. Calling {@code reinterpret(Long.MAX_VALUE)}
   *       grants read access to up to {@code Long.MAX_VALUE} bytes from the base address, allowing
   *       {@code getString} to scan forward for the null terminator.
   *   <li>{@link GLib#gFree} - must be called with the <em>original</em> (pre-reinterpret) pointer
   *       to return the memory to GLib's allocator. Using Java's GC would bypass {@code g_free} and
   *       corrupt the GLib heap.
   * </ol>
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
    // reinterpret is required: Panama has no size metadata for natively-allocated strings,
    // so the default segment size is 0 and getString would fail with an out-of-bounds error.
    final var s = raw.reinterpret(Long.MAX_VALUE).getString(0);
    // Must free with g_free, not the JVM GC - this pointer came from GLib's allocator.
    GLib.gFree(raw);
    return s;
  }

  private WebKit6() {}
}
