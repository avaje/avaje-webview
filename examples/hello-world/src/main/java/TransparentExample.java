import io.avaje.webview.Webview;

String HTML =
"""
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }

    body {
      background: transparent;
      font-family: system-ui, sans-serif;
      height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      -webkit-app-region: drag;
    }

    .card {
      background: rgba(255,255,255,0.12);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border: 1px solid rgba(255,255,255,0.2);
      border-radius: 16px;
      padding: 32px 40px;
      color: #fff;
      text-align: center;
      -webkit-app-region: no-drag;
    }

    h1 { font-size: 1.6rem; font-weight: 600; margin-bottom: 8px; }
    p  { opacity: 0.65; font-size: 0.95rem; }

    button {
      margin-top: 20px;
      padding: 8px 20px;
      border-radius: 8px;
      border: 1px solid rgba(255,255,255,0.25);
      background: rgba(255,255,255,0.1);
      color: #fff;
      cursor: pointer;
      font-size: 0.9rem;
    }
    button:hover { background: rgba(255,255,255,0.2); }
  </style>
</head>
<body>
  <div class="card">
    <h1>Transparent Window</h1>
    <p>The desktop shows through the empty areas.<br>This card uses <code>backdrop-filter: blur</code>.</p>
    <button onclick="closeApp()">Close</button>
  </div>
</body>
</html>
""";

void main() {
  Webview webview =
      Webview.builder()
          .width(420)
          .height(240)
          .borderless(true)
          .transparent(true)
          .resizable(false)
          .html(HTML)
          .build();

  webview.bind("closeApp", req -> { webview.close(); return null; });

  // needs JVM argument -XstartOnFirstThread on macOS
  webview.run();
}
