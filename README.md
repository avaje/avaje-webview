## avaje-webview

This is an experimental fork of upstream library: https://github.com/webview/webview_java

The main goals of this fork is to:
- Support GraalVM native image
- Target Java 25 / GraalVM 25
- Minimise the dependencies (which are not in maven central but in https://jitpack.io)

## Changes (from upstream)

- Add support for GraalVM native image
- Add support for extracting the embedded libraries into temp or user home subdir
- Add option to register Shutdown hook to cleanup resources on CTRL-C
- Change run() to include webview_terminate(), so easier to ensure resources cleaned up
- Add System.Logger use for resource cleanup (to ease debugging of resource cleanup)
- Introduce WebviewBuilder, move native library bootstrap logic there
- Use Builder pattern to replace constructors
- Remove the dependency on co.casterlabs.commons:platform (local copy of necessary code only)
- Remove the dependency on co.casterlabs.commons:io
- Remove the dependency on Lombok and Jetbrains
- Replace Lombok `@Getter/@Setter` with code
- Remove Lombok `@SneakyThrows`
- Replace Lombok `@NonNull` and Jetbrains `@Nullable` with JSpecify annotations



## How to use

#### Add dependency

```xml
<dependency>
    <groupId>io.avaje.experimental</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>0.2</version>
</dependency>
```

#### Build a Webview

```java
Webview webview = Webview.builder()
    // browser developer tools enabled    
    .enableDeveloperTools(true)
    .title("My App")
    .width(1000)
    .height(800)
    .html("<h1>Hello World</h1>")
//  .url("http://localhost:" + port)
    .build();

webview.run();
```

-------

## Options

### Extracting embedded libraries

By default, the embedded native libs are extracted to the working dir and
deleted on exit. We have the options to instead extract to temp via
`.extractToTemp(true)` or to extract to `~/.avaje-webview` and keep them
(only extract once) via `.extractToUserHome(true)`.

```java
Webview webview = Webview.builder()
    .extractToUserHome(true) 
    // .extractToTemp(true)    
    .title("My App")
    .width(1000)
    .height(800)
    .html("<h1>Hello World</h1>")
    .build();

webview.run();
```


### Shutdown hook

By default, a shutdown hook is registered to ensure that the resource
cleanup occurs via a SIGINT/CTRL-C, so disable shutdownHook if desired
via `.shutdownHook(false)` and then you must ensure `webview.close()`
is called when the process is terminated via a SIGINT/CTRL-C

```java
Webview webview = Webview.builder()
    // you need to ensure resources are cleaned up on SIGINT     
    .shutdownHook(false) 
    .title("My App")
    .width(1000)
    .height(800)
    .html("<h1>Hello World</h1>")
    .build();

webview.run();
```
