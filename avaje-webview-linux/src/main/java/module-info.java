module io.avaje.webview.linux {
  requires transitive io.avaje.webview;
  provides io.avaje.webview.WebviewProvider
      with io.avaje.webview.linux.GtkWebViewProvider;
}
