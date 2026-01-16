package io.avaje.webview.linux64;

import java.io.InputStream;

import io.avaje.spi.ServiceProvider;
import io.avaje.webview.spi.NativeLoader;

@ServiceProvider
public class LinuxSo implements NativeLoader {

  @Override
  public InputStream load(String path) {
    return LinuxSo.class.getResourceAsStream(path);
  }
}
