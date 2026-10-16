import io.avaje.webview.Webview;

String HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    body {
      font-family: system-ui, sans-serif;
      padding: 24px;
    }

    button {
      margin: 0 6px 6px 0;
      padding: 6px 12px;
    }

    textarea {
      width: 100%;
      height: 60px;
    }

    #log {
      background: #f4f4f8;
      height: 180px;
      overflow-y: auto;
      padding: 8px;
      font-family: monospace;
      font-size: 12px;
      white-space: pre-wrap;
    }
  </style>
</head>
<body>
  <h1>Window events and controls</h1>

  <p>Type below, then try closing the window: Java vetoes the close and asks first.</p>
  <textarea id="notes" oninput="setDirty(true)" placeholder="Unsaved changes..."></textarea>

  <div>
    <button onclick="windowAction('hide')">Hide for 2s</button>
    <button onclick="windowAction('minimize')">Minimize for 2s</button>
    <button onclick="windowAction('fullscreen')">Fullscreen for 2s</button>
    <button onclick="windowAction('where')">Window state</button>
  </div>

  <div id="log"></div>

  <script>
    function log(text) {
      const line = new Date().toLocaleTimeString() + '  ' + text + '\\n';
      document.getElementById('log').textContent = line + document.getElementById('log').textContent;
    }

    function askToClose() {
      if (confirm('You have unsaved notes. Close anyway?')) {
        confirmClose();
      }
    }
  </script>
</body>
</html>
""";

volatile boolean dirty;

void main() {
  Webview webview = Webview.builder()
      .title("Window Events Example")
      .width(720)
      .height(560)
      .html(HTML)
      .build();

  webview.onPageLoaded(url -> log(webview, "page loaded: " + url));
  webview.onResize(size -> log(webview, "resized to " + size.width() + "x" + size.height()));
  webview.onFocusChanged(focused -> log(webview, focused ? "focused" : "lost focus"));

  // Runs on the UI thread when the user tries to close: veto it and let the page ask instead.
  webview.onCloseRequested(() -> {
    if (!dirty) {
      return true;
    }
    webview.eval("askToClose()");
    return false;
  });

  webview.bind("setDirty", _ -> {
    dirty = true;
    return "null";
  });
  webview.bind("confirmClose", _ -> {
    webview.close(); // a programmatic close is never vetoed
    return "null";
  });

  webview.bind("windowAction", args -> {
    switch (args) {
      case "[\"hide\"]" -> {
        webview.hideWindow();
        later(webview, () -> webview.showWindow());
      }
      case "[\"minimize\"]" -> {
        webview.minimizeWindow();
        later(webview, () -> webview.restoreWindow());
      }
      case "[\"fullscreen\"]" -> {
        webview.fullscreen();
        later(webview, () -> webview.exitFullscreen());
      }
      case "[\"where\"]" -> log(webview, "size " + webview.getSize()
          + ", minimized " + webview.isMinimized()
          + ", fullscreen " + webview.isFullscreen());
      default -> log(webview, "unknown action " + args);
    }
    return "null";
  });

  webview.run();
}

/** Runs {@code action} two seconds from now, off the UI thread so the window keeps responding. */
void later(Webview webview, Runnable action) {
  Thread.startVirtualThread(() -> {
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      return;
    }
    action.run();
    log(webview, "restored");
  });
}

void log(Webview webview, String text) {
  System.out.println(text);
  webview.eval("log('" + text.replace("\\", "\\\\").replace("'", "\\'") + "')");
}
