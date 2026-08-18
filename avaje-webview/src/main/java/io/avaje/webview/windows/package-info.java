/**
 * Windows webview implementation using Win32 and WebView2 via Panama FFM.
 *
 * <p>{@link io.avaje.webview.windows.Win32WebView} is the entry point. It creates three Win32
 * windows (main, widget host and message-only) and brings up a WebView2 controller inside the
 * widget window. Each instance pumps its own messages with {@code GetMessageW} and {@code
 * DispatchMessageW}, following the {@code win32_edge} backend of the C webview library.
 *
 * <p>Win32 message queues are per-thread, so all three windows and the pump belong to whichever
 * thread constructed the instance. WebView2 COM callbacks for environment creation, controller
 * creation and script messages come back to that same thread through a {@code PostMessageW} to the
 * message-only window, which keeps WebView2 state single-threaded. Cross-thread work takes the same
 * route.
 *
 * <p>{@link io.avaje.webview.windows.Win32} holds the bindings for {@code user32}, {@code
 * kernel32}, {@code dwmapi}, {@code advapi32} and {@code ole32}: window creation and the message
 * pump, the DWM dark-mode attribute, the registry reads that locate an Edge installation, and COM
 * initialisation. The WebView2 callback handlers in {@code Win32WebView} are built at runtime as
 * Panama structs laid out over the vtables {@code CreateCoreWebView2EnvironmentWithOptions} and
 * {@code ICoreWebView2CreateCoreWebView2ControllerCompletedHandler} expect.
 */
package io.avaje.webview.windows;
