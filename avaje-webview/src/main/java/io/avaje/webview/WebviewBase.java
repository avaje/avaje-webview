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
 * Base for the platform-specific {@link Webview} implementations, which are left with the native
 * layer only. The JS bridge, bindings, user-script tracking, console redirect and dispatch routing
 * all live here.
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
          if (typeof it !== "function") {
            continue;
          }
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
          if (e.button !== 0) {
            return;
          }
          var el = e.target;
          while (el instanceof HTMLElement) {
            var r = window.getComputedStyle(el).getPropertyValue('-webkit-app-region');
            if (r === 'no-drag') {
              return;
            }
            if (r === 'drag') {
              e.preventDefault();
              __avaje_wv_drag__();
              return;
            }
            el = el.parentElement;
          }
        });
      })();
      """;

  /** Reserved message method that carries an {@link #evalResult} result back to Java. */
  private static final String EVAL_RESULT = "__avaje_wv_eval__";

  /**
   * Runs one {@link #evalResult} expression. {@code %1$s} is the quoted request id, {@code %2$s} the
   * expression, placed on its own lines so a trailing line comment can't swallow the closing paren.
   * Marks {@code lastEval} first so {@link #EVALUATE_PARSE_CHECK_JS} can tell it ran.
   */
  private static final String EVALUATE_JS =
      """
      (function() {
        var webview = window.__webview__;
        var id = %1$s;
        webview.lastEval = id;
        function reply(status, value) {
          var message;
          try {
            message = JSON.stringify({
              id: id,
              method: "__avaje_wv_eval__",
              params: [status, value === undefined ? null : value]
            });
          } catch (e) {
            message = JSON.stringify({ id: id, method: "__avaje_wv_eval__", params: [1, String(e)] });
          }
          webview.post(message);
        }
        (async () => (
      %2$s
        ))().then(
          function(value) {
            reply(0, value);
          },
          function(error) {
            reply(1, error instanceof Error ? error.name + ": " + error.message : String(error));
          });
      })();
      """;

  /**
   * Evaluated straight after {@link #EVALUATE_JS}. A syntax error stops that script before it marks
   * {@code lastEval}, and nothing else would ever settle the future, so report the failure here.
   */
  private static final String EVALUATE_PARSE_CHECK_JS =
      """
      (function() {
        var webview = window.__webview__;
        if (webview && webview.lastEval !== %1$s) {
          webview.post(JSON.stringify({
            id: %1$s,
            method: "__avaje_wv_eval__",
            params: [1, "SyntaxError: expression could not be parsed"]
          }));
        }
      })();
      """;

  private final boolean redirectConsole;
  protected final boolean borderless;
  protected final boolean outline;
  protected final boolean transparent;
  protected final MemorySegment parentWindow;
  protected final boolean moveParentWithChild;

  protected WebviewBase(
      boolean redirectConsole,
      boolean borderless,
      boolean outline,
      boolean transparent,
      MemorySegment parentWindow,
      boolean moveParentWithChild) {
    this.redirectConsole = redirectConsole;
    this.borderless = borderless;
    this.outline = outline;
    this.transparent = transparent;
    this.parentWindow = parentWindow != null ? parentWindow : MemorySegment.NULL;
    this.moveParentWithChild = moveParentWithChild;
  }

  // JS bridge state. userScripts and bindScriptIdx are only ever touched on the main thread.
  private final List<String> userScripts = new ArrayList<>();
  private int bindScriptIdx = -1;
  private final Map<String, BindCallback> bindings = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<String>> evaluations = new ConcurrentHashMap<>();

  /** Whether the window should be on screen. */
  protected volatile boolean visibleRequested = true;

  private final List<BooleanSupplier> closeRequestedListeners = new CopyOnWriteArrayList<>();
  private final List<Consumer<WindowSize>> resizeListeners = new CopyOnWriteArrayList<>();
  private final List<Consumer<Boolean>> focusListeners = new CopyOnWriteArrayList<>();
  private final List<Consumer<String>> pageLoadedListeners = new CopyOnWriteArrayList<>();
  private final List<Predicate<String>> navigationChecks = new CopyOnWriteArrayList<>();
  private final List<Consumer<String>> newWindowListeners = new CopyOnWriteArrayList<>();

  private boolean javaNavigationPending;

  // Last values handed to listeners.
  private WindowSize lastSize;
  private Boolean lastFocused;
  private final AtomicLong evaluationIds = new AtomicLong();

  /**
   * Injects the JS bridge init script and sets up console redirection. Call once the user-content
   * manager is configured.
   *
   * @param postFn JS expression that posts messages to Java, e.g. {@code "function(msg){return
   *     window.webkit.messageHandlers.__webview__.postMessage(msg);}"}.
   */
  protected final void setupJsBridge(String postFn) {
    addUserScriptInternal(buildInitScript(postFn));
    bindScriptIdx = userScripts.size();
    final var emptyBind = buildBindScript();
    userScripts.add(emptyBind);
    nativeAddUserScript(emptyBind);
    if (redirectConsole) {
      redirectConsole();
    }
    if (borderless && this instanceof Win32WebView) {
      setupAppRegionDrag();
    }
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
   * Called by the subclass when a message arrives from the page. Picks the {@link BindCallback} the
   * JSON names and runs it.
   */
  protected final void onMessage(String json) {
    final var id = jsonGet(json, "id");
    final var method = jsonGet(json, "method");
    final var params = jsonGet(json, "params");
    if (EVAL_RESULT.equals(method)) {
      completeEvaluation(id, params);
      return;
    }
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
          javaNavigationPending = true;
          setHtmlImpl(html != null ? html : "");
        });
  }

  @Override
  public void navigate(@Nullable String url) {
    dispatchImpl(
        () -> {
          syncBindScript();
          javaNavigationPending = true;
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

  void disableMaximize() {
    dispatchImpl(this::disableMaximizeImpl);
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
  public CompletableFuture<String> evalResult(@NonNull String expression) {
    final var id = "eval-" + evaluationIds.incrementAndGet();
    final var future = new UiThreadFuture<String>(this::onUIThread);
    evaluations.put(id, future);
    final var quotedId = jsonEscape(id);
    dispatchImpl(
        () -> {
          evalImpl(EVALUATE_JS.formatted(quotedId, expression));
          evalImpl(EVALUATE_PARSE_CHECK_JS.formatted(quotedId));
        });
    return future;
  }

  /** Settles the future for an {@link #evalResult} call. */
  private void completeEvaluation(String id, String params) {
    final var future = evaluations.remove(id);
    if (future == null) {
      return;
    }
    final var comma = params.indexOf(',');
    if (comma < 0) {
      future.completeExceptionally(new JavaScriptException("Malformed evalResult reply: " + params));
      return;
    }
    final var value = params.substring(comma + 1, params.length() - 1);
    if (params.charAt(1) == '0') {
      future.complete(value);
    } else {
      future.completeExceptionally(new JavaScriptException(WebviewUtil.jsonUnescape(value)));
    }
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
                  + ")) { window.__webview__.onBind("
                  + jsonEscape(name)
                  + "); }");
        });
  }

  @Override
  public void unbind(@NonNull String name) {
    bindings.remove(name);
    dispatchImpl(
        () -> {
          rebuildBindScript();
          evalImpl(
              "if (window.__webview__) { window.__webview__.onUnbind(" + jsonEscape(name) + "); }");
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
  public abstract Webview unmaximizeWindow();

  @Override
  public abstract boolean isMaximized();

  @Override
  public abstract Webview fullscreen();

  @Override
  public abstract Webview minimizeWindow();

  @Override
  public Webview showWindow() {
    visibleRequested = true;
    dispatchImpl(this::showWindowImpl);
    return this;
  }

  @Override
  public Webview hideWindow() {
    visibleRequested = false;
    dispatchImpl(this::hideWindowImpl);
    return this;
  }

  @Override
  public Webview restoreWindow() {
    dispatchImpl(this::restoreWindowImpl);
    return this;
  }

  @Override
  public boolean isMinimized() {
    return callOnUIThread(this::isMinimizedImpl);
  }

  @Override
  public Webview exitFullscreen() {
    dispatchImpl(this::exitFullscreenImpl);
    return this;
  }

  @Override
  public boolean isFullscreen() {
    return callOnUIThread(this::isFullscreenImpl);
  }

  @Override
  public Webview focusWindow() {
    dispatchImpl(this::focusWindowImpl);
    return this;
  }

  @Override
  public WindowSize getSize() {
    return callOnUIThread(this::getSizeImpl);
  }

  @Override
  public void onCloseRequested(@NonNull BooleanSupplier allowClose) {
    closeRequestedListeners.add(allowClose);
  }

  @Override
  public void onResize(@NonNull Consumer<WindowSize> listener) {
    resizeListeners.add(listener);
  }

  @Override
  public void onFocusChanged(@NonNull Consumer<Boolean> listener) {
    focusListeners.add(listener);
  }

  @Override
  public void onPageLoaded(@NonNull Consumer<String> listener) {
    pageLoadedListeners.add(listener);
  }

  @Override
  public void onNavigate(@NonNull Predicate<String> allow) {
    navigationChecks.add(allow);
  }

  @Override
  public void onNewWindow(@NonNull Consumer<String> listener) {
    newWindowListeners.add(listener);
  }

  /**
   * Asks every {@link #onNavigate} check whether the page or one of its iframes may navigate to
   * {@code url}. Loads started by {@link #navigate} or {@link #setHTML} are always allowed. A throwing
   * check is logged and counts as allowing the navigation.
   *
   * @return {@code false} if any check blocked the navigation
   */
  protected final boolean fireNavigationRequested(String url) {
    if (javaNavigationPending) {
      javaNavigationPending = false;
      return true;
    }
    for (final var check : navigationChecks) {
      try {
        if (!check.test(url)) {
          return false;
        }
      } catch (final Throwable t) {
        log.log(ERROR, "onNavigate check failed", t);
      }
    }
    return true;
  }

  /**
   * Reports a {@code target="_blank"} link or {@code window.open} call. The caller always blocks
   * the in-app popup. With no {@link #onNewWindow} listener, web and mail links open in the
   * default browser instead.
   */
  protected final void fireNewWindowRequested(String url) {
    if (!newWindowListeners.isEmpty()) {
      deliver(newWindowListeners, url);
      return;
    }
    try {
      WebviewUtil.openExternal(new URI(url));
    } catch (final IllegalArgumentException e) {
      log.log(DEBUG, "Ignoring new window for {0}", url);
    } catch (final Throwable t) {
      log.log(WARNING, "Could not open " + url + " in the default browser", t);
    }
  }

  /**
   * Asks every {@link #onCloseRequested} check whether a user-initiated close may go ahead. A
   * throwing check is logged and counts as allowing the close.
   *
   * @return {@code false} if any check vetoed the close
   */
  protected final boolean fireCloseRequested() {
    for (final var check : closeRequestedListeners) {
      try {
        if (!check.getAsBoolean()) {
          return false;
        }
      } catch (final Throwable t) {
        log.log(ERROR, "onCloseRequested check failed", t);
      }
    }
    return true;
  }

  /** Reports the content size to {@link #onResize} listeners when it has changed. */
  protected final void fireResized(int width, int height) {
    final var size = new WindowSize(width, height);
    if (size.equals(lastSize)) {
      return;
    }
    lastSize = size;
    deliver(resizeListeners, size);
  }

  /** Reports focus to {@link #onFocusChanged} listeners when it has changed. */
  protected final void fireFocusChanged(boolean focused) {
    if (lastFocused != null && lastFocused == focused) {
      return;
    }
    lastFocused = focused;
    deliver(focusListeners, focused);
  }

  /** Reports a finished page load to {@link #onPageLoaded} listeners. */
  protected final void firePageLoaded(String url) {
    deliver(pageLoadedListeners, url);
  }

  /** Hands {@code value} to each listener, logging failures so none reach native code. */
  private static <T> void deliver(List<Consumer<T>> listeners, T value) {
    for (final var listener : listeners) {
      try {
        listener.accept(value);
      } catch (final Throwable t) {
        log.log(ERROR, "Window event listener failed", t);
      }
    }
  }

  /**
   * Runs {@code task} on the UI thread and returns its result.
   *
   * @throws IllegalStateException when the UI thread doesn't respond, for example because the
   *     window has closed or {@link #run()} hasn't started yet
   */
  protected final <T> T callOnUIThread(Supplier<T> task) {
    if (onUIThread()) {
      return task.get();
    }
    final var result = new CompletableFuture<T>();
    dispatchImpl(
        () -> {
          try {
            result.complete(task.get());
          } catch (final Throwable t) {
            result.completeExceptionally(t);
          }
        });
    try {
      return result.get(5, TimeUnit.SECONDS);
    } catch (final TimeoutException e) {
      throw new IllegalStateException(
          "The webview UI thread did not respond; is the window closed or run() not started?", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (final ExecutionException e) {
      throw e.getCause() instanceof final RuntimeException re
          ? re
          : new IllegalStateException(e.getCause());
    }
  }

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
   * Resolves a resource {@link URI} to a real filesystem {@link Path}. Resources inside a jar are
   * copied to a temp file, since Win32 {@code LoadImageW} and Cocoa {@code NSImage} both want a
   * file on disk.
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

  protected abstract void disableMaximizeImpl();

  protected abstract void setHtmlImpl(String html);

  protected abstract void startWindowDragImpl();

  protected abstract void showWindowImpl();

  protected abstract void hideWindowImpl();

  protected abstract void restoreWindowImpl();

  protected abstract boolean isMinimizedImpl();

  protected abstract void exitFullscreenImpl();

  protected abstract boolean isFullscreenImpl();

  protected abstract void focusWindowImpl();

  protected abstract WindowSize getSizeImpl();

  protected abstract void evalImpl(String js);

  protected abstract boolean onUIThread();

  /** Schedule {@code r} to run on the UI thread. */
  protected abstract void dispatchImpl(Runnable r);

  /** Add a user script run at document-start, top frame only. */
  protected abstract void nativeAddUserScript(String js);

  /** Remove all previously added user scripts. */
  protected abstract void nativeRemoveAllUserScripts();

  /**
   * Settle the JS promise waiting on a binding call.
   *
   * @param id the request id the call was made with
   * @param status 0 for success, anything else for failure
   * @param result JSON result, or the error message when status is non-zero
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

  /** Route the page's console output to System.Logger. */
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
    // An init script, so the override survives navigation instead of applying to one page.
    addUserScriptInternal(CONSOLE_REDIRECT_JS);
  }

  /** Adapt a WebviewBinding to a BindCallback, turning a thrown exception into a rejection. */
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
   * Builds the JS bridge, injected as a user script at document-start on every page load. It runs
   * in strict mode inside a self-executing function and leaves one global behind, {@code
   * window.__webview__}.
   *
   * <p>Every bound {@code window.xxx()} ends up in {@code call()}, which mints a random id, parks
   * {@code {resolve, reject}} in {@code _promises} under it, posts {@code {id, method, params}} to
   * the native side and hands the caller a Promise. Java runs the {@link WebviewBinding} and comes
   * back through {@link #returnResult}, which evals {@code onReply(id, status, result)} to settle
   * that Promise: resolved on status 0, rejected otherwise. Malformed JSON from Java rejects
   * rather than passing through unnoticed.
   *
   * <p>{@code onBind} and {@code onUnbind} add and remove those {@code window} properties for
   * {@link #bind} / {@link #unbind} calls made after the page has loaded. Bindings registered
   * before the first load arrive instead through {@link #buildBindScript}, a second document-start
   * script injected after this one.
   *
   * @param postFn a JS function expression (not a statement) posting one string message to the
   *     native side, e.g. {@code "function(message){return
   *     window.webkit.messageHandlers.__webview__.postMessage(message);}"}
   * @return the bridge script, ready to inject
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
              var promise = new Promise(function(resolve, reject) {
                _promises[_id] = { resolve, reject };
              });
              this.post(JSON.stringify({ id: _id, method: method, params: _params }));
              return promise;
            };
            Webview_.prototype.onReply = function(id, status, result) {
              var promise = _promises[id];
              if (result !== undefined) {
                try {
                  result = JSON.parse(result);
                } catch (e) {
                  promise.reject(new Error('Failed to parse binding result as JSON'));
                  return;
                }
              }
              if (status === 0) {
                promise.resolve(result);
              } else {
                promise.reject(result);
              }
            };
            Webview_.prototype.onBind = function(name) {
              if (window.hasOwnProperty(name)) {
                throw new Error('Property "' + name + '" already exists');
              }
              window[name] = (function() {
                var params = [name].concat(Array.prototype.slice.call(arguments));
                return Webview_.prototype.call.apply(this, params);
              }).bind(this);
            };
            Webview_.prototype.onUnbind = function(name) {
              if (!window.hasOwnProperty(name)) {
                throw new Error('Property "' + name + '" does not exist');
              }
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
      if (!first) {
        sb.append(", ");
      }
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
    if (ki < 0) {
      return "";
    }
    final var colon = json.indexOf(':', ki + needle.length());
    if (colon < 0) {
      return "";
    }
    var vi = colon + 1;
    while (vi < json.length() && json.charAt(vi) == ' ') {
      vi++;
    }
    if (vi >= json.length()) {
      return "";
    }
    final var first = json.charAt(vi);
    if (first == '"') {
      var end = vi + 1;
      while (end < json.length()) {
        final var c = json.charAt(end);
        if (c == '\\') {
          end += 2;
          continue;
        }
        if (c == '"') {
          break;
        }
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
