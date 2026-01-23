import io.avaje.webview.Webview;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

DateTimeFormatter FORMATTER =
    DateTimeFormatter.ofPattern("HH:mm:ss");

String CSS = """
body {
    font-family: system-ui, sans-serif;
    padding: 40px;
}

h1 {
    color: #667eea;
}

input {
    padding: 10px;
    width: 100%;
    margin: 10px 0;
}

button {
    padding: 10px 20px;
    background: #667eea;
    color: white;
    border: none;
    cursor: pointer;
}

.response {
    background: #f0f9ff;
    padding: 15px;
    margin-top: 20px;
    display: none;
}

.response.show {
    display: block;
}
""";

String HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Message Demo</title>
  <style>
  """ + CSS + """
  </style>
</head>
<body>
  <h1>Two-Way Communication</h1>
  <p>Send message to Java, get async response</p>

  <input type="text" id="messageInput"
         placeholder="Type your message..."
         value="Hello from JavaScript!">
  <button onclick="sendToJava()">Send to Java</button>

  <div class="response" id="response">
    Waiting for Java response...
  </div>

  <script>
    async function sendToJava() {
      const message = document.getElementById('messageInput').value;
      const responseDiv = document.getElementById('response');

      responseDiv.classList.add('show');
      responseDiv.textContent = 'Sending to Java...';

      console.log('JS → Java:', message);
      await sendMessage(message);

      responseDiv.textContent =
        'Message sent! Waiting for async response from Java...';
    }

    // Called by Java via eval()
    function displayResponse(response) {
      const responseDiv = document.getElementById('response');
      responseDiv.classList.add('show');
      responseDiv.textContent = response;
      console.log('Java → JS:', response);
    }

    console.log('Ready! Type a message and click Send.');
  </script>
</body>
</html>
""";

void main() {
    Webview webview = Webview.builder()
        .title("Java ↔ JavaScript Communication")
        .width(600)
        .height(400)
        .enableDeveloperTools(true)
        .html(HTML)
        .build();
    
    // Bind: JavaScript sends a message to Java
    webview.bind("sendMessage", argsJson -> {
        String message = parseFirstArg(argsJson);
        System.out.println("[Java] Received: " + message);
        
        // Java processes and responds back asynchronously using eval
        Thread.startVirtualThread(() -> {
            // simulate processing delay
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
        
            String response = escapeJS("Java received your message at " +
                    LocalDateTime.now().format(FORMATTER) +
                    ": \"" + message + "\"");
            webview.eval("displayResponse('" + response + "')");
        });
        
        return "Message sent to Java";
    });
    
    webview.run();
}

String parseFirstArg(String json) {
    if (json.startsWith("[\"") && json.endsWith("\"]")) {
      return json.substring(2, json.length() - 2)
          .replace("\\\"", "\"")
          .replace("\\\\", "\\");
    }
    return "";
}

String escapeJS(String str) {
    return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
}