```xml
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>0.30</version>
</dependency>

<dependency>
    <groupId>io.avaje</groupId>
    <artifactId>avaje-jex</artifactId>
    <version>3.6</version>
</dependency>
```


```java
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
                .navigate("http://localhost:" + port)
                .build();

        webview.run();
    } finally {
      server.shutdown();
    }
}
```