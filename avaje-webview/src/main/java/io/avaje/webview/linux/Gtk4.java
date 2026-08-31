package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM bindings for GTK4 ({@code libgtk-4}).
 *
 * <p>GTK is not thread-safe, so every method here belongs on the thread that called {@link
 * #gtkInitCheck()}. Calls from anywhere else corrupt GTK state and show up later as intermittent
 * crashes. {@link GtkWebView} keeps to that by comparing against {@code gtkThread} before each call
 * and sending anything else through {@link GLib#gIdleAddFull}.
 */
final class Gtk4 {

  /**
   * {@code FunctionDescriptor} for the GTK {@code "destroy"} signal on {@code GtkWidget}: {@code
   * void(*)(GtkWidget* widget, gpointer user_data)}.
   */
  static final FunctionDescriptor DESTROY_SIGNAL_DESC = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /** Symbol lookup rooted in {@code libgtk-4.so.1} */
  private static final SymbolLookup LOOKUP =
      SymbolLookup.libraryLookup("libgtk-4.so.1", Arena.global());

  /**
   * {@code gtk_init_check() -> gboolean}
   *
   * <p>Initializes GTK and connects to the display server, X11 or Wayland. The {@code gboolean}
   * return is non-zero on success, zero when there is no usable display.
   */
  private static final MethodHandle GTK_INIT_CHECK =
      downcall("gtk_init_check", FunctionDescriptor.of(JAVA_INT));

  /**
   * {@code gtk_window_new() -> GtkWidget*}
   *
   * <p>Creates a new top-level window, returned as a {@code GtkWidget*}. It starts with a floating
   * GObject reference that GTK sinks once the window becomes a root widget, so unlike the WebView
   * child it is left to GTK rather than sunk here.
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
   * <p>Sets the size the window opens at. Not a minimum, the user can still resize below it.
   */
  private static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE =
      downcall(
          "gtk_window_set_default_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

  /**
   * {@code gtk_window_set_resizable(GtkWindow* window, gboolean resizable) -> void}
   *
   * <p>Controls whether the user can drag the window border to resize it.
   *
   * <p>Call this before {@link #GTK_WINDOW_SET_DEFAULT_SIZE} when fixing a window's size, as GTK
   * only takes the new default size once the resizable state has settled.
   */
  private static final MethodHandle GTK_WINDOW_SET_RESIZABLE =
      downcall("gtk_window_set_resizable", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_window_set_child(GtkWindow* window, GtkWidget* child) -> void}
   *
   * <p>Sets the single content widget of the window. A GTK4 window has exactly one direct child,
   * so a second call replaces the first. The window sinks the child's floating reference, which is
   * why {@link GLib#gObjectRefSink} has to run first for the caller to keep a reference of its own.
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
   * <p>Destroys the window outright, past the {@code "close-request"} veto. The {@code "destroy"}
   * signal is emitted synchronously, so {@code GtkWebView#onWindowDestroy} has already run and
   * decremented {@code openWindows} by the time this returns.
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
   * {@code gtk_window_unmaximize(GtkWindow* window) -> void}
   *
   * <p>Asks the window manager to restore the window from a maximized state.
   */
  private static final MethodHandle GTK_WINDOW_UNMAXIMIZE =
      downcall("gtk_window_unmaximize", FunctionDescriptor.ofVoid(ADDRESS));

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
   * {@code gtk_window_set_titlebar(GtkWindow* window, GtkWidget* titlebar) -> void}
   *
   * <p>Replaces the window's title bar widget. A zero-height {@code GtkBox} goes in here, which
   * leaves the window decorated but with nothing drawn where the title bar would be.
   */
  private static final MethodHandle GTK_WINDOW_SET_TITLEBAR =
      downcall("gtk_window_set_titlebar", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_box_new(GtkOrientation orientation, gint spacing) -> GtkWidget*}
   *
   * <p>Used only to build the zero-height placeholder titlebar for outline mode.
   */
  private static final MethodHandle GTK_BOX_NEW =
      downcall("gtk_box_new", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));

  /**
   * {@code gtk_native_get_surface(GtkNative* self) -> GdkSurface*}
   *
   * <p>Every realized top-level {@code GtkWindow} implements {@code GtkNative}, and this returns
   * its backing {@code GdkSurface}, which is also the {@code GdkToplevel} that {@link
   * #GDK_TOPLEVEL_BEGIN_MOVE} wants.
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
   * <p>Shows or hides a widget. GTK4 dropped {@code gtk_widget_show()} and {@code
   * gtk_widget_hide()} in favour of this.
   *
   * <p>Window creation shows both the WebKitWebView child and the GtkWindow itself. The child has
   * to be visible before the first draw pass, or the window comes up empty and grey.
   */
  private static final MethodHandle GTK_WIDGET_SET_VISIBLE =
      downcall("gtk_widget_set_visible", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

  /**
   * {@code gtk_widget_set_size_request(GtkWidget* widget, gint width, gint height) -> void}
   *
   * <p>Sets the minimum size of a widget.
   *
   * <p>Passing {@code -1} for either dimension means "no minimum" on that axis.
   */
  private static final MethodHandle GTK_WIDGET_SET_SIZE_REQUEST =
      downcall(
          "gtk_widget_set_size_request", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

  /**
   * {@code gtk_widget_grab_focus(GtkWidget* widget) -> gboolean}
   *
   * <p>Moves keyboard focus to the widget. Called on the WebKitWebView right after the window is
   * shown so shortcuts such as Ctrl+R and Ctrl+F work before the user has clicked into the page.
   *
   * <p>The {@code gboolean} return, non-zero when focus moved, is discarded as failing to focus is
   * harmless.
   */
  private static final MethodHandle GTK_WIDGET_GRAB_FOCUS =
      downcall("gtk_widget_grab_focus", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /**
   * {@code gtk_settings_get_default() -> GtkSettings*}
   *
   * <p>Returns the singleton {@code GtkSettings} object for the default display. Used to set {@code
   * gtk-application-prefer-dark-theme} when {@code setDarkAppearance(true)} is called.
   *
   * <p>A borrowed reference, so do NOT call {@code g_object_unref} on it. GTK owns the settings
   * object and keeps it for the life of the process.
   *
   * <p>Returns {@code NULL} before GTK is initialized, which is what the null-address guard in
   * {@link LinuxHelper#setWindowAppearance} is for.
   */
  private static final MethodHandle GTK_SETTINGS_GET_DEFAULT =
      downcall("gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code GTK_STYLE_PROVIDER_PRIORITY_APPLICATION}, the priority for app-level CSS so it
   * overrides the active theme's stylesheet but can still be overridden by user themes/settings.
   */
  private static final int GTK_STYLE_PROVIDER_PRIORITY_APPLICATION = 600;

  /**
   * {@code gdk_display_get_default() -> GdkDisplay*}
   *
   * <p>Returns the default {@code GdkDisplay} for the process; used as the target for a
   * process-wide CSS provider rather than requiring a realized widget to look one up.
   */
  private static final MethodHandle GDK_DISPLAY_GET_DEFAULT =
      downcall("gdk_display_get_default", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code gtk_widget_add_css_class(GtkWidget* widget, const char* css_class) -> void}
   *
   * <p>Adds a CSS style class to a widget so a targeted stylesheet rule (e.g. {@code
   * .webview-transparent}) can match only this widget instance rather than every window.
   */
  private static final MethodHandle GTK_WIDGET_ADD_CSS_CLASS =
      downcall("gtk_widget_add_css_class", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_css_provider_new() -> GtkCssProvider*}
   *
   * <p>Creates a new, empty CSS provider. Returns a full (non-floating) GObject reference.
   */
  private static final MethodHandle GTK_CSS_PROVIDER_NEW =
      downcall("gtk_css_provider_new", FunctionDescriptor.of(ADDRESS));

  /**
   * {@code gtk_css_provider_load_from_data(GtkCssProvider* provider, const char* data, gssize
   * length) -> void}
   *
   * <p>Parses {@code data} as CSS and loads it into {@code provider}.
   */
  private static final MethodHandle GTK_CSS_PROVIDER_LOAD_FROM_DATA =
      downcall(
          "gtk_css_provider_load_from_data",
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));

  /**
   * {@code gtk_style_context_add_provider_for_display(GdkDisplay* display, GtkStyleProvider*
   * provider, guint priority) -> void}
   *
   * <p>Registers {@code provider} for every widget on {@code display}. The display retains its own
   * reference, so the caller may drop its reference immediately after this call.
   */
  private static final MethodHandle GTK_STYLE_CONTEXT_ADD_PROVIDER_FOR_DISPLAY =
      downcall(
          "gtk_style_context_add_provider_for_display",
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));

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
   * <p>GTK's {@code gboolean} is a C {@code int}, hence the {@code 1}/{@code 0} conversion.
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
   * Asks the window manager to restore the window from a maximized state.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowUnmaximize(MemorySegment window) {
    try {
      GTK_WINDOW_UNMAXIMIZE.invokeExact(window);
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
   * Replaces {@code window}'s titlebar widget with a zero-height {@code GtkBox}, so the window
   * stays decorated (keeping the CSD shadow/rounded corners/resize border) but draws no visible
   * title bar.
   *
   * @param window a {@code GtkWindow*}
   */
  static void gtkWindowHideTitlebar(MemorySegment window) {
    try (var a = Arena.ofConfined()) {
      final var box =
          (MemorySegment) GTK_BOX_NEW.invokeExact(0 /* GTK_ORIENTATION_HORIZONTAL */, 0);
      GTK_WIDGET_ADD_CSS_CLASS.invokeExact(box, a.allocateFrom("webview-hidden-titlebar"));
      GTK_WINDOW_SET_TITLEBAR.invokeExact(window, box);

      final var provider = (MemorySegment) GTK_CSS_PROVIDER_NEW.invokeExact();
      final var css =
          a.allocateFrom(
              "box.webview-hidden-titlebar { min-height: 0; min-width: 0; padding: 0; margin:"
                  + " 0; border: none; box-shadow: none; background: transparent; }");
      GTK_CSS_PROVIDER_LOAD_FROM_DATA.invokeExact(provider, css, (long) -1);
      final var display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
      GTK_STYLE_CONTEXT_ADD_PROVIDER_FOR_DISPLAY.invokeExact(
          display, provider, GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
      GLib.gObjectUnref(provider);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Begins a native window-move grab on {@code window}'s toplevel surface, using the default seat's
   * current pointer position as the drag origin and {@code GDK_CURRENT_TIME} (0) as the timestamp,
   * since the triggering click arrived as a JS event rather than a live GDK event that could be
   * forwarded.
   *
   * @param window a realized {@code GtkWindow*}
   */
  static void gtkWindowBeginMoveDrag(MemorySegment window) {
    try (var a = Arena.ofConfined()) {
      final var surface = (MemorySegment) GTK_NATIVE_GET_SURFACE.invokeExact(window);
      if (surface.address() == 0L) {
        return;
      }
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
   * Shows or hides a widget.
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
   * @param widget a {@code GtkWidget*}, usually the WebKitWebView content widget
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
   * <p>The gboolean return, whether focus actually moved, is discarded as failing is harmless.
   *
   * @param widget a {@code GtkWidget*}, usually the WebKitWebView
   */
  static void gtkWidgetGrabFocus(MemorySegment widget) {
    try {
      // gboolean return, non-zero if focus moved. Nothing breaks when it does not.
      final var _ = (int) GTK_WIDGET_GRAB_FOCUS.invokeExact(widget);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the singleton {@code GtkSettings} object for the default display.
   *
   * <p>A borrowed reference, so do NOT unref the returned pointer. Returns {@code NULL} before GTK
   * is initialized.
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

  /**
   * Makes {@code window}'s background see-through so that whatever is behind it in the compositor
   * (desktop, other windows) shows through wherever neither the window chrome nor the page content
   * paints an opaque pixel.
   *
   * <p>Only the GTK-drawn background is affected. WebKit's own opaque base fill needs clearing
   * separately through {@link WebKit6#webkitWebViewSetBackgroundColor}, or the page keeps painting
   * a white rectangle over the now transparent window.
   *
   * <p>Needs a compositor that gives client windows an alpha channel. Wayland always does, X11 does
   * with a compositing manager such as picom running, and without one the transparent areas come
   * out opaque black.
   *
   * @param window a {@code GtkWindow*}, not yet required to be realized
   */
  static void gtkMakeWindowTransparent(MemorySegment window) {
    try (var a = Arena.ofConfined()) {
      GTK_WIDGET_ADD_CSS_CLASS.invokeExact(window, a.allocateFrom("webview-transparent"));
      final var provider = (MemorySegment) GTK_CSS_PROVIDER_NEW.invokeExact();
      final var css =
          a.allocateFrom("window.webview-transparent { background-color: rgba(0,0,0,0); }");
      GTK_CSS_PROVIDER_LOAD_FROM_DATA.invokeExact(provider, css, (long) -1);
      final var display = (MemorySegment) GDK_DISPLAY_GET_DEFAULT.invokeExact();
      GTK_STYLE_CONTEXT_ADD_PROVIDER_FOR_DISPLAY.invokeExact(
          display, provider, GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
      // The display took its own reference, so drop this one.
      GLib.gObjectUnref(provider);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private Gtk4() {}
}
