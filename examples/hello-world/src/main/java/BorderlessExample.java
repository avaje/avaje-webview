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
      -webkit-app-region: drag;
  }

  .titlebar-close {
      cursor: pointer;
      padding: 4px 10px;
      -webkit-app-region: no-drag;
  }

  .titlebar-close:hover {
      background: rgba(255, 255, 255, 0.2);
  }

  .content {
      padding: 20px;
  }
  </style>
</head>
<body>
  <div class="titlebar">
    <span>Borderless Window</span>
    <span class="titlebar-close" onclick="closeApp()">✕</span>
  </div>
  <div class="content">
    <p>This window has no native title bar or border.</p>
    <p>Drag the purple bar above to move the window.</p>
  </div>
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
      "closeApp",
      req -> {
        webview.close();
        return null;
      });

  webview.run();
}
