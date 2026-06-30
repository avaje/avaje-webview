import io.avaje.webview.Webview;

void main() {
  Webview webview = Webview.builder()
      .title("Hi")
      .enableDeveloperTools(true)
      .build();

  webview.setHTML("<h1>Hello World!</h1>");
  // needs JVM argument -XstartOnFirstThread on Macos
  webview.run();
}