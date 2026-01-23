import io.avaje.webview.Webview;

void main() {
  Webview webview = Webview.builder()
      .title("Hi")
      .html("<h1>Hello World!</h1>")
      .enableDeveloperTools(true)
      .build();
  
  // needs JVM argument -XstartOnFirstThread on Macos
  webview.run();
}