package io.avaje.webview;

import module java.base;

import io.avaje.webview.Webview.Builder;

final class WebviewBuilder implements Builder {

  private static final WebviewProvider PROVIDER =
      ServiceLoader.load(WebviewProvider.class).stream()
          .map(ServiceLoader.Provider::get)
          .filter(WebviewProvider::isSupported)
          .findFirst()
          .orElseThrow(() -> new UnsupportedOperationException(
              "No WebviewProvider found for platform: " + System.getProperty("os.name")));

  private String title;
  private boolean enableDeveloperTools;
  private MemorySegment windowPointer = MemorySegment.NULL;
  private int width = 800;
  private int height = 600;
  private String html;
  private String url;

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
  public Webview build() {
    var view = PROVIDER.create(enableDeveloperTools, width, height, windowPointer);
    if (title != null) view.setTitle(title);
    if (url != null) {
      view.navigate(url);
    } else if (html != null) {
      view.setHTML(html);
    } else {
      view.navigate("about:blank");
    }
    return view;
  }
}
