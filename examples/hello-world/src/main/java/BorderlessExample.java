import io.avaje.webview.Webview;

String HTML =
"""
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Borderless</title>
  <style>
  body {
      margin: 0;
      font-family: system-ui, sans-serif;
      border: 1px solid #444;
      height: 100vh;
      box-sizing: border-box;
  }

  .titlebar {
      height: 36px;
      background: #667eea;
      color: white;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 8px;
      user-select: none;
  }

  .titlebar-btn {
      cursor: pointer;
      padding: 4px 10px;
  }

  .titlebar-btn:hover {
      background: rgba(255, 255, 255, 0.2);
  }

  .content {
      padding: 20px;
  }
  </style>
</head>
<body>
  <div class="titlebar" id="titlebar">
    <span>Borderless Window</span>
    <div>
      <span class="titlebar-btn" onclick="minimizeWindow()">−</span>
      <span class="titlebar-btn" onclick="closeWindow()">✕</span>
    </div>
  </div>
  <div class="content">
    <p>This window has no native title bar or border.</p>
    <p>Drag the purple bar above to move the window.</p>
  </div>

  <script>
    // Native decorations are gone (see .borderless(true) below), so dragging the
    // colored bar has to start a native window-move ourselves via a bound function.
    document.getElementById('titlebar').addEventListener('mousedown', event => {
      if (event.button !== 0 || event.target.closest('.titlebar-btn')) return;
      startDrag();
    });

    function minimizeWindow() {
      minimizeApp();
    }

    function closeWindow() {
      closeApp();
    }
  </script>
</body>
</html>
""";

void main() {
  Webview webview =
      Webview.builder()
          .title("Borderless")
          .width(500)
          .height(300)
          .borderless(true)
          .html(HTML)
          .build();

  webview.bind(
      "startDrag",
      req -> {
        webview.startWindowDrag();
        return null;
      });

  webview.bind(
      "minimizeApp",
      req -> {
        webview.minimizeWindow();
        return null;
      });

  webview.bind(
      "closeApp",
      req -> {
        webview.close();
        return null;
      });

  webview.run();
}
