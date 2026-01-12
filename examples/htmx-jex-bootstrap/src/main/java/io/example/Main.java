package io.example;

import io.avaje.inject.*;
import io.avaje.jex.Jex;
import io.avaje.webview.Webview;

import static java.lang.System.Logger.Level.DEBUG;

public class Main {

  static final System.Logger log = System.getLogger("app");

  static void main(String[] args) {

    var server =
        Jex.create()
            .configureWith(BeanScope.builder().build())
            .port(Integer.getInteger("http.port", 0)) // 8092
            .start();

    int port = server.port();

    Webview wv =
        Webview.builder()
            .enableDeveloperTools(true)
            .extractToUserHome(true)
            .title("My App")
            .width(1000)
            .height(800)
            .url("http://localhost:" + port)
            .build();

    wv.run();
    server.shutdown();
    log.log(DEBUG, "done");
  }
}
