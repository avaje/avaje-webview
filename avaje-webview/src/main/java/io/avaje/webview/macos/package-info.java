/**
 * macOS webview implementation using Cocoa and WKWebView via Panama FFM.
 *
 * <h2>Architecture</h2>
 *
 * <p>{@link io.avaje.webview.macos.CocoaWebView} is the single public entry point. It drives the
 * Cocoa run loop via {@code [NSApplication run]} and creates an {@code NSWindow} containing a
 * {@code WKWebView}. All Cocoa and WebKit calls are dispatched to the main thread using {@code
 * dispatch_async_f} with {@code _dispatch_main_q}.
 *
 * <h2>FFM binding classes</h2>
 *
 * <ul>
 *   <li>{@link io.avaje.webview.macos.ObjC} — bindings for the Objective-C runtime ({@code
 *       libobjc.A.dylib}): {@code objc_msgSend} variants, class/selector/method registration, and
 *       dynamic synthesis of the {@code WKScriptMessageHandler} protocol.
 *   <li>{@link io.avaje.webview.macos.MacOSHelper} — high-level helpers for fullscreen, maximize,
 *       dark mode, and icon loading that compose the lower-level ObjC runtime calls.
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <p>Cocoa requires all UI work on the <em>main thread</em>. On macOS, the JVM main thread
 * <strong>must</strong> be registered as the AppKit main thread, which requires the {@code
 * -XstartOnFirstThread} JVM flag. Without it, {@code [NSApplication run]} blocks on a thread that
 * AppKit does not recognize, and UI calls from other threads crash with {@code -[NSApplication
 * _checkForIllegalThreading]}.
 *
 * <p>Cross-thread work is posted via {@code dispatch_async_f(_dispatch_main_q, ...)} with a Panama
 * upcall stub as the callback, draining a {@code ConcurrentLinkedQueue} on the main thread.
 *
 * <h2>Protocol synthesis</h2>
 *
 * <p>Java cannot implement ObjC protocols via normal inheritance. {@code CocoaWebView} synthesises
 * the {@code WKScriptMessageHandler} protocol at runtime using {@code
 * objc_allocateClassPair}/{@code class_addMethod} with a Panama upcall stub registered as the IMP
 * for {@code userContentController:didReceiveScriptMessage:}. One unique class is created per
 * window instance to keep closures independent.
 *
 * <h2>Runtime requirements</h2>
 *
 * <ul>
 *   <li>JVM flag: {@code -XstartOnFirstThread} (required for AppKit main-thread registration)
 *   <li>{@code WebKit.framework} — loaded explicitly via {@code dlopen} to ensure the ObjC runtime
 *       sees the framework classes before {@code objc_getClass} is called
 * </ul>
 */
package io.avaje.webview.macos;
