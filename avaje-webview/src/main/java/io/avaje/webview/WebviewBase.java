package io.avaje.webview;

import module java.base;
import module org.jspecify;

import static java.lang.System.Logger.Level.*;

/**
 * Abstract base for platform-specific {@link Webview} implementations.
 *
 * <p>Holds all shared logic: JS bridge injection, binding management, user-script tracking,
 * console-redirect, and thread-safe dispatch routing. Subclasses implement the native layer.
 */
public abstract class WebviewBase implements Webview {

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

  // JS bridge state — all mutations to userScripts/bindScriptIdx happen on the GTK thread
  private final List<String> userScripts = new ArrayList<>();
  private int bindScriptIdx = -1;
  private final Map<String, BindCallback> bindings = new ConcurrentHashMap<>();

  // -------------------------------------------------------------------------
  // Called by subclass once native webview is initialised
  // -------------------------------------------------------------------------

  /**
   * Injects the JS bridge init script and sets up console redirection. Must be called on the UI/GTK
   * thread after the user-content manager is configured.
   *
   * @param postFn JS expression for posting messages to Java, e.g. {@code "function(msg){return
   *     window.webkit.messageHandlers.__webview__.postMessage(msg);}"}.
   */
  protected final void setupJsBridge(String postFn) {
    addUserScriptInternal(buildInitScript(postFn));
    redirectConsole();
  }

  /**
   * Called by subclass when a {@code script-message-received} event arrives from WebKit. Parses the
   * JSON message and dispatches to the matching {@link BindCallback}.
   */
  protected final void onMessage(String json) {
    String id = jsonGet(json, "id");
    String method = jsonGet(json, "method");
    String params = jsonGet(json, "params");
    BindCallback cb = bindings.get(method);
    if (cb != null) {
      dispatchImpl(() -> cb.onCall(id, params));
    }
  }

  // -------------------------------------------------------------------------
  // Webview interface — concrete implementations
  // -------------------------------------------------------------------------

  @Override
  public void setHTML(@Nullable String html) {
    dispatchImpl(() -> setHtmlImpl(html != null ? html : ""));
  }

  @Override
  public void navigate(@Nullable String url) {
    dispatchImpl(() -> navigateImpl(url == null ? "about:blank" : url));
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
    String wrapped = wrapInitScript(script, allowNestedAccess);
    dispatchImpl(() -> addUserScriptInternal(wrapped));
  }

  @Override
  public void eval(@NonNull String script) {
    dispatchImpl(() -> evalImpl(wrapEval(script)));
  }

  @Override
  public void bind(@NonNull String name, @NonNull WebviewBinding handler) {
    bindings.put(name, adapt(name, handler));
    dispatchImpl(
        () -> {
          rebuildBindScript();
          evalImpl("if(window.__webview__)window.__webview__.onBind(" + jsonEscape(name) + ")");
        });
  }

  @Override
  public void unbind(@NonNull String name) {
    bindings.remove(name);
    dispatchImpl(
        () -> {
          rebuildBindScript();
          evalImpl("if(window.__webview__)window.__webview__.onUnbind(" + jsonEscape(name) + ")");
        });
  }

  @Override
  public void dispatch(@NonNull Runnable handler) {
    dispatchImpl(handler);
  }

  // -------------------------------------------------------------------------
  // Webview interface — left abstract for platform subclasses
  // -------------------------------------------------------------------------

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
  public abstract void setIcon(Path path);

  @Override
  public abstract void setIcon(URI uri);

  // -------------------------------------------------------------------------
  // Platform-specific abstracts (called on the UI/GTK thread)
  // -------------------------------------------------------------------------

  protected abstract void navigateImpl(String url);

  protected abstract void setTitleImpl(String title);

  protected abstract void setSizeImpl(int width, int height);

  protected abstract void setMinSizeImpl(int width, int height);

  protected abstract void setMaxSizeImpl(int width, int height);

  protected abstract void setFixedSizeImpl(int width, int height);

  protected abstract void setHtmlImpl(String html);

  protected abstract void evalImpl(String js);

  /** Schedule {@code r} to run on the UI/GTK thread. */
  protected abstract void dispatchImpl(Runnable r);

  /** Add a WebKit user script executed at document-start, top frame only. */
  protected abstract void nativeAddUserScript(String js);

  /** Remove all previously added user scripts. */
  protected abstract void nativeRemoveAllUserScripts();

  // -------------------------------------------------------------------------
  // Return result to JS caller
  // -------------------------------------------------------------------------

  void returnResult(String id, int status, String result) {
    String escaped = (result == null || result.isEmpty()) ? "undefined" : jsonEscape(result);
    String js = "window.__webview__.onReply(" + jsonEscape(id) + "," + status + "," + escaped + ")";
    dispatchImpl(() -> evalImpl(js));
  }

  // -------------------------------------------------------------------------
  // User-script tracking
  // -------------------------------------------------------------------------

  private void addUserScriptInternal(String js) {
    userScripts.add(js);
    nativeAddUserScript(js);
  }

