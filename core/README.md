## avaje-webview

This is an experimental fork of upstream library: https://github.com/webview/webview_java

The main goals of this fork is to:
- Support GraalVM native image
- Target Java 25 / GraalVM 25
- Minimise the dependencies (which are not in maven central but in https://jitpack.io)

## Upstream

## Changes

- Add support for GraalVM native image
- Add support for extracting the embedded libraries into user home or temp
- Add option to register Shutdown hook to cleanup resources on CTRL-C
- Move native library bootstrap logic into new Bootstrap class
- Remove the dependency on co.casterlabs.commons:platform (local copy of necessary code only)
- Remove the dependency on co.casterlabs.commons:io
- Remove the dependency on Lombok and Jetbrains
- Replace Lombok `@Getter/@Setter` with code
- Remove Lombok `@SneakyThrows`
- Replace Lombok `@NonNull` and Jetbrains `@Nullable` with JSpecify annotations



### How to use

#### Add dependency

```xml
<dependency>
    <groupId>io.avaje.experimental</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

#### Build a Webview

```java
Webview webview = Webview.builder()
    .debug(true)
    .title("My App")
    .width(1000)
    .height(800)
    .html("<h1>Hello World</h1>")
//                .url("http://localhost:" + port)
    .extractToUserHome(true)
    .build();

webview.run();
// webview.close();
```