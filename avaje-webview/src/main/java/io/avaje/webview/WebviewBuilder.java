package io.avaje.webview;

import module java.base;

import org.jspecify.annotations.NonNull;

import io.avaje.webview.platform.LinuxLibC;
import io.avaje.webview.platform.Platform;

/**
 * Builder for Webview.
 *
 * <pre>{@code
 *
 *    Webview wv = Webview.builder()
 *             .debug(true)
 *             .title("My App")
 *             .width(1000)
 *             .height(800)
 *             .url("http://localhost:" + port)
 *             .build();
 *
 *     wv.run(); // Run the webview event loop, the webview is fully disposed when this returns.
 *     wv.close(); // Free any resources allocated.
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
  private boolean shutdownHook = true;
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

  /** Set to false to disable the registration of a shutdown hook. */
  public WebviewBuilder shutdownHook(boolean shutdownHook) {
    this.shutdownHook = shutdownHook;
    return this;
  }

  /** Build the Webview. */
  public Webview build() {
    var n = initNative(this);
    var view = new Webview(n, enableDeveloperTools, windowPointer, width, height);
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
    return new WebviewNative.Default();
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
      switch (Platform.OS_DISTRIBUTION) {
        case LINUX -> {
          if (LinuxLibC.isGNU()) {
            return List.of(prefix + "linux/" + Platform.archTarget + "/gnu/libwebview.so");
          }
          return List.of(prefix + "linux/" + Platform.archTarget + "/musl/libwebview.so");
        }
        case MACOS -> {
          return List.of(prefix + "macos/" + Platform.archTarget + "/libwebview.dylib");
        }
        case WINDOWS_NT -> {
          return List.of(prefix + "windows_nt/" + Platform.archTarget + "/webview.dll");
        }
        default ->
            throw new IllegalStateException(
                "Unsupported platform: " + Platform.OS_DISTRIBUTION + ":" + Platform.archTarget);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean extractToFile(String lib, File target) {
    try (InputStream in = WebviewNative.class.getResourceAsStream(lib.toLowerCase())) {
      byte[] bytes = toBytes(in);
      Files.write(target.toPath(), bytes);
      return true;
    } catch (Exception e) {
      if (!e.getMessage().contains("used by another")) {
        System.err.println("Unable to extract native: " + lib);
        throw new RuntimeException(e);
      }
      return false;
    }
  }

  private static byte[] toBytes(@NonNull InputStream source) throws IOException {
    if (source == null) {
      throw new NullPointerException("source is marked non-null but is null");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
      streamTransfer(source, out);
      return out.toByteArray();
  }

  private static void streamTransfer(@NonNull InputStream source, @NonNull OutputStream dest)
      throws IOException {
    if (source == null) {
      throw new NullPointerException("source is marked non-null but is null");
    }
    if (dest == null) {
      throw new NullPointerException("dest is marked non-null but is null");
    }
    source.transferTo(dest);
  }
}