  private void rebuildBindScript() {
    String newScript = buildBindScript();
    nativeRemoveAllUserScripts();
    if (bindScriptIdx >= 0) {
      userScripts.set(bindScriptIdx, newScript);
    } else {
      bindScriptIdx = userScripts.size();
      userScripts.add(newScript);
    }
    for (String s : userScripts) nativeAddUserScript(s);
  }

  // -------------------------------------------------------------------------
  // Console redirect
  // -------------------------------------------------------------------------

  private void redirectConsole() {
    bind(
        "__$io_avaje_webview$log__",
        json -> {
          int comma = json.indexOf(",");
          if (comma == -1 || json.charAt(0) != '[') {
            log.log(ERROR, "[Webview] " + json);
            return "\"ok\"";
          }
          String function = json.substring(2, comma - 1);
          String contents = json.substring(comma + 1, json.length() - 1);
          String message = "[Webview | console." + function + "] " + contents;
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

  // -------------------------------------------------------------------------
  // WebviewBinding → BindCallback adapter
  // -------------------------------------------------------------------------

  private BindCallback adapt(String name, WebviewBinding wb) {
    return (id, req) -> {
      try {
        String result = wb.apply(WebviewUtil.forceSafeChars(req));
        returnResult(id, 0, result == null ? "null" : WebviewUtil.forceSafeChars(result));
      } catch (Throwable e) {
        String stack = WebviewUtil.getExceptionStack(e);
        log.log(ERROR, stack);
        returnResult(id, 1, "\"" + WebviewUtil.jsonEscape(stack) + "\"");
      }
    };
  }

  // -------------------------------------------------------------------------
  // JS script wrappers
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // JS bridge script (ported from webview engine_base.hh)
  // -------------------------------------------------------------------------

  private static String buildInitScript(String postFn) {
    return "(function(){'use strict';"
        + "function generateId(){"
        + "var crypto=window.crypto||window.msCrypto;"
        + "var bytes=new Uint8Array(16);"
        + "crypto.getRandomValues(bytes);"
        + "return Array.prototype.slice.call(bytes).map(function(n){"
        + "var s=n.toString(16);"
        + "return((s.length%2)==1?'0':'')+s;"
        + "}).join('');"
        + "}"
        + "var Webview=(function(){"
        + "var _promises={};"
        + "function Webview_(){}"
        + "Webview_.prototype.post=function(message){return("
        + postFn
        + ")(message);};"
        + "Webview_.prototype.call=function(method){"
        + "var _id=generateId();"
        + "var _params=Array.prototype.slice.call(arguments,1);"
        + "var promise=new Promise(function(resolve,reject){_promises[_id]={resolve,reject};});"
        + "this.post(JSON.stringify({id:_id,method:method,params:_params}));"
        + "return promise;"
        + "};"
        + "Webview_.prototype.onReply=function(id,status,result){"
        + "var promise=_promises[id];"
        + "if(result!==undefined){"
        + "try{result=JSON.parse(result);}"
        + "catch(e){promise.reject(new Error('Failed to parse binding result as JSON'));return;}"
        + "}"
        + "if(status===0){promise.resolve(result);}else{promise.reject(result);}"
        + "};"
        + "Webview_.prototype.onBind=function(name){"
        + "if(window.hasOwnProperty(name))throw new Error('Property \"'+name+'\" already exists');"
        + "window[name]=(function(){"
        + "var params=[name].concat(Array.prototype.slice.call(arguments));"
        + "return Webview_.prototype.call.apply(this,params);"
        + "}).bind(this);"
        + "};"
        + "Webview_.prototype.onUnbind=function(name){"
        + "if(!window.hasOwnProperty(name))throw new Error('Property \"'+name+'\" does not exist');"
        + "delete window[name];"
        + "};"
        + "return Webview_;"
        + "})();"
        + "window.__webview__=new Webview();"
        + "})()";
  }

  private String buildBindScript() {
    var sb = new StringBuilder("(function(){'use strict';var methods=[");
    boolean first = true;
    for (String name : bindings.keySet()) {
      if (!first) sb.append(",");
      sb.append(jsonEscape(name));
      first = false;
    }
    sb.append("];methods.forEach(function(n){window.__webview__.onBind(n);});})()");
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Minimal JSON utilities
  // -------------------------------------------------------------------------

  static String jsonEscape(String s) {
    return "\"" + WebviewUtil.jsonEscape(s) + "\"";
  }

  static String jsonGet(String json, String key) {
    String needle = "\"" + key + "\"";
    int ki = json.indexOf(needle);
    if (ki < 0) return "";
    int colon = json.indexOf(':', ki + needle.length());
    if (colon < 0) return "";
    int vi = colon + 1;
    while (vi < json.length() && json.charAt(vi) == ' ') vi++;
    if (vi >= json.length()) return "";
    char first = json.charAt(vi);
    if (first == '"') {
      int end = vi + 1;
      while (end < json.length()) {
        char c = json.charAt(end);
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
      char close = first == '[' ? ']' : '}';
      int depth = 1, i = vi + 1;
      boolean inStr = false;
      while (i < json.length() && depth > 0) {
        char c = json.charAt(i);
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
    int end = vi;
    while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
    return json.substring(vi, end).trim();
  }
}
