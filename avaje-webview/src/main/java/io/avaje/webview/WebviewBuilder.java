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
  private boolean outline;
  private MemorySegment parent = MemorySegment.NULL;
  private boolean maximize;
  private boolean fullscreen;
  private boolean resizable = true;
  private boolean maximizable = true;
  private boolean moveParentWithChild;
  private boolean transparent;
  private int minWidth, minHeight, maxWidth, maxHeight = -1;

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
  public WebviewBuilder borderless(boolean borderless, boolean outline) {
    this.borderless = borderless;
    this.outline = outline;
    return this;
  }

  @Override
  public WebviewBuilder parent(Webview parent) {
    this.parent = parent.nativeWindowPointer();
    return this;
  }

  @Override
  public WebviewBuilder parent(Webview parent, boolean moveParentWithChild) {
    this.parent = parent.nativeWindowPointer();
    this.moveParentWithChild = moveParentWithChild;
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
  public WebviewBuilder maximizable(boolean maximizable) {
    this.maximizable = maximizable;
    return this;
  }

  @Override
  public WebviewBuilder minSize(int width, int height) {
    this.minWidth = width;
    this.minHeight = height;
    return this;
  }

  @Override
  public WebviewBuilder maxSize(int width, int height) {
    this.maxWidth = width;
    this.maxHeight = height;
    return this;
  }

  @Override
  public WebviewBuilder resizable(boolean resizable) {
    this.resizable = resizable;
    return this;
  }

  @Override
  public WebviewBuilder transparent(boolean transparent) {
    this.transparent = transparent;
    return this;
  }

  @Override
  public Webview build() {
    final WebviewBase view = createForPlatform();
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
    if (!resizable) {
      view.setFixedSize(width, height);
    } else {
      if (!maximizable) view.disableMaximize();
      if (minWidth > 0) view.setMinSize(minWidth, minHeight);
      if (maxWidth > 0) view.setMaxSize(maxWidth, maxHeight);
    }
    return view;
  }

  private WebviewBase createForPlatform() {
    final var os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win"))
      return new Win32WebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, outline, transparent, parent, moveParentWithChild);
    if (os.contains("mac"))
      return new CocoaWebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, outline, transparent, parent, moveParentWithChild);
    if (os.contains("linux"))
      return new GtkWebView(
          enableDeveloperTools, redirectConsole, width, height, borderless, outline, transparent, parent, moveParentWithChild);
    throw new UnsupportedOperationException(
        "Unsupported platform: " + System.getProperty("os.name"));
  }
}
