package io.example;

import io.avaje.inject.*;
import io.avaje.jex.Jex;
import io.avaje.webview.Webview;

public class Main {
    static void main(String[] args) {

        var server = Jex.create()
                .configureWith(BeanScope.builder().build())
                .port(0) // 8092
                .start();

        int port = server.port();

        Webview wv = Webview.builder()
                .enableDeveloperTools(true)
                .extractToUserHome(true)
                .title("My App")
                .width(1000)
                .height(800)
                .url("http://localhost:" + port)
                .build();

        // jex.lifecycle().onShutdown(() -> System.out.println("lifecycle stop"));
        // server.onShutdown(wv::close); //, Integer.MIN_VALUE);

        wv.run(); // Run the webview event loop, the webview is fully disposed when this returns.
        System.out.println("normal stop");
        // wv.close(); // Free any resources allocated.
        server.shutdown();
        System.out.println("done");
    }
}