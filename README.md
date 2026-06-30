![Supported JVM Versions](https://img.shields.io/badge/JVM-25+-brightgreen.svg?&logo=openjdk)
[![Discord](https://img.shields.io/discord/1074074312421683250?color=%237289da&label=discord)](https://discord.gg/Qcqf9R27BR)
[![Build](https://github.com/avaje/avaje-webview/actions/workflows/build.yml/badge.svg)](https://github.com/avaje/avaje-webview/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](https://github.com/avaje/avaje-webview/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.avaje.webview/avaje-webview.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/io.avaje.webview/avaje-webview)
[![javadoc](https://javadoc.io/badge2/io.avaje.webview/avaje-webview/javadoc.svg?color=purple)](https://javadoc.io/doc/io.avaje.webview/avaje-webview)

## avaje-webview

Avaje Webview wraps native platform webview engines to provide a clean interface for building modern cross-platform GUIs.

Uses **Java 25 FFM** (Foreign Function & Memory) to call native libraries directly.

This is an enhanced fork of https://github.com/webview/webview_java with some [major differences](#notable-changes-from-upstream)

## Platform requirements

| Platform | Engine | Requirement |
|----------|--------|-------------|
| **Linux** | WebKitGTK 6.0 | `libgtk-4`, `libwebkitgtk-6.0`, `libjavascriptcoregtk-6.0` must be installed |
| **macOS** | WKWebView | Built into macOS — no install needed |
| **Windows** | WebView2 (Edge) | Microsoft Edge must be installed (pre-installed on Windows 10+) |

Linux install example (Debian/Ubuntu):
```sh
sudo apt-get install libgtk-4-1 libwebkitgtk-6.0-4 libjavascriptcoregtk-6.0-1
```

## How to use

#### Add dependency

```xml
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview</artifactId>
    <version>${version}</version>
</dependency>
```
#### JVM flags

On **macOS**, the first webview must be created on the OS main thread. Pass `-XstartOnFirstThread` to the JVM:

```sh
java -XstartOnFirstThread --enable-native-access=ALL-UNNAMED -jar myapp.jar
```

#### Build a Webview

```java
Webview webview = Webview.builder()
    .enableDeveloperTools(true)
    .title("My App")
    .html("<h1>Hello World</h1>")
    .build();

webview.run();
```

Or navigate to a URL:

```java
Webview webview = Webview.builder()
    .title("My App")
    .navigate("https://avaje.io")
    .build();

webview.run();
```

## Options

### Window Properties

```java
Webview webview = Webview.builder()
    .title("Configurable Window")
    .width(1200)
    .height(800)
    .enableDeveloperTools(true) // Enable right-click > Inspect
    .build();

// Set window constraints after creation
webview.setMinSize(600, 400);
webview.setMaxSize(1920, 1080);

// Or lock to a fixed size
webview.setFixedSize(800, 600);

// Maximize or fullscreen
webview.maximizeWindow();
webview.fullscreen();

// Dark mode
webview.setDarkAppearance(true);
```

### Set Window Icon

```java
// From file path
webview.setIcon(Path.of("icon.ico"));

// From classpath resource
webview.setIcon(getClass().getResource("/icon.ico").toURI());
```

> **Note:** GTK4 dropped support for arbitrary file-based window icons. On Linux, the app icon
> is set via the `.desktop` file and icon theme. `setIcon` is a no-op on Linux.

## Java-JavaScript Bridge

### Executing JavaScript from Java

```java
Webview webview = Webview.builder()
    .html("<html><body><h1 id='title'>Original</h1></body></html>")
    .build();

// Execute JavaScript in the webview
webview.eval("document.getElementById('title').textContent = 'Updated!';");

webview.run();
```

### Calling Java from JavaScript

Bind Java functions as async `window` globals that return Promises:

```java
Webview webview = Webview.builder()
    .title("Java Bridge Example")
    .html("""
        <!DOCTYPE html>
        <html>
        <body>
           <button onclick="callJava()">Call Java</button>
           <div id="result"></div>
           <script>
               async function callJava() {
                   try {
                       const result = await greet('World');
                       document.getElementById('result').textContent = result;
                   } catch (error) {
                       console.error('Java error:', error);
                   }
               }
           </script>
        </body>
        </html>
    """)
    .build();

// Bind Java method to JavaScript — args arrive as a JSON array string
webview.bind("greet", (String jsonArgs) -> {
    return "\"Hello, " + jsonArgs + "!\""; // return value must be valid JSON
});

webview.run();
```

Binding callbacks receive arguments as a JSON array string and must return a valid JSON string.
The JavaScript side receives the return value as the resolved Promise value.

### Complex Data Exchange

```java
record User(String name, int age) {}
record UserRequest(String action, String userId) {}

Webview webview = Webview.builder()
    .html("""
        <script>
            async function getUser() {
                const user = await fetchUser({ action: 'get', userId: '123' });
                console.log(user.name, user.age);
            }
        </script>
    """)
    .build();

webview.bind("fetchUser", (String jsonArgs) -> {
    UserRequest request = Jsonb.instance().type(UserRequest.class).list().fromJson(jsonArgs).getFirst();
    User user = new User("Alice", 30);
    return Jsonb.instance().toJson(user);
});

webview.eval("getUser();");
webview.run();
```

## Multiple Windows

Multiple windows are supported on all platforms. Additional windows can be created from any
thread once the first window's event loop is running.

```java
try (var w1 = Webview.create(false)) {
    w1.bind("openSecond", _ -> {
        Thread.ofPlatform().start(() -> {
            try (var w2 = Webview.create(false)) {
                w2.setHTML("<h1>Window 2</h1>");
                w2.run();
            }
        });
        return "null";
    });
    w1.setHTML("<button onclick='openSecond()'>Open second window</button>");
    w1.run();
}
```

**macOS constraint:** the first `Webview` must be created on the OS main thread
(`-XstartOnFirstThread`). Additional windows may be created from background threads.

**Linux constraint:** the first `Webview` must be created on the thread that will call `run()`.
All subsequent windows dispatch their init to that thread automatically.

## Console output

`console.log`, `console.warn`, `console.error`, etc. are automatically forwarded to
`java.lang.System.Logger` under the name `io.avaje.webview`. Configure your logging
framework to see webview JS console output.

## Notable changes from upstream

- Use **FFM** instead of JNA, no embedded native libraries
- Full **JPMS** support
- Pure **Java 25** implementation
- Support for **GraalVM native image**
- Builder pattern replaces constructors
- **Multi-window** support on macOS and Linux
- Extended window API: `setIcon`, `maximizeWindow`, `fullscreen`, `setDarkAppearance`, `setFixedSize`, `setMinSize`, `setMaxSize`
- Automatic **console redirection** (JS console -> Java logger)
- Remove dependency on `co.casterlabs.commons:platform`
- Remove dependency on `co.casterlabs.commons:io`
- Remove Lombok and Jetbrains annotations; replaced with JSpecify (`@NonNull`, `@Nullable`)
