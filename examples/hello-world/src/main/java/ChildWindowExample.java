import io.avaje.webview.Webview;

String MAIN_HTML =
"""
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Child Window Demo</title>
  <style>
  body {
      font-family: system-ui, sans-serif;
      padding: 40px;
  }

  button {
      padding: 10px 20px;
      background: #667eea;
      color: white;
      border: none;
      cursor: pointer;
  }
  </style>
</head>
<body>
  <h1>Main Window</h1>
  <p>Click the button to open a modal child (dialog) window.</p>
  <p>The main window is blocked - clicking it does nothing - until the child closes.</p>
  <button onclick="openDialog()">Open Dialog</button>

  <script>
    function openDialog() {
      openChild();
    }
  </script>
</body>
</html>
""";

String CHILD_HTML =
"""
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Dialog</title>
  <style>
  body {
      font-family: system-ui, sans-serif;
      padding: 30px;
      text-align: center;
  }

  button {
      padding: 10px 20px;
      background: #667eea;
      color: white;
      border: none;
      cursor: pointer;
  }
  </style>
</head>
<body>
  <h2>Modal Dialog</h2>
  <p>The main window stays inert while this window is open.</p>
  <button onclick="done()">Done</button>

  <script>
    function done() {
      closeDialog();
    }
  </script>
</body>
</html>
""";

void main() {
  Webview main =
      Webview.builder().title("Main Window").width(500).height(300).html(MAIN_HTML).build();

  main.bind(
      "openChild",
      req -> {
        // Build and run the child on its own platform thread - each Webview.run() blocks
        // its calling thread until that window closes, so the child needs its own thread
        // while the main window keeps pumping on this one.
        Thread.ofPlatform()
            .start(
                () -> {
                  try (Webview child =
                      Webview.builder()
                          .title("Please wait")
                          .width(320)
                          .height(180)
                          .parent(main) // disables `main` until this window closes
                          .html(CHILD_HTML)
                          .build()) {
                    child.bind(
                        "closeDialog",
                        _ -> {
                          child.dispatch(child::close);
                          return null;
                        });
                    child.run();
                  }
                });
        return null;
      });

  main.run();
}
