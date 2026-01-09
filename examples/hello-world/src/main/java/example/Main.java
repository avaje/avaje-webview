package example;

import io.avaje.webview.Webview;

public class Main {

  static void main() {

    Webview webview = Webview.builder().title("Hi").html("<h1>Hello World!</h1>").build();

    // needs JVM argument -XstartOnFirstThread on Macos
    webview.run();
  }
}
