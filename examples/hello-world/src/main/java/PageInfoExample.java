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

    dt {
      font-weight: bold;
      margin-top: 12px;
    }

    dd {
      font-family: monospace;
      margin: 4px 0 0;
    }
  </style>
</head>
<body>
  <h1>Page info</h1>

  <p><button onclick="showInfo()">Read from Java</button></p>
  <p><button onclick="useAnotherAgent()">Change the user agent and reload</button></p>

  <dl>
    <dt>Webview.getUrl()</dt>
    <dd id="url"></dd>
    <dt>navigator.userAgent</dt>
    <dd id="agent"></dd>
  </dl>

  <script>
    async function showInfo() {
      document.getElementById('url').textContent = await currentUrl();
      document.getElementById('agent').textContent = navigator.userAgent;
    }

    window.addEventListener('load', showInfo);
  </script>
</body>
</html>
""";

void main() {
  Webview webview = Webview.builder()
      .title("Page Info Example")
      .width(760)
      .height(420)
      .userAgent("PageInfoExample/1.0")
      .html(HOME)
      .build();

  // Bindings run on the UI thread, where getUrl() answers straight away.
  webview.bind("currentUrl", _ -> "\"" + webview.getUrl() + "\"");

  // A new user agent applies from the next load, so reload the page to see it.
  webview.bind("useAnotherAgent", _ -> {
    webview.setUserAgent("PageInfoExample/2.0");
    webview.setHTML(HOME);
    return "null";
  });

  webview.run();
}
