import io.avaje.webview.Webview;

import java.net.URI;

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

    li {
      margin-bottom: 8px;
    }

    #log {
      background: #f4f4f8;
      height: 160px;
      overflow-y: auto;
      padding: 8px;
      font-family: monospace;
      font-size: 12px;
      white-space: pre-wrap;
    }
  </style>
</head>
<body>
  <h1>Navigation control</h1>

  <p>This window keeps its own page and sends every link to your default browser.</p>

  <ul>
    <li><a href="https://avaje.io">A blocked link</a> (opens in your browser instead)</li>
    <li><a href="https://github.com/avaje/avaje-webview" target="_blank">A target="_blank" link</a> (a new window request)</li>
    <li><button onclick="window.open('https://avaje.io/webview')">window.open()</button> (also a new window request)</li>
    <li><button onclick="openInBrowser('https://openjdk.org')">Open from Java</button> (calls Webview.openExternal)</li>
  </ul>

  <div id="log"></div>

  <script>
    function log(text) {
      const line = new Date().toLocaleTimeString() + '  ' + text + '\\n';
      document.getElementById('log').textContent = line + document.getElementById('log').textContent;
    }
  </script>
</body>
</html>
""";

void main() {
  Webview webview = Webview.builder()
      .title("Navigation Example")
      .width(760)
      .height(560)
      .html(HOME)
      .build();

  // Runs before every navigation the page or an iframe starts. Loads started from Java, like
  // html(HOME) above, skip it. A real app would return true for its own URLs here.
  webview.onNavigate(url -> {
    if (url.startsWith("about:")) {
      return true;
    }
    log(webview, "Intercepted " + url + ", opening it in the browser");
    openInBrowser(webview, url);
    return false;
  });

  // target="_blank" links and window.open(). The webview never opens a popup itself. Without this
  // listener, web links would go to the browser automatically.
  webview.onNewWindow(url -> {
    log(webview, "new window requested for " + url);
    openInBrowser(webview, url);
  });

  webview.bind("openInBrowser", args -> {
    // args is a JSON array holding one string, such as ["https://openjdk.org"]
    String url = args.substring(2, args.length() - 2);
    Webview.openExternal(URI.create(url));
    log(webview, "opened " + url);
    return "null";
  });

  webview.run();
}

// openExternal refuses anything that isn't http, https or mailto, like a file: link.
void openInBrowser(Webview webview, String url) {
  try {
    Webview.openExternal(URI.create(url));
  } catch (IllegalArgumentException e) {
    log(webview, "not opening " + url + ": " + e.getMessage());
  }
}

void log(Webview webview, String text) {
  System.out.println(text);
  webview.eval("log('" + text.replace("\\", "\\\\").replace("'", "\\'") + "')");
}
