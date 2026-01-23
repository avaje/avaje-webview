[![Discord](https://img.shields.io/discord/1074074312421683250?color=%237289da&label=discord)](https://discord.gg/Qcqf9R27BR)
[![Build](https://github.com/avaje/avaje-inject/actions/workflows/build.yml/badge.svg)](https://github.com/avaje/avaje-webview/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-blue)](https://github.com/avaje/avaje-webview/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.avaje.webview/avaje-webview.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/io.avaje.webview/avaje-webview)
[![javadoc](https://javadoc.io/badge2/io.avaje.webview/avaje-webview/javadoc.svg?color=purple)](https://javadoc.io/doc/io.avaje.webview/avaje-webview)

## avaje-webview

Avaje Webview wraps native platform webview engines to provide a clean interface for building modern cross-platform GUIs.

This is an enhanced fork of https://github.com/webview/webview_java with some [major differences](#notable-changes-from-upstream)

## How to use

#### Add dependency

```xml
<!-- if targeting all platforms -->
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview-all</artifactId>
    <version>${version}</version>
</dependency>

<!-- or if just targeting a specific OS

<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview-{windows|mac|linux64|linux32}</artifactId>
    <version>${version}</version>
</dependency>
-->

<!-- or if just targeting a specific OS and architecture
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview-{windows-x86|windows-x86_64|you get the idea}</artifactId>
    <version>${version}</version>
</dependency>
-->
```
If using GraalVM native image and maven, we can use `avaje-webview-platform` as the 
dependency which will use **_maven profiles_** to selectively include the platform 
specific dependencies for Mac, Windows, Linux.

```xml
<!-- uses maven profiles to include platform specific dependencies -->
<dependency>
    <groupId>io.avaje.webview</groupId>
    <artifactId>avaje-webview-platform</artifactId>
    <version>${version}</version>
</dependency>
```


#### Build a Webview

```java
Webview webview = Webview.builder()
    // browser developer tools enabled    
    .enableDeveloperTools(true)
    .title("My App")
    .html("<h1>Hello World</h1>")
    .build();

webview.run();
```

### macOS
macOS requires that all UI code be executed from the first thread, you will need to launch Java with -XstartOnFirstThread.


## Options

### Extracting embedded libraries

```java
// Extract native libs to working dir directory (default, cleaned on exit)
Webview webview = Webview.builder()
    .build();

// Extract native libs to temp directory (also cleaned on exit)
Webview webview = Webview.builder()
    .extractToTemp(true)
    .build();

// Extract native libs to user home (persistent, faster subsequent startups)
Webview webview = Webview.builder()
    .extractToUserHome(true)
    .build();
```

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

// Or set a fixed size
webview.setFixedSize(800, 600);

// Maximize or fullscreen
webview.maximizeWindow();
webview.fullscreen();

//set Dark Mode
webview.setDarkAppearance(true);
```

### Set Window Icon

```java
// From file path
webview.setIcon(Path.of("icon.ico"));

// From classpath resource
webview.setIcon(getClass().getResource("/icon.ico").toURI());
```

## Java-JavaScript Bridge

### Executing JavaScript from Java

```java
Webview webview = Webview.builder()
    .html("<html><body><h1 id='title'>Original</h1></body></html>")
    .build();

// Execute JavaScript immediately
webview.eval("document.getElementById('title').textContent = 'Updated!';");

webview.run();
```

### Executing Java from JavaScript

Expose Java functionality to JavaScript as async functions:

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
                         // Calls Java method, returns Promise
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
        
// Bind Java method to JavaScript
webview.bind("greet", (String jsonArgs) -> {
       // do something with the args here
      return "Recieved, " + jsonArgs + "!");
   });
webview.run();
```

### Example Complex Data Exchange

```java
record User(String name, int age) {}
record UserRequest(String action, String userId) {}

Webview webview = Webview.builder()
    .html("""
        <script>
            async function getUser() {
                const user = await fetchUser({
                    action: 'get',
                    userId: '123'
                });
                console.log(user.name, user.age);
            }
        </script>
    """)
    .build();

webview.bind("fetchUser", (String jsonArgs) -> {
    UserRequest request = Jsonb.instance().type(UserRequest.class).list().fromJson(jsonArgs).getFirst();
    
    // Simulate database lookup
    User user = new User("Alice", 30);
    
    return Jsonb.instance().toJson(user);
});

webview.eval("getUser();");
webview.run();
```

## Notable changes (from upstream)

- Add support for GraalVM native image
- Use FFM instead of JNA
- Full JPMS support
- Add support for extracting the embedded libraries into temp or user home subdir
- Builder pattern to replace constructors
- More window functions (setting icons, maximizing and fullscreen)
- Mac window functions
- Remove the dependency on co.casterlabs.commons:platform (local copy of necessary code only)
- Remove the dependency on co.casterlabs.commons:io
- Remove the dependency on Lombok and Jetbrains
- Replace Lombok with code
- Replace Lombok `@NonNull` and Jetbrains `@Nullable` with JSpecify annotations
