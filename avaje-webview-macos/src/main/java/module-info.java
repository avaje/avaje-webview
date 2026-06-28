module io.avaje.webview.macos {
  requires io.avaje.webview;
  provides io.avaje.webview.WebviewProvider
      with io.avaje.webview.macos.CocoaWebViewProvider;
}
