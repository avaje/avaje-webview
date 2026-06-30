/**
 * Linux webview implementation using GTK4 and WebKitGTK 6.0 via Panama FFM.
 *
 * <h2>Architecture</h2>
 *
 * <p>{@link io.avaje.webview.linux.GtkWebView} is the single public entry point. It creates a
 * {@code GtkWindow} containing a {@code WebKitWebView} widget and drives the GTK event loop via
 * {@code g_main_context_iteration}. The window and all GTK/WebKit state are owned by the
 * <em>GTK thread</em> — the thread that first constructed a {@code GtkWebView}.
 *
 * <h2>FFM binding classes</h2>
 *
 * <ul>
 *   <li>{@link io.avaje.webview.linux.GLib} — bindings for {@code libglib-2.0} and
 *       {@code libgobject-2.0}: main loop, idle sources, GObject reference counting, and GLib
 *       signal connectivity.
 *   <li>{@link io.avaje.webview.linux.Gtk4} — bindings for {@code libgtk-4}: window creation,
 *       sizing, visibility, focus, and the single-child widget model.
 *   <li>{@link io.avaje.webview.linux.WebKit6} — bindings for {@code libwebkitgtk-6.0} and
 *       {@code libjavascriptcoregtk-6.0}: web view creation, navigation, JavaScript evaluation,
 *       user content management, and JSC value extraction.
 *   <li>{@link io.avaje.webview.linux.LinuxHelper} — high-level helpers for dark mode, fullscreen,
 *       and maximize that compose the lower-level GTK4/GLib calls.
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <p>GTK is <strong>not thread-safe</strong>. Every call to GTK, GObject, or WebKitGTK must
 * originate on the GTK thread. Cross-thread work is dispatched via {@code g_idle_add_full} using
 * Panama upcall stubs, which drains a {@code ConcurrentLinkedQueue} on the GTK thread during the
 * next main-loop iteration.
 *
 * <h2>Runtime requirements</h2>
 *
 * <p>The following shared libraries must be installed on the host:
 * <ul>
 *   <li>{@code libgtk-4.so.1} (package {@code libgtk-4-1} on Debian/Ubuntu)</li>
 *   <li>{@code libwebkitgtk-6.0.so.4} (package {@code libwebkitgtk-6.0-4})</li>
 *   <li>{@code libjavascriptcoregtk-6.0.so.1} (package {@code libjavascriptcoregtk-6.0-1})</li>
 * </ul>
 * Missing libraries cause an {@link java.lang.UnsatisfiedLinkError} at class-load time.
 */
package io.avaje.webview.linux;
