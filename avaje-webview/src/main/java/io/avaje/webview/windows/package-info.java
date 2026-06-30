/**
 * Windows webview implementation using Win32 and WebView2 via Panama FFM.
 *
 * <h2>Architecture</h2>
 *
 * <p>{@link io.avaje.webview.windows.Win32WebView} is the single public entry point. It creates
 * three Win32 windows (main, widget host, message-only) and initializes a WebView2 controller
 * inside the widget window. Each window instance runs its own Win32 message pump via {@code
 * GetMessageW}/{@code DispatchMessageW}, mirroring the reference C webview {@code win32_edge}
 * backend design.
 *
 * <h2>FFM binding classes</h2>
 *
 * <ul>
 *   <li>{@link io.avaje.webview.windows.Win32} — bindings for {@code user32}, {@code kernel32},
 *       {@code dwmapi}, {@code advapi32}, and {@code ole32}: window creation and message pump
 *       ({@code CreateWindowExW}, {@code GetMessageW}, {@code DispatchMessageW}), DWM dark-mode
 *       attribute, registry reads for Edge installation detection, and COM initialisation.
 *   <li>{@link io.avaje.webview.windows.Win32WebView} — WebView2 COM-callback handler synthesised
 *       at runtime as a Panama struct that overlays the vtable expected by {@code
 *       CreateCoreWebView2EnvironmentWithOptions} and {@code
 *       ICoreWebView2CreateCoreWebView2ControllerCompletedHandler}.
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <p>Win32 message queues are per-thread. {@code Win32WebView} creates all three windows and starts
 * the message pump on whichever thread constructed the instance. WebView2 COM callbacks
 * (environment created, controller created, script-message received) are delivered to this same
 * thread via {@code PostMessageW} to the message-only window, ensuring all WebView2 state access
 * remains single-threaded. Cross-thread work is posted via {@code PostMessageW}.
 */
package io.avaje.webview.windows;
