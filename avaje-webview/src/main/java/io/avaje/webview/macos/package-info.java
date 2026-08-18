/**
 * macOS webview implementation using Cocoa and WKWebView via Panama FFM.
 *
 * <p>{@link io.avaje.webview.macos.CocoaWebView} is the entry point. It runs the Cocoa loop through
 * {@code [NSApplication run]} and puts a {@code WKWebView} inside an {@code NSWindow}. Cocoa and
 * WebKit calls all land on the main thread, sent there with {@code dispatch_async_f} on {@code
 * _dispatch_main_q}.
 *
 * <p>Cocoa insists on the main thread for UI work, and on macOS the JVM main thread only counts as
 * AppKit's main thread under {@code -XstartOnFirstThread}. Without that flag {@code [NSApplication
 * run]} blocks on a thread AppKit does not recognise, and UI calls from elsewhere die in {@code
 * -[NSApplication _checkForIllegalThreading]}. Work from other threads is queued on a {@code
 * ConcurrentLinkedQueue} and drained by a Panama upcall stub posted to the main queue.
 *
 * <p>The native bindings live in {@link io.avaje.webview.macos.ObjC}, covering the Objective-C
 * runtime ({@code libobjc.A.dylib}): the {@code objc_msgSend} variants, class, selector and method
 * registration, and the runtime synthesis of the {@code WKScriptMessageHandler} protocol. {@link
 * io.avaje.webview.macos.MacOSHelper} builds fullscreen, maximize, dark mode and icon loading on
 * top of those.
 *
 * <p>Java cannot implement an ObjC protocol by inheritance, so {@code CocoaWebView} synthesises
 * {@code WKScriptMessageHandler} at runtime with {@code objc_allocateClassPair} and {@code
 * class_addMethod}, registering a Panama upcall stub as the IMP for {@code
 * userContentController:didReceiveScriptMessage:}. Each window gets its own class so the closures
 * stay separate.
 *
 * <p>Two things have to be in place at runtime: the {@code -XstartOnFirstThread} JVM flag, and
 * {@code WebKit.framework} dlopen'd before the first {@code objc_getClass}, or the runtime never
 * sees its classes.
 */
package io.avaje.webview.macos;
