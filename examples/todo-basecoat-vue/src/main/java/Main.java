import io.avaje.webview.Webview;

void main() {
  try (var stream = this.getClass().getResourceAsStream("index.html")) {
    if (stream == null) throw new IllegalStateException();
    
    var webview = Webview.builder()
        .title("avaje todo")
        .html(new String(stream.readAllBytes()))
        .build();
    webview.run();
  } catch (IOException exception) {
    throw new IllegalStateException(exception);
  }
}
