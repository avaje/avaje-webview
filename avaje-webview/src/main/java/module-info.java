/**
 * <h2>Avaje Webview</h2>
 *  Provides a modern, lightweight Java wrapper around native platform webview engines (WebView2 on Windows,
 * WebKitGTK on Linux, and WebKit on macOS).
 * <h3>Key Features:</h3>
 * <ul>
 * <li><b>Native Performance:</b> Uses the platform's native browser engine via FFM (Foreign Function & Memory API).</li>
 * <li><b>Bidirectional Bridge:</b> Call Java methods from JavaScript via Promises and execute JavaScript from Java.</li>
 * <li><b>MInimal Dependencies:</b> Minimal footprint with no heavy runtime dependencies.</li>
 * <li><b>Automatic Lifecycle:</b> Managed native library extraction and window event loop handling.</li>
 * </ul>
 * <h3>Getting Started:</h3>
 * The primary entry point is {@link io.avaje.webview.Webview}. Use the builder to configure your window:
 * <pre>{@code
 * Webview webview = Webview.builder()
 * .title("My Desktop App")
 * .url("https://example.com")
 * .build();
 * * webview.run();
 * }</pre>
 */

import io.avaje.webview.spi.NativeLoader;

module io.avaje.webview {

  requires transitive org.jspecify;
  requires static java.desktop;

  exports io.avaje.webview;
  exports io.avaje.webview.spi to io.avaje.webview.linux32, io.avaje.webview.linux64, io.avaje.webview.macos, io.avaje.webview.windows;

  uses NativeLoader;
}