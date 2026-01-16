import io.avaje.webview.spi.NativeLoader;

module io.avaje.webview.windows {

  requires transitive io.avaje.webview;
  requires static io.avaje.spi;

  provides NativeLoader with io.avaje.webview.windows.WindowsDLL;
}