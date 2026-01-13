[![Discord](https://img.shields.io/discord/1074074312421683250?color=%237289da&label=discord)](https://discord.gg/Qcqf9R27BR)
[![Build](https://github.com/avaje/avaje-inject/actions/workflows/build.yml/badge.svg)](https://github.com/avaje/avaje-webview/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](https://github.com/avaje/avaje-webview/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.avaje/avaje-webview.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/io.avaje/avaje-webview)
[![javadoc](https://javadoc.io/badge2/io.avaje/avaje-webview/javadoc.svg?color=purple)](https://javadoc.io/doc/io.avaje/avaje-webview)

## avaje-webview

This is an enhanced fork of https://github.com/webview/webview_java

The main goals of this fork is to:
- Support GraalVM native image
- Target Java 25 / GraalVM 25
- Minimize dependencies 

## How to use

#### Add dependency

```xml
<dependency>
    <groupId>io.avaje</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>${version}</version>
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

### macOS
macOS requires that all UI code be executed from the first thread, which means you will need to launch Java with -XstartOnFirstThread. This also means that the Webview AWT helper will NOT work at all.


## Notable changes (from upstream)

- Add support for GraalVM native image
- Use FFM, replacing use of JNA, remove JNA dependencies
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
