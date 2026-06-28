package io.avaje.webview.linux;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewProvider;

import java.lang.foreign.MemorySegment;

public final class GtkWebViewProvider implements WebviewProvider {

  @Override
  public boolean isSupported() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("linux");
  }

  @Override
  public Webview create(boolean debug, int width, int height, MemorySegment windowPointer) {
    return new GtkWebView(debug, width, height);
  }
}
