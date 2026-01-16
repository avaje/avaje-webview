import io.avaje.webview.linux64.LinuxSo;

module io.avaje.webview.linux64 {

  requires transitive io.avaje.webview;
  requires static io.avaje.spi;

  provides io.avaje.webview.spi.NativeLoader with LinuxSo;
}