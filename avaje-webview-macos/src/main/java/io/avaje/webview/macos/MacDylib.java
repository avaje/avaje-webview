package io.avaje.webview.macos;

import java.io.InputStream;

import io.avaje.spi.ServiceProvider;
import io.avaje.webview.spi.NativeLoader;

@ServiceProvider
public class MacDylib implements NativeLoader {

  @Override
  public InputStream load(String path) {
    return MacDylib.class.getResourceAsStream(path);
  }
}
