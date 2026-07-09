package io.avaje.webview;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;

import module java.base;

import module org.jspecify;

import io.avaje.webview.linux.GtkWebView;
import io.avaje.webview.macos.CocoaWebView;
import io.avaje.webview.windows.Win32WebView;

/**
 * Abstract base for platform-specific {@link Webview} implementations.
 *
 * <p>Holds all shared logic: JS bridge injection, binding management, user-script tracking,
 * console-redirect, and thread-safe dispatch routing. Subclasses implement the native layer.
 */
public abstract sealed class WebviewBase implements Webview
    permits GtkWebView, CocoaWebView, Win32WebView {

  @FunctionalInterface
  interface BindCallback {
    void onCall(String id, String req);
  }

  private static final System.Logger log = System.getLogger("io.avaje.webview");

  private static final String CONSOLE_REDIRECT_JS =
      """
      (function() {
        const original = { ...console };
        function log(name, ...parameters) {
          __$io_avaje_webview$log__(name, ...parameters);
          original[name](...parameters);
        }
        for (const [name, it] of Object.entries(console)) {
          if (typeof it !== "function") continue;
          console[name] = (...parameters) => log(name, ...parameters);
        }
        window.addEventListener("error", event => {
          console.error(event.error);
          return false;
        });
        window.addEventListener("unhandledrejection", event => {
          console.error(event.reason);
          return false;
        });
      })();
      """;

  private static final String APP_REGION_DRAG_JS =
      """
      (function() {
        'use strict';
        document.addEventListener('mousedown', function(e) {
          if (e.button !== 0) return;
          var el = e.target;
          while (el instanceof HTMLElement) {
            var r = window.getComputedStyle(el).getPropertyValue('-webkit-app-region');
            if (r === 'no-drag') return;
            if (r === 'drag') { e.preventDefault(); __avaje_wv_drag__(); return; }
            el = el.parentElement;
          }
        });
      })();
      """;

  private final boolean redirectConsole;
  protected final boolean borderless;
  protected final MemorySegment parentWindow;

  protected WebviewBase(boolean redirectConsole, boolean borderless, MemorySegment parentWindow) {
    this.redirectConsole = redirectConsole;
    this.borderless = borderless;
    this.parentWindow = parentWindow != null ? parentWindow : MemorySegment.NULL;
  }

  // JS bridge state, all mutations to userScripts/bindScriptIdx must happen on the main thread
  private final List<String> userScripts = new ArrayList<>();
  private int bindScriptIdx = -1;
  private final Map<String, BindCallback> bindings = new ConcurrentHashMap<>();

  /**
   * Injects the JS bridge init script and sets up console redirection. Must be called after the
   * user-content manager is configured.
   *
   * @param postFn JS expression for posting messages to Java, e.g. {@code "function(msg){return
   *     window.webkit.messageHandlers.__webview__.postMessage(msg);}"}.
   */
  protected final void setupJsBridge(String postFn) {
    addUserScriptInternal(buildInitScript(postFn));
    bindScriptIdx = userScripts.size();
    final var emptyBind = buildBindScript();
    userScripts.add(emptyBind);
    nativeAddUserScript(emptyBind);
    if (redirectConsole) redirectConsole();
    if (borderless && this instanceof Win32WebView) setupAppRegionDrag();
  }

  private void setupAppRegionDrag() {
    bind(
        "__avaje_wv_drag__",
        _ -> {
          startWindowDragImpl();
          return "null";
        });
    addUserScriptInternal(APP_REGION_DRAG_JS);
  }

  /**
   * Called by subclass when a {@code script-message-received} event arrives from WebKit. Parses the
   * JSON message and dispatches to the matching {@link BindCallback}.
   */
  protected final void onMessage(String json) {
    final var id = jsonGet(json, "id");
    final var method = jsonGet(json, "method");
    final var params = jsonGet(json, "params");
    final var cb = bindings.get(method);
    if (cb != null) {
      dispatchImpl(() -> cb.onCall(id, params));
    }
  }

  @Override
  public void setHTML(@Nullable String html) {
    dispatchImpl(
        () -> {
          syncBindScript();
          setHtmlImpl(html != null ? html : "");
        });
  }

  @Override
  public void navigate(@Nullable String url) {
    dispatchImpl(
        () -> {
          syncBindScript();
          navigateImpl(url == null ? "about:blank" : url);
        });
  }

  @Override
  public void setTitle(@NonNull String title) {
    dispatchImpl(() -> setTitleImpl(title));
  }

  @Override
  public void setMinSize(int width, int height) {
    dispatchImpl(() -> setMinSizeImpl(width, height));
  }

  @Override
  public void setMaxSize(int width, int height) {
    dispatchImpl(() -> setMaxSizeImpl(width, height));
  }

  @Override
  public void setSize(int width, int height) {
    dispatchImpl(() -> setSizeImpl(width, height));
  }

  @Override
  public void setFixedSize(int width, int height) {
    dispatchImpl(() -> setFixedSizeImpl(width, height));
  }

  @Override
  public void setInitScript(@NonNull String script) {
    setInitScript(script, false);
  }

  @Override
  public void setInitScript(@NonNull String script, boolean allowNestedAccess) {
    final var wrapped = wrapInitScript(script, allowNestedAccess);
    dispatchImpl(() -> addUserScriptInternal(wrapped));
  }

  @Override
  public void eval(@NonNull String script) {
    dispatchImpl(() -> evalImpl(wrapEval(script)));
  }

  @Override
  public void bind(@NonNull String name, @NonNull WebviewBinding handler) {
    bindings.put(name, adapt(handler));
    dispatchImpl(
        () -> {
          rebuildBindScript();
          evalImpl(
              "if (window.__webview__ && !window.hasOwnProperty("
                  + jsonEscape(name)
                  + ")) window.__webview__.onBind("
                  + jsonEscape(name)
                  + ")");
        });
  }

  @Override
  public void unbind(@NonNull String name) {
    bindings.remove(name);
    dispatchImpl(
        () -> {
          rebuildBindScript();
          evalImpl("if (window.__webview__) window.__webview__.onUnbind(" + jsonEscape(name) + ")");
        });
  }

  @Override
  public void dispatch(@NonNull Runnable handler) {
    dispatchImpl(handler);
  }

  @Override
  public abstract MemorySegment nativeWindowPointer();

  @Override
  public abstract void run();

  @Override
  public abstract void close();

  @Override
  public abstract void setDarkAppearance(boolean shouldAppearDark);

  @Override
  public abstract Webview maximizeWindow();

  @Override
  public abstract Webview fullscreen();

  @Override
  public abstract Webview minimizeWindow();

  @Override
  public void startWindowDrag() {
    dispatchImpl(this::startWindowDragImpl);
  }

  @Override
  public abstract void setIcon(Path path);

  @Override
  public void setIcon(URI uri) {
    try {
      setIcon(resolveIconPath(uri));
    } catch (final Exception e) {
      log.log(WARNING, "setIcon failed for URI {0}: {1}", uri, e.getMessage());
    }
  }

  /**
   * Resolves a resource {@link URI} to a real filesystem {@link Path}, copying it to a temp file
   * when the resource lives inside a jar since native icon loading (Win32 {@code LoadImageW}, Cocoa
   * {@code NSImage}) require a file on disk.
   */
  protected static Path resolveIconPath(URI uri) throws IOException {
    if ("file".equals(uri.getScheme())) {
      return Path.of(uri);
    }

    final var uriStr = uri.toString();
    final var slash = uriStr.lastIndexOf('/');
    final var fileName = slash >= 0 ? uriStr.substring(slash + 1) : uriStr;
    final var dot = fileName.lastIndexOf('.');
    final var suffix = dot >= 0 ? fileName.substring(dot) : "";

    final var tmp = Files.createTempFile("webview-icon-", suffix);
    tmp.toFile().deleteOnExit();
    try (var in = uri.toURL().openStream()) {
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    }
    return tmp;
  }

  protected abstract void navigateImpl(String url);

  protected abstract void setTitleImpl(String title);

  protected abstract void setSizeImpl(int width, int height);

  protected abstract void setMinSizeImpl(int width, int height);

  protected abstract void setMaxSizeImpl(int width, int height);

  protected abstract void setFixedSizeImpl(int width, int height);

  protected abstract void setHtmlImpl(String html);

  protected abstract void startWindowDragImpl();

  protected abstract void evalImpl(String js);

  /** Schedule {@code r} to run on the UI thread. */
  protected abstract void dispatchImpl(Runnable r);

  /** Add a WebKit user script executed at document-start, top frame only. */
  protected abstract void nativeAddUserScript(String js);

  /** Remove all previously added user scripts. */
  protected abstract void nativeRemoveAllUserScripts();

  /**
   * Return result to JS caller
   *
   * @param id requestId
   * @param status status
   * @param result result
   */
  void returnResult(String id, int status, String result) {
    final var escaped = result == null || result.isEmpty() ? "undefined" : jsonEscape(result);
    final var js =
        "window.__webview__.onReply(" + jsonEscape(id) + ", " + status + ", " + escaped + ")";
    dispatchImpl(() -> evalImpl(js));
  }

  private void syncBindScript() {
    userScripts.set(bindScriptIdx, buildBindScript());
    rebuildAllUserScripts();
  }

  // User-script tracking
  private void addUserScriptInternal(String js) {
    userScripts.add(js);
    nativeAddUserScript(js);
  }

  private void rebuildBindScript() {
    final var newScript = buildBindScript();
    if (bindScriptIdx >= 0) {
      userScripts.set(bindScriptIdx, newScript);
    } else {
      bindScriptIdx = userScripts.size();
      userScripts.add(newScript);
    }
    rebuildAllUserScripts();
  }

  private void rebuildAllUserScripts() {
    nativeRemoveAllUserScripts();
    for (final String s : userScripts) nativeAddUserScript(s);
  }

  /** Bind logging redirect to System.Logger */
  private void redirectConsole() {
    bind(
        "__$io_avaje_webview$log__",
        json -> {
          final var comma = json.indexOf(",");
          if (comma == -1 || json.charAt(0) != '[') {
            log.log(ERROR, "[Webview] " + json);
            return "\"ok\"";
          }
          final var function = json.substring(2, comma - 1);
          final var contents = json.substring(comma + 1, json.length() - 1);
          final var message = "[Webview | console." + function + "] " + contents;
          switch (function) {
            case "log", "info" -> log.log(INFO, message);
            case "warn" -> log.log(WARNING, message);
            case "error" -> log.log(ERROR, message);
            case "debug" -> log.log(DEBUG, message);
            default -> log.log(TRACE, "[unknown console function] " + message);
          }
          return "\"ok\"";
        });
    // Inject as init script so the console override runs on every page (not just the current one)
    addUserScriptInternal(CONSOLE_REDIRECT_JS);
  }

  /** Adapt a WebviewBinding to a Bind Callback */
  private BindCallback adapt(WebviewBinding wb) {
    return (id, req) -> {
      try {
        final var result = wb.apply(WebviewUtil.forceSafeChars(req));
        returnResult(id, 0, result == null ? "null" : WebviewUtil.forceSafeChars(result));
      } catch (final Throwable e) {
        final var stack = WebviewUtil.getExceptionStack(e);
        log.log(ERROR, stack);
        returnResult(id, 1, "\"" + WebviewUtil.jsonEscape(stack) + "\"");
      }
    };
  }

  private static String wrapInitScript(String script, boolean allowNestedAccess) {
    return String.format(
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
  }

  private static String wrapEval(String script) {
    return String.format(
        """
        try {
        %s
        } catch (e) {
        console.error('[Webview]', 'An error occurred whilst evaluating script:', %s, e);
        }""",
        script, '"' + WebviewUtil.jsonEscape(script) + '"');
  }

  /**
   * Builds the minified JavaScript bridge that is injected as a user script at document-start on
   * every page load.
   *
   * <p>The script runs in a self-executing function ({@code (function(){...})()}) in strict mode so
   * it does not leak any variables into the global scope. It installs exactly one global: {@code
   * window.__webview__} — an instance of the private {@code Webview_} class.
   *
   * <p><b>ID generation ({@code generateId})</b><br>
   * Each Java binding call needs a unique correlation ID so that when Java calls back with {@code
   * onReply(id, status, result)}, the bridge can locate and settle the right Promise. {@code
   * window.crypto.getRandomValues} produces 16 cryptographically random bytes formatted as a
   * 32-character lowercase hex string. {@code window.msCrypto} is the IE11 fallback (kept for
   * completeness; unreachable on modern WebView2/WebKit).
   *
   * <p><b>Promise map ({@code _promises})</b><br>
   * A plain object that maps {@code id → {resolve, reject}}. Entries are written by {@code call()}
   * immediately before the message is posted, and deleted implicitly when {@code onReply()} settles
   * the Promise and the GC reclaims the entry. No manual cleanup is needed because a Promise can
   * only be settled once.
   *
   * <p><b>{@code Webview_.prototype.post}</b><br>
   * Thin wrapper around the platform-specific {@code postFn} parameter, which is a JS function
   * expression that serializes a message and hands it to the native layer. On Linux/macOS it calls
   * {@code window.webkit.messageHandlers.__webview__.postMessage(msg)}; on Windows it calls the
   * WebView2 equivalent. Injected at build time so the bridge has no platform branching at runtime.
   *
   * <p><b>{@code Webview_.prototype.call}</b><br>
   * The core RPC method. Called indirectly by every bound {@code window.xxx()} function:
   *
   * <ol>
   *   <li>Generates a unique {@code _id}.
   *   <li>Collects all arguments after the method name into {@code _params} ({@code
   *       Array.prototype.slice} so it works with the {@code arguments} object).
   *   <li>Creates a Promise and stores {@code {resolve, reject}} in {@code _promises[_id]}.
   *   <li>Posts {@code {id, method, params}} as JSON to the native side.
   *   <li>Returns the Promise — the caller {@code await}s it.
   * </ol>
   *
   * Java receives the JSON, invokes the bound {@link WebviewBinding}, and calls back via {@link
   * #returnResult}, which evals {@code window.__webview__.onReply(id, status, result)}.
   *
   * <p><b>{@code Webview_.prototype.onReply}</b><br>
   * Called by Java (via {@code eval}) when a binding completes. Looks up the pending Promise by
   * {@code id}, JSON-parses the result string (Java always sends valid JSON), and either resolves
   * or rejects. {@code status === 0} = success; anything else = the result is an error message. If
   * Java returns malformed JSON the Promise rejects with a descriptive error rather than silently
   * swallowing it.
   *
   * <p><b>{@code Webview_.prototype.onBind} / {@code onUnbind}</b><br>
   * Called by Java when {@link #bind} / {@link #unbind} are invoked at runtime (after the page has
   * loaded). {@code onBind} installs a new property on {@code window} whose value is a closure that
   * prepends the method name to its arguments and delegates to {@code call()}. The {@code
   * bind(this)} at the end ensures {@code this} inside the closure refers to the {@code Webview_}
   * instance (not the global object). {@code onUnbind} removes the property; both guard against
   * double-bind/double-unbind with an explicit check.
   *
   * <p><b>Injection timing</b><br>
   * This script runs at document-start (before any page scripts) so that {@code window.__webview__}
   * exists by the time the page's own {@code <script>} tags execute. Bindings registered before the
   * first page load are installed via a second user script ({@link #buildBindScript}) that also
   * runs at document-start, after this one.
   *
   * @param postFn a JS function expression (not a statement) that posts a single string message to
   *     the native side, e.g. {@code "function(message){return
   *     window.webkit.messageHandlers.__webview__.postMessage(message);}"}
   * @return the complete, minified bridge script ready for injection as a user script
   */
  private static String buildInitScript(String postFn) {
    return String.format(
        """
        (function() {
          'use strict';
          function generateId() {
            var crypto = window.crypto || window.msCrypto;
            var bytes = new Uint8Array(16);
            crypto.getRandomValues(bytes);
            return Array.prototype.slice.call(bytes).map(function(n) {
              var s = n.toString(16);
              return ((s.length %% 2) == 1 ? '0' : '') + s;
            }).join('');
          }
          var Webview = (function() {
            var _promises = {};
            function Webview_() {}
            Webview_.prototype.post = function(message) {
              return (%s)(message);
            };
            Webview_.prototype.call = function(method) {
              var _id = generateId();
              var _params = Array.prototype.slice.call(arguments, 1);
              var promise = new Promise(function(resolve, reject) { _promises[_id] = { resolve, reject }; });
              this.post(JSON.stringify({ id: _id, method: method, params: _params }));
              return promise;
            };
            Webview_.prototype.onReply = function(id, status, result) {
              var promise = _promises[id];
              if (result !== undefined) {
                try { result = JSON.parse(result); }
                catch (e) { promise.reject(new Error('Failed to parse binding result as JSON')); return; }
              }
              if (status === 0) { promise.resolve(result); } else { promise.reject(result); }
            };
            Webview_.prototype.onBind = function(name) {
              if (window.hasOwnProperty(name)) throw new Error('Property "' + name + '" already exists');
              window[name] = (function() {
                var params = [name].concat(Array.prototype.slice.call(arguments));
                return Webview_.prototype.call.apply(this, params);
              }).bind(this);
            };
            Webview_.prototype.onUnbind = function(name) {
              if (!window.hasOwnProperty(name)) throw new Error('Property "' + name + '" does not exist');
              delete window[name];
            };
            return Webview_;
          })();
          window.__webview__ = new Webview();
        })()
        """,
        postFn);
  }

  private String buildBindScript() {
    final var sb = new StringBuilder("(function() {'use strict';  var methods = [");
    var first = true;
    for (final String name : bindings.keySet()) {
      if (!first) sb.append(", ");
      sb.append(jsonEscape(name));
      first = false;
    }
    sb.append("];\n  methods.forEach(function(n) { window.__webview__.onBind(n); });\n})()");
    return sb.toString();
  }

  static String jsonEscape(String s) {
    return "\"" + WebviewUtil.jsonEscape(s) + "\"";
  }

  static String jsonGet(String json, String key) {
    final var needle = "\"" + key + "\"";
    final var ki = json.indexOf(needle);
    if (ki < 0) return "";
    final var colon = json.indexOf(':', ki + needle.length());
    if (colon < 0) return "";
    var vi = colon + 1;
    while (vi < json.length() && json.charAt(vi) == ' ') vi++;
    if (vi >= json.length()) return "";
    final var first = json.charAt(vi);
    if (first == '"') {
      var end = vi + 1;
      while (end < json.length()) {
        final var c = json.charAt(end);
        if (c == '\\') {
          end += 2;
          continue;
        }
        if (c == '"') break;
        end++;
      }
      return json.substring(vi + 1, end);
    }
    if (first == '[' || first == '{') {
      final var close = first == '[' ? ']' : '}';
      int depth = 1, i = vi + 1;
      var inStr = false;
      while (i < json.length() && depth > 0) {
        final var c = json.charAt(i);
        if (!inStr && c == '"') {
          inStr = true;
        } else if (inStr && c == '\\') {
          i++;
        } else if (inStr && c == '"') {
          inStr = false;
        } else if (c == first) {
          depth++;
        } else if (c == close) {
          depth--;
        }
        i++;
      }
      return json.substring(vi, i);
    }
    var end = vi;
    while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
    return json.substring(vi, end).trim();
  }
}
