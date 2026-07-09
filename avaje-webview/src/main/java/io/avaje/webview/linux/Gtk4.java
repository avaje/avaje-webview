package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
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
  private static final MethodHandle GTK_INIT_CHECK =
      downcall("gtk_init_check", FunctionDescriptor.of(JAVA_INT));

  /**
   * {@code gtk_window_new() -> GtkWidget*}
   *
   * <p>Creates a new top-level window. Returns a {@code GtkWidget*} cast to {@code ADDRESS}. The
   * new window starts with a floating GObject reference; GTK's widget hierarchy management will
   * sink it when the window becomes a root widget. Since we are the root, we let GTK manage the
   * lifecycle and do not call {@code g_object_ref_sink} on the window (only on the WebView child).
   */
  private static final MethodHandle GTK_WINDOW_NEW =
      downcall("gtk_window_new", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code gtk_window_set_title(GtkWindow* window, const gchar* title) -> void}
   *
   * <p>Sets the title bar text.
   */
  private static final MethodHandle GTK_WINDOW_SET_TITLE =
      downcall("gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_window_set_default_size(GtkWindow* window, gint width, gint height) -> void}
   *
   * <p>Sets the window's <em>default</em> size
   */
  private static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE =
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
  private static final MethodHandle GTK_WINDOW_SET_RESIZABLE =
      downcall("gtk_window_set_resizable", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_window_set_child(GtkWindow* window, GtkWidget* child) -> void}
   *
   * <p>Sets the single content widget of the window. GTK4 windows have exactly one direct child,
   * calling this a second time replaces the previous child. The window takes ownership of the
   * child's floating reference (effectively calling {@code g_object_ref_sink}) so we must have
   * called {@link GLib#gObjectRefSink} beforehand to hold our own reference.
   */
  private static final MethodHandle GTK_WINDOW_SET_CHILD =
      downcall("gtk_window_set_child", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_window_set_transient_for(GtkWindow* window, GtkWindow* parent) -> void}
   *
   * <p>Marks {@code window} as logically attached to {@code parent}: the window manager keeps it
   * stacked above the parent and typically minimizes/restores it together with the parent.
   */
  private static final MethodHandle GTK_WINDOW_SET_TRANSIENT_FOR =
      downcall("gtk_window_set_transient_for", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_window_set_modal(GtkWindow* window, gboolean modal) -> void}
   *
   * <p>Combined with {@link #GTK_WINDOW_SET_TRANSIENT_FOR}, tells the window manager this is a
   * modal dialog relative to its transient parent.
   */
  private static final MethodHandle GTK_WINDOW_SET_MODAL =
      downcall("gtk_window_set_modal", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_widget_set_sensitive(GtkWidget* widget, gboolean sensitive) -> void}
   *
   * <p>Enables or disables input to every widget in the tree rooted at {@code widget}.
   */
  private static final MethodHandle GTK_WIDGET_SET_SENSITIVE =
      downcall("gtk_widget_set_sensitive", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_window_destroy(GtkWindow* window) -> void}
   *
   * <p>Destroys the window unconditionally, bypassing the {@code "close-request"} veto mechanism.
   * GTK emits the {@code "destroy"} signal synchronously during this call, so our {@code
   * GtkWebView#onWindowDestroy} upcall runs and decrements {@code openWindows} before {@code
   * gtkWindowDestroy} returns.
   */
  private static final MethodHandle GTK_WINDOW_DESTROY =
      downcall("gtk_window_destroy", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_minimize(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to minimize the window to the taskbar.
   */
  private static final MethodHandle GTK_WINDOW_MINIMIZE =
      downcall("gtk_window_minimize", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_maximize(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to maximize the window.
   */
  private static final MethodHandle GTK_WINDOW_MAXIMIZE =
      downcall("gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_fullscreen(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to switch the window to fullscreen mode.
   */
  private static final MethodHandle GTK_WINDOW_FULLSCREEN =
      downcall("gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code gtk_window_set_decorated(GtkWindow* window, gboolean setting) -> void}
   *
   * <p>Controls whether the window manager draws native decorations (title bar, borders,
   * minimize/maximize/close buttons) around the window.
   */
  private static final MethodHandle GTK_WINDOW_SET_DECORATED =
      downcall("gtk_window_set_decorated", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_native_get_surface(GtkNative* self) -> GdkSurface*}
   *
   * <p>Every realized top-level {@code GtkWindow} implements {@code GtkNative}; this returns its
   * backing {@code GdkSurface}, which also implements the {@code GdkToplevel} interface used by
   * {@link #GDK_TOPLEVEL_BEGIN_MOVE}.
   */
  private static final MethodHandle GTK_NATIVE_GET_SURFACE =
      downcall("gtk_native_get_surface", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /** {@code gtk_widget_get_display(GtkWidget* widget) -> GdkDisplay*} */
  private static final MethodHandle GTK_WIDGET_GET_DISPLAY =
      downcall("gtk_widget_get_display", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /** {@code gdk_display_get_default_seat(GdkDisplay* display) -> GdkSeat*} */
  private static final MethodHandle GDK_DISPLAY_GET_DEFAULT_SEAT =
      downcall("gdk_display_get_default_seat", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /** {@code gdk_seat_get_pointer(GdkSeat* seat) -> GdkDevice*} */
  private static final MethodHandle GDK_SEAT_GET_POINTER =
      downcall("gdk_seat_get_pointer", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code gdk_surface_get_device_position(GdkSurface*, GdkDevice*, double* x, double* y,
   * GdkModifierType* mask) -> gboolean}
   */
  private static final MethodHandle GDK_SURFACE_GET_DEVICE_POSITION =
      downcall(
          "gdk_surface_get_device_position",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code gdk_toplevel_begin_move(GdkToplevel*, GdkDevice*, int button, double x, double y,
   * guint32 timestamp) -> void}
   *
   * <p>Asks the window manager/compositor to begin an interactive window-move grab, as if the user
   * had pressed {@code button} at surface position {@code (x, y)} and started dragging.
   */
  private static final MethodHandle GDK_TOPLEVEL_BEGIN_MOVE =
      downcall(
          "gdk_toplevel_begin_move",
          FunctionDescriptor.ofVoid(
              ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT));

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
  private static final MethodHandle GTK_WIDGET_SET_VISIBLE =
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
  private static final MethodHandle GTK_WIDGET_SET_SIZE_REQUEST =
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
  private static final MethodHandle GTK_WIDGET_GRAB_FOCUS =
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
  private static final MethodHandle GTK_SETTINGS_GET_DEFAULT =
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
   * Marks {@code window} as transient for {@code parent} so the window manager keeps it attached
   * (stacked above, minimized/restored together).
   *
   * @param window a {@code GtkWindow*}
   * @param parent a {@code GtkWindow*}
   */
  static void gtkWindowSetTransientFor(MemorySegment window, MemorySegment parent) {
    try {
      GTK_WINDOW_SET_TRANSIENT_FOR.invokeExact(window, parent);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Marks {@code window} as a modal dialog relative to its transient parent.
   *
   * @param window a {@code GtkWindow*}
   * @param modal {@code true} to mark modal
   */
  static void gtkWindowSetModal(MemorySegment window, boolean modal) {
    try {
      GTK_WINDOW_SET_MODAL.invokeExact(window, modal ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Enables or disables input to {@code widget} and its descendants.
   *
   * @param widget a {@code GtkWidget*}
   * @param sensitive {@code false} blocks input (clicks are ignored) until re-enabled
   */
  static void gtkWidgetSetSensitive(MemorySegment widget, boolean sensitive) {
    try {
      GTK_WIDGET_SET_SENSITIVE.invokeExact(widget, sensitive ? 1 : 0);
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
   * Asks the window manager to minimize (iconify) the window to the taskbar.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowMinimize(MemorySegment window) {
    try {
      GTK_WINDOW_MINIMIZE.invokeExact(window);
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
   * Shows or hides native window decorations (title bar, borders, min/max/close buttons).
   *
   * @param window a {@code GtkWindow*}
   * @param decorated {@code false} to remove native decorations
   */
  static void gtkWindowSetDecorated(MemorySegment window, boolean decorated) {
    try {
      GTK_WINDOW_SET_DECORATED.invokeExact(window, decorated ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Begins a native window-move grab on {@code window}'s toplevel surface, using the default seat's
   * current pointer position as the drag origin and {@code GDK_CURRENT_TIME} (0) as the timestamp,
   * since the triggering click was consumed by an unrelated JS event rather than a live GDK event
   * we can forward.
   *
   * @param window a realized {@code GtkWindow*}
   */
  static void gtkWindowBeginMoveDrag(MemorySegment window) {
    try (var a = Arena.ofConfined()) {
      final var surface = (MemorySegment) GTK_NATIVE_GET_SURFACE.invokeExact(window);
      if (surface.address() == 0L) return;
      final var display = (MemorySegment) GTK_WIDGET_GET_DISPLAY.invokeExact(window);
      final var seat = (MemorySegment) GDK_DISPLAY_GET_DEFAULT_SEAT.invokeExact(display);
      final var pointer = (MemorySegment) GDK_SEAT_GET_POINTER.invokeExact(seat);
      final var x = a.allocate(JAVA_DOUBLE);
      final var y = a.allocate(JAVA_DOUBLE);
      final var mask = a.allocate(JAVA_INT);
      final var _ = (int) GDK_SURFACE_GET_DEVICE_POSITION.invokeExact(surface, pointer, x, y, mask);
      GDK_TOPLEVEL_BEGIN_MOVE.invokeExact(
          surface,
          pointer,
          1 /* GDK_BUTTON_PRIMARY */,
          x.get(JAVA_DOUBLE, 0),
          y.get(JAVA_DOUBLE, 0),
          0 /* GDK_CURRENT_TIME */);
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
