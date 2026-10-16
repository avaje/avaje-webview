import io.avaje.webview.Webview;

String HTML =
"""
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <style>
    body {
      font-family: system-ui, sans-serif;
      padding: 32px;
    }

    label {
      display: block;
      margin-top: 12px;
    }

    input {
      padding: 6px;
      margin-top: 4px;
    }

    button {
      margin: 16px 8px 0 0;
      padding: 8px 16px;
    }

    pre {
      background: #f4f4f8;
      padding: 12px;
      white-space: pre-wrap;
    }
  </style>
</head>
<body>
  <h1>evalResult() and native dialogs</h1>

  <label>Name <input id="name" value="Ada"></label>
  <label>Attachment <input id="file" type="file" multiple></label>

  <button onclick="inspectPage()">Let Java read this page</button>
  <button onclick="alert('Hello from alert()')">alert()</button>
  <button onclick="show('confirm() returned ' + confirm('Proceed?'))">confirm()</button>
  <button onclick="show('prompt() returned ' + prompt('Your name?', 'Ada'))">prompt()</button>

  <pre id="output">Results show up here.</pre>

  <script>
    function show(text) {
      document.getElementById('output').textContent = text;
    }

    function slowAnswer() {
      return new Promise(resolve => setTimeout(() => resolve(42), 500));
    }
  </script>
</body>
</html>
""";

void main() {
  Webview webview = Webview.builder()
      .title("evalResult() Example")
      .width(640)
      .height(520)
      .enableDeveloperTools(true)
      .html(HTML)
      .build();

  // The button calls into Java, and Java pulls what it needs out of the page with evalResult().
  // Handlers run on the UI thread, so chain on the futures rather than blocking with join().
  webview.bind("inspectPage", _ -> {
    var name = webview.evalResult("document.getElementById('name').value");
    var files = webview.evalResult("[...document.getElementById('file').files].map(f => f.name)");
    var answer = webview.evalResult("await slowAnswer()");
    var failure = webview.evalResult("missingFunction()");

    failure
        .handle((json, error) -> error != null ? error.getMessage() : json)
        .thenCombine(name, (error, n) -> "name     = " + n + "\nerror    = " + error)
        .thenCombine(files, (text, f) -> text + "\nfiles    = " + f)
        .thenCombine(answer, (text, a) -> text + "\nawaited  = " + a)
        .thenAccept(text -> {
          System.out.println(text);
          webview.eval("show(" + toJsString(text) + ")");
        });
    return "null";
  });

  webview.run();
}

String toJsString(String text) {
  return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
}
