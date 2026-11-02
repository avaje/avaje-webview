```xml
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>0.30</version>
</dependency>
```

```java
static void main() {

    Webview webview = Webview.builder()
            .title("Hi")
            .html("<h1>Hello World!</h1>")
            .build();

    // needs JVM argument -XstartOnFirstThread on Macos
    webview.run();
}
```