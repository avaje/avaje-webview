/**
 * Linux webview implementation using GTK4 and WebKitGTK 6.0 via Panama FFM.
 *
 * <p>{@link io.avaje.webview.linux.GtkWebView} is the entry point. It puts a {@code
 * WebKitWebView} inside a {@code GtkWindow} and drives the event loop itself with {@code
 * g_main_context_iteration}. The window and everything GTK or WebKit touches belongs to the GTK
 * thread, meaning whichever thread built the first {@code GtkWebView}.
 *
 * <p>GTK is not thread-safe, so every GTK, GObject and WebKitGTK call has to start on that thread.
 * Work arriving from anywhere else goes on a {@code ConcurrentLinkedQueue} and is picked up by a
 * Panama upcall stub scheduled with {@code g_idle_add_full}, which drains the queue on the next
 * main-loop iteration.
 *
 * <p>The native bindings are split by library:
 *
 * <ul>
 *   <li>{@link io.avaje.webview.linux.GLib}: {@code libglib-2.0} and {@code libgobject-2.0}, for
 *       the main loop, idle sources, GObject reference counting and signals.
 *   <li>{@link io.avaje.webview.linux.Gtk4}: {@code libgtk-4}, for window creation, sizing,
 *       visibility, focus and the single-child widget model.
 *   <li>{@link io.avaje.webview.linux.WebKit6}: {@code libwebkitgtk-6.0} and {@code
 *       libjavascriptcoregtk-6.0}, for the web view itself, navigation, JavaScript evaluation,
 *       user content management and JSC value extraction.
 *   <li>{@link io.avaje.webview.linux.LinuxHelper}: dark mode, fullscreen and maximize, built on
 *       top of the GTK4 and GLib calls.
 * </ul>
 *
 * <p>Three shared libraries have to be present on the host, or class loading fails with an {@link
 * java.lang.UnsatisfiedLinkError}: {@code libgtk-4.so.1} (Debian/Ubuntu package {@code
 * libgtk-4-1}), {@code libwebkitgtk-6.0.so.4} ({@code libwebkitgtk-6.0-4}) and {@code
 * libjavascriptcoregtk-6.0.so.1} ({@code libjavascriptcoregtk-6.0-1}).
 */
package io.avaje.webview.linux;
