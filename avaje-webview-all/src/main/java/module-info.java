
/**
 * Aggregator Module for all native libraries
 */
module io.avaje.webview.all {

  requires transitive io.avaje.webview;
  requires io.avaje.webview.linux32;
  requires io.avaje.webview.linux64;
  requires io.avaje.webview.mac;
  requires io.avaje.webview.windows;

}