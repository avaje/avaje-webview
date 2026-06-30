package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM bindings for GTK4 ({@code libgtk-4}).
 *
 * <p><b>Single-threaded constraint:</b> GTK is not thread-safe. Every method in this class must be
 * called on the thread that invoked {@link #gtkInitCheck()} (the "GTK thread"). Calls from other
 * threads corrupt internal GTK state in ways that produce intermittent crashes. We enforce this in
 * {@link GtkWebView} by checking {@code Thread.currentThread() == gtkThread} before each GTK call
 * and routing cross-thread work through {@link GLib#gIdleAddFull}.
 */
final class Gtk4 {

  /**
   * {@code FunctionDescriptor} for the GTK {@code "destroy"} signal on {@code GtkWidget}. {@code
   * void(*)(GtkWidget* widget, gpointer user_data)}. Both
   */
  static final FunctionDescriptor DESTROY_SIGNAL_DESC = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /** Symbol lookup rooted in {@code libgtk-4.so.1} */
  private static final SymbolLookup LOOKUP =
      SymbolLookup.libraryLookup("libgtk-4.so.1", Arena.global());

  /**
   * {@code gtk_init_check() -> gboolean}
   *
   * <p>Initializes GTK and connects to the display server (X11 or Wayland). Returns {@code FALSE}
   *
   * <p>Return type is {@code gboolean} = C {@code int} = {@code JAVA_INT}. Non-zero means success.
   */
  static final MethodHandle GTK_INIT_CHECK =
      downcall("gtk_init_check", FunctionDescriptor.of(JAVA_INT));

  /**
   * {@code gtk_window_new() -> GtkWidget*}
   *
   * <p>Creates a new top-level window. Returns a {@code GtkWidget*} cast to {@code ADDRESS}. The
   * new window starts with a floating GObject reference; GTK's widget hierarchy management will
   * sink it when the window becomes a root widget. Since we are the root, we let GTK manage the
   * lifecycle and do not call {@code g_object_ref_sink} on the window (only on the WebView child).
   */
  static final MethodHandle GTK_WINDOW_NEW =
      downcall("gtk_window_new", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code gtk_window_set_title(GtkWindow* window, const gchar* title) -> void}
   *
   * <p>Sets the title bar text.
   */
  static final MethodHandle GTK_WINDOW_SET_TITLE =
      downcall("gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_window_set_default_size(GtkWindow* window, gint width, gint height) -> void}
   *
   * <p>Sets the window's <em>default</em> size
   */
  static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE =
      downcall(
          "gtk_window_set_default_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

  /**
   * {@code gtk_window_set_resizable(GtkWindow* window, gboolean resizable) -> void}
   *
   * <p>Controls whether the user can drag the window border to resize it.
   *
   * <p><b>Ordering constraint:</b> call this <em>before</em> {@link #GTK_WINDOW_SET_DEFAULT_SIZE}
   * when making a window fixed-size. GTK only honors the default size change when the resizable
   * state is already in its final value.
   */
  static final MethodHandle GTK_WINDOW_SET_RESIZABLE =
      downcall("gtk_window_set_resizable", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_window_set_child(GtkWindow* window, GtkWidget* child) -> void}
   *
   * <p>Sets the single content widget of the window. GTK4 windows have exactly one direct child,
   * calling this a second time replaces the previous child. The window takes ownership of the
   * child's floating reference (effectively calling {@code g_object_ref_sink}) so we must have
   * called {@link GLib#gObjectRefSink} beforehand to hold our own reference.
   */
  static final MethodHandle GTK_WINDOW_SET_CHILD =
      downcall("gtk_window_set_child", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_window_destroy(GtkWindow* window) -> void}
   *
   * <p>Destroys the window unconditionally, bypassing the {@code "close-request"} veto mechanism.
   * GTK emits the {@code "destroy"} signal synchronously during this call, so our {@code
   * GtkWebView#onWindowDestroy} upcall runs and decrements {@code openWindows} before {@code
   * gtkWindowDestroy} returns.
   *
   * <p>Added in GTK 4.0; replaces the old pattern of calling {@code gtk_widget_destroy()} on the
   * window. Prefer this over {@code gtk_window_close} for programmatic shutdown because it
   * guarantees teardown even if third-party code has connected a vetoing {@code close-request}
   * handler.
   */
  static final MethodHandle GTK_WINDOW_DESTROY =
      downcall("gtk_window_destroy", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_maximize(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to maximize the window. The request is asynchronous; the actual
   * resize happens on the next event loop iteration when the compositor responds.
   */
  static final MethodHandle GTK_WINDOW_MAXIMIZE =
      downcall("gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_fullscreen(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to switch the window to fullscreen mode. Like {@link
   * #GTK_WINDOW_MAXIMIZE}, the transition is compositor-driven and asynchronous.
   */
  static final MethodHandle GTK_WINDOW_FULLSCREEN =
      downcall("gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_widget_set_visible(GtkWidget* widget, gboolean visible) -> void}
   *
   * <p>Shows or hides a widget. GTK4 removed the convenience {@code gtk_widget_show()} and {@code
   * gtk_widget_hide()} functions; {@code gtk_widget_set_visible} with {@code TRUE/FALSE} is the
   * canonical GTK4 replacement.
   *
   * <p>We call this with {@code 1} (visible) on both the WebKitWebView child and the GtkWindow
   * itself during window creation. The widget must be visible before the window's first draw pass;
   * failing to show the child results in an empty grey window.
   *
   * <p>{@code gboolean} = C {@code int} = {@code JAVA_INT}.
   */
  static final MethodHandle GTK_WIDGET_SET_VISIBLE =
      downcall("gtk_widget_set_visible", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_widget_set_size_request(GtkWidget* widget, gint width, gint height) -> void}
   *
   * <p>Sets the minimum size of a widget. GTK4 removed {@code gtk_window_set_geometry_hints()}
   * which was the GTK3 API for constraining minimum window dimensions. The GTK4 replacement is to
   * call {@code gtk_widget_set_size_request} on the <em>content widget</em> (here the
   * WebKitWebView), which propagates the constraint upward to the window during layout.
   *
   * <p>Passing {@code -1} for either dimension means "no minimum" on that axis.
   */
  static final MethodHandle GTK_WIDGET_SET_SIZE_REQUEST =
      downcall(
          "gtk_widget_set_size_request", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

  /**
   * {@code gtk_widget_grab_focus(GtkWidget* widget) -> gboolean}
   *
   * <p>Moves keyboard focus to the widget. We call this on the WebKitWebView immediately after
   * showing the window so that keyboard shortcuts (Ctrl+R, Ctrl+F, etc.) work without the user
   * having to click inside the web content first.
   *
   * <p>Returns {@code gboolean} (non-zero if focus was successfully moved), but we discard the
   * return value — focus failing is non-fatal; the user can still click to focus.
   */
  static final MethodHandle GTK_WIDGET_GRAB_FOCUS =
      downcall("gtk_widget_grab_focus", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /**
   * {@code gtk_settings_get_default() -> GtkSettings*}
   *
   * <p>Returns the singleton {@code GtkSettings} object for the default display. Used to set {@code
   * gtk-application-prefer-dark-theme} when {@code setDarkAppearance(true)} is called.
   *
   * <p><b>Reference semantics:</b> this is a <em>borrowed</em> reference — do NOT call {@code
   * g_object_unref} on it. The settings object is owned by GTK and lives for the process lifetime.
   *
   * <p>Returns {@code NULL} if GTK has not been initialized, hence the null-address guard in {@link
   * LinuxHelper#setWindowAppearance}.
   */
  static final MethodHandle GTK_SETTINGS_GET_DEFAULT =
      downcall("gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));

  private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(sym)
            .orElseThrow(() -> new UnsatisfiedLinkError("GTK4 symbol not found: " + sym)),
        desc);
  }

  /**
   * Initializes GTK and connects to the display, returning {@code false} if no display is
   * available.
   *
   * <p>Unlike {@code gtk_init()}, this variant does not abort the process on failure, so callers
   * can throw a meaningful exception.
   */
  static boolean gtkInitCheck() {
    try {
      return (int) GTK_INIT_CHECK.invokeExact() != 0;
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Creates a new top-level {@code GtkWindow}.
   *
   * @return a {@code GtkWidget*} cast to {@link MemorySegment}; starts with a floating reference
   */
  static MemorySegment gtkWindowNew() {
    try {
      return (MemorySegment) GTK_WINDOW_NEW.invokeExact();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets the title bar text of a window.
   *
   * @param window a {@code GtkWindow*}
   * @param title a null-terminated UTF-8 C string; GTK copies it so the buffer may be freed
   *     immediately
   */
  static void gtkWindowSetTitle(MemorySegment window, MemorySegment title) {
    try {
      GTK_WINDOW_SET_TITLE.invokeExact(window, title);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets the default (initial) size of a window. Does not enforce a minimum; the user can resize
   * below this value.
   *
   * @param window a {@code GtkWindow*}
   * @param w default width in device pixels
   * @param h default height in device pixels
   */
  static void gtkWindowSetDefaultSize(MemorySegment window, int w, int h) {
    try {
      GTK_WINDOW_SET_DEFAULT_SIZE.invokeExact(window, w, h);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables user resizing of the window.
   *
   * <p>GTK's {@code gboolean} is a C {@code int}, so we convert the Java {@code boolean} to {@code
   * 1}/{@code 0} before calling the native function.
   *
   * @param window a {@code GtkWindow*}
   * @param resizable {@code true} to allow user resizing; {@code false} to fix the size
   */
  static void gtkWindowSetResizable(MemorySegment window, boolean resizable) {
    try {
      GTK_WINDOW_SET_RESIZABLE.invokeExact(window, resizable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets the single content widget of the window.
   *
   * @param window a {@code GtkWindow*}
   * @param child the widget to embed; replaces any previous child
   */
  static void gtkWindowSetChild(MemorySegment window, MemorySegment child) {
    try {
      GTK_WINDOW_SET_CHILD.invokeExact(window, child);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Destroys the window immediately.
   *
   * <p>Emits the {@code "destroy"} signal synchronously, so {@code GtkWebView#onWindowDestroy} runs
   * before this method returns.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowDestroy(MemorySegment window) {
    try {
      GTK_WINDOW_DESTROY.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Asks the window manager to maximize the window.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowMaximize(MemorySegment window) {
    try {
      GTK_WINDOW_MAXIMIZE.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Asks the window manager to switch the window to fullscreen mode.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowFullscreen(MemorySegment window) {
    try {
      GTK_WINDOW_FULLSCREEN.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Shows or hides a widget. GTK's {@code gboolean} is a C {@code int}; we convert.
   *
   * @param widget a {@code GtkWidget*}
   * @param visible {@code true} to show; {@code false} to hide
   */
  static void gtkWidgetSetVisible(MemorySegment widget, boolean visible) {
    try {
      GTK_WIDGET_SET_VISIBLE.invokeExact(widget, visible ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets the minimum size of a widget, constraining the parent window's minimum dimensions.
   *
   * @param widget a {@code GtkWidget*} — typically the WebKitWebView content widget
   * @param w minimum width in device pixels; {@code -1} for no minimum
   * @param h minimum height in device pixels; {@code -1} for no minimum
   */
  static void gtkWidgetSetSizeRequest(MemorySegment widget, int w, int h) {
    try {
      GTK_WIDGET_SET_SIZE_REQUEST.invokeExact(widget, w, h);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Moves keyboard focus to the widget so shortcuts work without a user click.
   *
   * <p>Return value (whether focus moved successfully) is discarded — focus failure is non-fatal.
   *
   * @param widget a {@code GtkWidget*} — typically the WebKitWebView
   */
  static void gtkWidgetGrabFocus(MemorySegment widget) {
    try {
      // Return value is gboolean: non-zero = focus moved. Discarded — non-fatal if focus fails.
      final var _ = (int) GTK_WIDGET_GRAB_FOCUS.invokeExact(widget);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the singleton {@code GtkSettings} object for the default display.
   *
   * <p>This is a borrowed reference — do NOT unref the returned pointer. Returns {@code NULL} if
   * GTK is not initialized.
   *
   * @return a {@code GtkSettings*} borrowed reference
   */
  static MemorySegment gtkSettingsGetDefault() {
    try {
      return (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private Gtk4() {}
}
