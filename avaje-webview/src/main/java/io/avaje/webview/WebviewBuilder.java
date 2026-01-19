package io.avaje.webview;

import static io.avaje.webview.platform.Platform.OS_DISTRIBUTION;
import static io.avaje.webview.platform.Platform.archTarget;
import static java.util.Objects.requireNonNull;

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
    String prefix = "/io/avaje/webview/nativelib/";
    String lib = prefix + System.mapLibraryName("webview");

    File target = createTarget(lib);
    if (target.exists() && !keepExtractedFile && !target.delete()) {
      System.out.println("Failed to delete previously extracted: " + target);
    }
    if (!keepExtractedFile) {
      target.deleteOnExit();
    }
    if (target.exists() || extractToFile(lib.toLowerCase(), target)) {
      System.load(target.getAbsolutePath());
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

  private static boolean extractToFile(String lib, File target) {
    List<InputStream> natives = ModuleLayer.boot()
        .modules()
        .stream()
        .map(module -> {
            try {
                return module.getResourceAsStream(lib);
            } catch (IOException exception) {
                throw new UncheckedIOException("Fatal error streaming '" + lib + "'", exception);
            }
        })
        .filter(Objects::nonNull)
        .toList();
    if (natives.isEmpty()) {
      InputStream stream = requireNonNull(WebviewBuilder.class.getResourceAsStream(lib));
      natives = List.of(stream);
    }

    int size = natives.size();
    if (size != 1) throw new IllegalStateException("Multiple natives found (" + size + ")");

    try (InputStream in = natives.getFirst();
        OutputStream out = new FileOutputStream(target)) {
      in.transferTo(out);
      return true;
    } catch (IOException exception) {
      if (exception.getMessage().contains("used by another")) return false;
      throw new UncheckedIOException(exception);
    }
  }
}
