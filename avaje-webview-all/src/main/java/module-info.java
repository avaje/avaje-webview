
/**
 * Aggregator Module for all native libraries
 */
module io.avaje.webview.all {
  requires transitive io.avaje.webview;
  requires io.avaje.webview.linux_32bit;
  requires io.avaje.webview.linux_64bit;
  requires io.avaje.webview.mac;
  requires io.avaje.webview.windows;
}