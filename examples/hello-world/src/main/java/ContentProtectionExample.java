import io.avaje.webview.Webview;

String HOME = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    body {
      font-family: system-ui, sans-serif;
      padding: 24px;
    }

    .secret {
      font-family: monospace;
      font-size: 20px;
      padding: 12px;
      background: #eee;
      border-radius: 6px;
    }
  </style>
</head>
<body>
  <h1>Content protection</h1>

  <p>Try a screenshot, OBS, or a Discord screen share. The window should come out blank.</p>
  <p class="secret">Tensa Zangetsu</p>

  <p>
    <label>
      <input type="checkbox" id="protect" checked onchange="toggleProtection(this.checked)">
      Hide from screen capture
    </label>
  </p>
</body>
</html>
""";

void main() {
  Webview webview = Webview.builder()
      .title("Content Protection Example")
      .width(560)
      .height(320)
      .contentProtection(true)
      .html(HOME)
      .build();

  webview.bind("toggleProtection", params -> {
    webview.setContentProtection(params.contains("true"));
    return "null";
  });

  webview.run();
}
