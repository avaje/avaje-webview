/**
 * Aggregator Module for all native linux 32 libraries
 */
module io.avaje.webview.linux32 {
  requires transitive io.avaje.webview;
  requires io.avaje.webview.linux.arm.gnu;
  requires io.avaje.webview.linux.arm.musl;
  requires io.avaje.webview.linux.x86.gnu;
  requires io.avaje.webview.linux.x86.musl;

}