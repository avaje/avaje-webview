
module io.avaje.webview.macos {

  requires transitive io.avaje.webview;
  requires static io.avaje.spi;

  provides io.avaje.webview.spi.NativeLoader with io.avaje.webview.macos.MacDylib;
}