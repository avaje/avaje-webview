module io.avaje.webview.windows {
  requires io.avaje.webview;
  provides io.avaje.webview.WebviewProvider
      with io.avaje.webview.windows.Win32WebViewProvider;
}
