package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

final class Gtk4 {

  static final FunctionDescriptor DESTROY_SIGNAL_DESC = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP =
      SymbolLookup.libraryLookup("libgtk-4.so.1", Arena.global());

  static final MethodHandle GTK_INIT_CHECK =
      downcall("gtk_init_check", FunctionDescriptor.of(JAVA_INT));
  static final MethodHandle GTK_WINDOW_NEW =
      downcall("gtk_window_new", FunctionDescriptor.of(ADDRESS));
  static final MethodHandle GTK_WINDOW_SET_TITLE =
      downcall("gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  static final MethodHandle GTK_WINDOW_SET_DEFAULT_SIZE =
      downcall(
          "gtk_window_set_default_size", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
  static final MethodHandle GTK_WINDOW_SET_RESIZABLE =
      downcall("gtk_window_set_resizable", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  static final MethodHandle GTK_WINDOW_SET_CHILD =
      downcall("gtk_window_set_child", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  static final MethodHandle GTK_WINDOW_CLOSE =
      downcall("gtk_window_close", FunctionDescriptor.ofVoid(ADDRESS));
  // gtk_window_destroy tears down immediately (no "close-request" veto). GTK 4.0+.
  static final MethodHandle GTK_WINDOW_DESTROY =
      downcall("gtk_window_destroy", FunctionDescriptor.ofVoid(ADDRESS));
  static final MethodHandle GTK_WINDOW_MAXIMIZE =
      downcall("gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));
  static final MethodHandle GTK_WINDOW_FULLSCREEN =
      downcall("gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));
  static final MethodHandle GTK_WIDGET_SET_VISIBLE =
      downcall("gtk_widget_set_visible", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  static final MethodHandle GTK_WIDGET_SET_SIZE_REQUEST =
      downcall(
          "gtk_widget_set_size_request", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
  static final MethodHandle GTK_WIDGET_GRAB_FOCUS =
      downcall("gtk_widget_grab_focus", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  static final MethodHandle GTK_SETTINGS_GET_DEFAULT =
      downcall("gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));

  private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(sym)
            .orElseThrow(() -> new UnsatisfiedLinkError("GTK4 symbol not found: " + sym)),
        desc);
  }

  static boolean gtkInitCheck() {
    try {
      return (int) GTK_INIT_CHECK.invokeExact() != 0;
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static MemorySegment gtkWindowNew() {
    try {
      return (MemorySegment) GTK_WINDOW_NEW.invokeExact();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowSetTitle(MemorySegment window, MemorySegment title) {
    try {
      GTK_WINDOW_SET_TITLE.invokeExact(window, title);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowSetDefaultSize(MemorySegment window, int w, int h) {
    try {
      GTK_WINDOW_SET_DEFAULT_SIZE.invokeExact(window, w, h);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowSetResizable(MemorySegment window, boolean resizable) {
    try {
      GTK_WINDOW_SET_RESIZABLE.invokeExact(window, resizable ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowSetChild(MemorySegment window, MemorySegment child) {
    try {
      GTK_WINDOW_SET_CHILD.invokeExact(window, child);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowClose(MemorySegment window) {
    try {
      GTK_WINDOW_CLOSE.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowDestroy(MemorySegment window) {
    try {
      GTK_WINDOW_DESTROY.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowMaximize(MemorySegment window) {
    try {
      GTK_WINDOW_MAXIMIZE.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWindowFullscreen(MemorySegment window) {
    try {
      GTK_WINDOW_FULLSCREEN.invokeExact(window);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWidgetSetVisible(MemorySegment widget, boolean visible) {
    try {
      GTK_WIDGET_SET_VISIBLE.invokeExact(widget, visible ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWidgetSetSizeRequest(MemorySegment widget, int w, int h) {
    try {
      GTK_WIDGET_SET_SIZE_REQUEST.invokeExact(widget, w, h);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static void gtkWidgetGrabFocus(MemorySegment widget) {
    try {
      final var _ = (int) GTK_WIDGET_GRAB_FOCUS.invokeExact(widget);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  static MemorySegment gtkSettingsGetDefault() {
    try {
      return (MemorySegment) GTK_SETTINGS_GET_DEFAULT.invokeExact();
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private Gtk4() {}
}
