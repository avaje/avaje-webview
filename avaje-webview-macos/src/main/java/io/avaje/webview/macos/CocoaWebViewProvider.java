package io.avaje.webview.macos;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewProvider;

import java.lang.foreign.MemorySegment;

public final class CocoaWebViewProvider implements WebviewProvider {

  @Override
  public boolean isSupported() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("mac");
  }

  @Override
  public Webview create(boolean debug, int width, int height, MemorySegment windowPointer) {
    return new CocoaWebView(debug, width, height);
  }
}
