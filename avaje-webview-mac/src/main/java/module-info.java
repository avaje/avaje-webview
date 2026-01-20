/**
 * Aggregator Module for mac native libraries
 */
module io.avaje.webview.mac {
  requires transitive io.avaje.webview;
  requires transitive io.avaje.webview.mac.aarch64;
  requires transitive io.avaje.webview.mac.x86_64;
}