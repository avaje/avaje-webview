package io.avaje.webview.spi;

import java.io.InputStream;

public interface NativeLoader {

  InputStream load(String lowerCase);
}
