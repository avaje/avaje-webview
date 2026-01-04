package example;

import io.avaje.jex.Jex;
import io.avaje.webview.Webview;

public class Main {

    static void main(String[] args) {
        // needs JVM argument -XstartOnFirstThread on Macos
        Jex.Server server = Jex.create()
                .get("/", context -> context.html("<h1>Hi Jex!</h1>"))
                .port(0) // random port
                .start();

        int port = server.port();

        try {
            Webview webview = Webview.builder()
                    .title("Hi Jex")
                    .url("http://localhost:" + port)
                    .build();

            webview.run();
        } finally {
          server.shutdown();
        }
    }
}
