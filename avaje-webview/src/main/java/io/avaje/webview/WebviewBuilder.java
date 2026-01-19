package io.avaje.webview;

import static io.avaje.webview.platform.Platform.OS_DISTRIBUTION;
import static io.avaje.webview.platform.Platform.archTarget;

import module java.base;

import io.avaje.webview.Webview.Builder;
import io.avaje.webview.platform.LinuxLibC;

/**
 * A fluent builder for configuring and instantiating {@link Webview} instances.
 *
 * <p>This builder handles native library extraction, window sizing, and initial content loading. *
 *
 * <h3>Example Usage:</h3>
 *
 * <pre>{@code
 * Webview wv = Webview.builder()
 *   .title("My App")
 *   .width(1024)
 *   .height(768)
 *   .url("https://example.com")
 *   .enableDeveloperTools(true)
 *   .build();
 *
 *   // Standard usage: This blocks until the window is closed
 * wv.run();
 *
 * }</pre>
 */
final class WebviewBuilder implements Builder {

  private static WebviewNative NATIVE_LIB;

  private boolean extractToUserHome;
  private boolean extractToTemp;
  private String title;
  private boolean enableDeveloperTools;
  private MemorySegment windowPointer;
  private int width = 800;
  private int height = 600;
  private String html;
  private String url;
  private boolean shutdownHook = true;
  private boolean keepExtractedFile;

  WebviewBuilder() {}

  @Override
  public WebviewBuilder extractToTemp(boolean extractToTemp) {
    this.extractToTemp = extractToTemp;
    return this;
  }

  @Override
  public WebviewBuilder extractToUserHome(boolean extractToUserHome) {
    this.extractToUserHome = extractToUserHome;
    return this;
  }

  @Override
  public WebviewBuilder title(String title) {
    this.title = title;
    return this;
  }

  @Override
  public WebviewBuilder enableDeveloperTools(boolean enableDeveloperTools) {
    this.enableDeveloperTools = enableDeveloperTools;
    return this;
  }

  @Override
  public WebviewBuilder windowPointer(MemorySegment windowPointer) {
    this.windowPointer = windowPointer;
    return this;
  }

  @Override
  public WebviewBuilder width(int width) {
    this.width = width;
    return this;
  }

  @Override
  public WebviewBuilder height(int height) {
    this.height = height;
    return this;
  }

  @Override
  public WebviewBuilder html(String html) {
    this.html = html;
    return this;
  }

  @Override
  public WebviewBuilder navigate(String url) {
    this.url = url;
    return this;
  }

  @Override
  public WebviewBuilder shutdownHook(boolean shutdownHook) {
    this.shutdownHook = shutdownHook;
    return this;
  }

  @Override
  public Webview build() {
    var n = initNative(this);
    var view = new DWebView(n, enableDeveloperTools, windowPointer, width, height);
    if (title != null) {
      view.setTitle(title);
    }
    if (url != null) {
      view.navigate(url);
    } else if (html != null) {
      view.setHTML(html);
    } else {
      view.navigate("about:blank");
    }
    if (shutdownHook) {
      Runtime.getRuntime().addShutdownHook(new Hook(view::close));
    }
    return view;
  }

  static final class Hook extends Thread {

    Hook(Runnable runnable) {
      super(runnable, "WebviewHook");
    }

    @Override
    public void run() {
      super.run();
    }
  }

  private synchronized WebviewNative initNative(WebviewBuilder bootstrap) {
    if (NATIVE_LIB == null) {
      NATIVE_LIB = bootstrap.initNativeLibrary();
    }
    return NATIVE_LIB;
  }

  private WebviewNative initNativeLibrary() {
    for (String lib : platformLibraries()) {
      File target = createTarget(lib);
      if (target.exists() && !keepExtractedFile && !target.delete()) {
        System.out.println("Failed to delete previously extracted: " + target);
      }
      if (!keepExtractedFile) {
        target.deleteOnExit();
      }

      if (target.exists() || extractToFile(lib, target)) {
        System.load(target.getAbsolutePath());
      }
    }

    // Return the FFM-based native implementation
    return new WebviewNative();
  }

  private File createTarget(String lib) {
    var libName = new File(lib).getName();
    if (extractToUserHome) {
      keepExtractedFile = false;
      String userHome = System.getProperty("user.home");
      var homeDir = new File(userHome);
      if (homeDir.exists()) {
        File extractToDir = Path.of(userHome, ".avaje-webview", "0.2").toFile();
        if (!extractToDir.exists() && !extractToDir.mkdirs()) {
          System.err.println("Failed to create directory to extract libraries: " + extractToDir);
        }
        keepExtractedFile = true;
        return new File(extractToDir, libName);
      }
    }
    if (extractToTemp) {
      try {
        return File.createTempFile("webview-", "-" + libName);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return new File(libName);
  }

  private static List<String> platformLibraries() {
    try {
      String prefix = "/io/avaje/webview/nativelib/";
      switch (OS_DISTRIBUTION) {
        case LINUX -> {
          if (LinuxLibC.isGNU()) {
            return List.of(prefix + "linux/" + archTarget + "/gnu/libwebview.so");
          }
          return List.of(prefix + "linux/" + archTarget + "/musl/libwebview.so");
        }
        case MACOS -> {
          return List.of(prefix + "macos/" + archTarget + "/libwebview.dylib");
        }
        case WINDOWS_NT -> {
          return List.of(prefix + "windows_nt/" + archTarget + "/webview.dll");
        }
        default ->
            throw new IllegalStateException(
                "Unsupported platform: " + OS_DISTRIBUTION + ":" + archTarget);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean extractToFile(String lib, File target) {
    try (var in = WebviewNative.class.getResourceAsStream(lib.toLowerCase());
        var out = new FileOutputStream(target)) {
      if (in == null)
        throw new IllegalStateException("Failed to access resource of native: " + lib);

      in.transferTo(out);
      return true;
    } catch (Exception e) {
      if (!e.getMessage().contains("used by another")) {
        System.err.println("Unable to extract native: " + lib);
        throw new RuntimeException(e);
      }
      return false;
    }
  }
}
