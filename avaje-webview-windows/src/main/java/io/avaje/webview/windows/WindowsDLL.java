package io.avaje.webview.windows;

import java.io.InputStream;

import io.avaje.spi.ServiceProvider;
import io.avaje.webview.spi.NativeLoader;

@ServiceProvider
public class WindowsDLL implements NativeLoader {

  @Override
  public InputStream load(String path) {
    return WindowsDLL.class.getResourceAsStream(path);
  }
}
