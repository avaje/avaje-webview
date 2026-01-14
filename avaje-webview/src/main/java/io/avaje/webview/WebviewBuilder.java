package io.avaje.webview;

import static io.avaje.webview.platform.Platform.OS_DISTRIBUTION;
import static io.avaje.webview.platform.Platform.archTarget;

import module java.base;

import io.avaje.webview.platform.LinuxLibC;

/**
 * Builder for Webview.
 *
 * <pre>{@code
 * Webview wv = Webview.builder()
 *          .debug(true)
 *          .title("My App")
 *          .width(1000)
 *          .height(800)
 *          .url("http://localhost:" + port)
 *          .build();
 *
 *  wv.run(); // Run the webview event loop, the webview is fully disposed when this returns.
 *  wv.close(); // Free any resources allocated.
 *
 * }</pre>
 */
public final class WebviewBuilder {

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
  private boolean keepExtractedFile;

  WebviewBuilder() {}

  /** Return a new builder for a Webview. */
  public static WebviewBuilder builder() {
    return new WebviewBuilder();
  }

  /**
   * When true the libraries will be extracted to the systems temp directory.
   *
   * <p>When not set, defaults to extracting the embedded libraries into the current working
   * directory.
   */
  public WebviewBuilder extractToTemp(boolean extractToTemp) {
    this.extractToTemp = extractToTemp;
    return this;
  }

  /**
   * When true the libraries will be extracted to a subdirectory under user home - {@code
   * ${user.home}/.avaje-webview/0.2}.
   *
   * <p>This has a slight performance improvement in that the libraries only need to be extracted
   * once and not on every execution.
   *
   * <p>When not set, defaults to extracting the embedded libraries into the current working
   * directory.
   */
  public WebviewBuilder extractToUserHome(boolean extractToUserHome) {
    this.extractToUserHome = extractToUserHome;
    return this;
  }

  /** Set the window title. */
  public WebviewBuilder title(String title) {
    this.title = title;
    return this;
  }

  /** Set to true to enable Browser developer tools (if supported). */
  public WebviewBuilder enableDeveloperTools(boolean enableDeveloperTools) {
    this.enableDeveloperTools = enableDeveloperTools;
    return this;
  }

  /** Set the window to attach the Webview to (typically not set). */
  public WebviewBuilder windowPointer(MemorySegment windowPointer) {
    this.windowPointer = windowPointer;
    return this;
  }

  /** Set the window width (defaults to 800). */
  public WebviewBuilder width(int width) {
    this.width = width;
    return this;
  }

  /** Set the window height (defaults to 600). */
  public WebviewBuilder height(int height) {
    this.height = height;
    return this;
  }

  /** Set raw html content to render. */
  public WebviewBuilder html(String html) {
    this.html = html;
    return this;
  }

  /** Set the url for the Webview to load. */
  public WebviewBuilder url(String url) {
    this.url = url;
    return this;
  }

  /** Build the Webview. */
  public Webview build() {
    return createView(false);
  }

  /** Builds an Asynchronous Webview */
  public Webview buildAsync() {
    return createView(true);
  }

  private DWebView createView(boolean async) {
    var n = initNative(this);
    var view = new DWebView(n, enableDeveloperTools, windowPointer, width, height, async);
    if (title != null) {
      view.setTitle(title);
    }
    if (url != null) {
      view.loadURL(url);
    } else if (html != null) {
      view.setHTML(html);
    } else {
      view.loadURL("about:blank");
    }
    return view;
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
