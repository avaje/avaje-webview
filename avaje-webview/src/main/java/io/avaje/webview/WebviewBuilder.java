package io.avaje.webview;

import java.lang.foreign.MemorySegment;

import io.avaje.webview.Webview.Builder;
import io.avaje.webview.linux.GtkWebView;
import io.avaje.webview.macos.CocoaWebView;
import io.avaje.webview.windows.Win32WebView;

final class WebviewBuilder implements Builder {

  private String title;
  private boolean enableDeveloperTools;
  private boolean redirectConsole = false;
  private int width = 800;
  private int height = 600;
  private String html;
  private String url;
  private boolean borderless;
  private MemorySegment parent = MemorySegment.NULL;
  private boolean maximize;
  private boolean fullscreen;

  WebviewBuilder() {}

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
  public WebviewBuilder redirectConsole(boolean redirectConsole) {
    this.redirectConsole = redirectConsole;
    return this;
  }

  @Override
  public WebviewBuilder windowPointer(MemorySegment windowPointer) {
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
  public WebviewBuilder borderless(boolean borderless) {
    this.borderless = borderless;
    return this;
  }

  @Override
  public WebviewBuilder parent(Webview parent) {
    this.parent = parent.nativeWindowPointer();
    return this;
  }

  @Override
  public WebviewBuilder maximize(boolean maximize) {
    this.maximize = maximize;
    return this;
  }

  @Override
  public WebviewBuilder fullscreen(boolean fullscreen) {
    this.fullscreen = fullscreen;
    return this;
  }

  @Override
  public Webview build() {
    final var view = createForPlatform();
    if (title != null) view.setTitle(title);
    if (url != null) {
      view.navigate(url);
    } else if (html != null) {
      view.setHTML(html);
    } else {
      view.navigate("about:blank");
    }
    if (fullscreen) {
      view.fullscreen();
    } else if (maximize) {
      view.maximizeWindow();
    }
    return view;
  }

  private Webview createForPlatform() {
    final var os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win"))
      return new Win32WebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, parent);
    if (os.contains("mac"))
      return new CocoaWebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, parent);
    if (os.contains("linux"))
      return new GtkWebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, parent);
    throw new UnsupportedOperationException(
        "Unsupported platform: " + System.getProperty("os.name"));
  }
}
