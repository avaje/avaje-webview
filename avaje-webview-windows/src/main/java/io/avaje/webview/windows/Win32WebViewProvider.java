package io.avaje.webview.windows;

import io.avaje.webview.Webview;
import io.avaje.webview.WebviewProvider;

import java.lang.foreign.MemorySegment;

public final class Win32WebViewProvider implements WebviewProvider {

  @Override
  public boolean isSupported() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("win");
  }

  @Override
  public Webview create(boolean debug, int width, int height, MemorySegment windowPointer) {
    return new Win32WebView(debug, width, height);
  }
}
