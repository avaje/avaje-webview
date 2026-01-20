package example;

import io.avaje.webview.Webview;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TwoWayCommunicationExample {

  public static void main(String[] args) {
    Webview webview =
        Webview.builder()
            .title("Java ↔ JavaScript Communication")
            .width(600)
            .height(400)
            .enableDeveloperTools(true)
            .html(createHTML())
            .build();
    // Bind: JavaScript sends a message to Java
    webview.bind(
        "sendMessage",
        argsJson -> {
          String message = parseFirstArg(argsJson);
          System.out.println("[Java] Received: " + message);

          // Java processes and responds back asynchronously using eval
          Thread.startVirtualThread(
              () -> {
                // simulate processing delay
                try {
                  Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
                String response =
                    "Java received your message at "
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + ": \""
                        + message
                        + "\"";
                webview.eval("displayResponse('" + escapeJS(response) + "')");
              });

          return "Message sent to Java";
        });

    webview.run();
  }

  private static String parseFirstArg(String json) {
    if (json.startsWith("[\"") && json.endsWith("\"]")) {
      return json.substring(2, json.length() - 2).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return "";
  }

  private static String escapeJS(String str) {
    return str.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String createHTML() {
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="UTF-8">
          <title>Message Demo</title>
          <style>
            body { font-family: system-ui, sans-serif; padding: 40px; }
            h1 { color: #667eea; }
            input { padding: 10px; width: 100%; margin: 10px 0; }
            button { padding: 10px 20px; background: #667eea; color: white; border: none; cursor: pointer; }
            .response { background: #f0f9ff; padding: 15px; margin-top: 20px; display: none; }
            .response.show { display: block; }
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

              responseDiv.textContent = 'Message sent! Waiting for async response from Java...';
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
  }
}
