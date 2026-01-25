package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

final class LinuxHelper {
  private static final Linker LINKER = Linker.nativeLinker();

  private static final MethodHandle gtk_window_fullscreen;
  private static final MethodHandle gtk_window_unfullscreen;
  private static final MethodHandle gtk_window_maximize;
  private static final MethodHandle gtk_window_unmaximize;
  private static final MethodHandle gtk_window_is_fullscreen;
  private static final MethodHandle gtk_window_is_maximized;

  // GTK Settings for theme
  private static final MethodHandle gtk_settings_get_default;
  private static final MethodHandle g_object_set;

  // GTK Window icon
  private static final MethodHandle gdk_pixbuf_new_from_file;
  private static final MethodHandle gtk_window_set_icon;

  static {
    try {
      // Try to load GTK 4 library (different naming on different distros)
      SymbolLookup gtkLookup;
      try {
        gtkLookup = SymbolLookup.libraryLookup("libgtk-4.so.1", Arena.global());
      } catch (Throwable _) {
        try {
          gtkLookup = SymbolLookup.libraryLookup("libgtk-4.so", Arena.global());
        } catch (Throwable _) {
          gtkLookup = SymbolLookup.libraryLookup("gtk-4", Arena.global());
        }
      }

      // Load GLib
      SymbolLookup glibLookup;
      try {
        glibLookup = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());
      } catch (Throwable _) {
        try {
          glibLookup = SymbolLookup.libraryLookup("libglib-2.0.so", Arena.global());
        } catch (Throwable _) {
          glibLookup = SymbolLookup.libraryLookup("glib-2.0", Arena.global());
        }
      }

      findFunction(
          gtkLookup, "gtk_window_set_decorated", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

      gtk_window_fullscreen =
          findFunction(gtkLookup, "gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_unfullscreen =
          findFunction(gtkLookup, "gtk_window_unfullscreen", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_maximize =
          findFunction(gtkLookup, "gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_unmaximize =
          findFunction(gtkLookup, "gtk_window_unmaximize", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_is_fullscreen =
          findFunction(
              gtkLookup, "gtk_window_is_fullscreen", FunctionDescriptor.of(JAVA_INT, ADDRESS));

      gtk_window_is_maximized =
          findFunction(
              gtkLookup, "gtk_window_is_maximized", FunctionDescriptor.of(JAVA_INT, ADDRESS));

      // gtkLookup Settings
      gtk_settings_get_default =
          findFunction(gtkLookup, "gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));

      // GObject property setter (variadic, we'll use a specific signature)
      g_object_set =
          findFunction(
              glibLookup,
              "g_object_set",
              FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

      findFunction(
          gtkLookup, "gtk_application_set_menubar", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      findFunction(glibLookup, "g_menu_new", FunctionDescriptor.of(ADDRESS));

      findFunction(
          glibLookup, "g_menu_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

      findFunction(
          glibLookup,
          "g_menu_append_submenu",
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

      findFunction(
          glibLookup, "g_simple_action_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

      findFunction(
          glibLookup, "g_action_map_add_action", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      findFunction(
          glibLookup,
          "g_signal_connect_data",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

      // Icon functions
      gdk_pixbuf_new_from_file =
          findFunction(
              gtkLookup,
              "gdk_pixbuf_new_from_file",
              FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

      gtk_window_set_icon =
          findFunction(
              gtkLookup, "gtk_window_set_icon", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      findFunction(glibLookup, "g_set_application_name", FunctionDescriptor.ofVoid(ADDRESS));

      findFunction(gtkLookup, "gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static MethodHandle findFunction(
      SymbolLookup lookup, String name, FunctionDescriptor desc) {
    return lookup
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc))
        .orElseThrow(() -> new UnsatisfiedLinkError(name + " not found"));
  }

  /**
   * Sets the GTK theme preference to light or dark mode. This sets the
   * gtk-application-prefer-dark-theme property.
   */
  public static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (var arena = Arena.ofConfined()) {
      var settings = (MemorySegment) gtk_settings_get_default.invoke();
      var propertyName = arena.allocateFrom("gtk-application-prefer-dark-theme");

      // g_object_set is variadic, so we pass: settings, property_name, value, NULL
      g_object_set.invoke(settings, propertyName, shouldBeDark ? 1 : 0, MemorySegment.NULL);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window appearance", e);
    }
  }

  /** Enters fullscreen mode. */
  public static void fullscreen(Webview webview) {
    try {
      var window = webview.nativeWindowPointer();

      // Check if already fullscreen
      int isFullscreen = (int) gtk_window_is_fullscreen.invoke(window);

      if (isFullscreen == 0) {
        gtk_window_fullscreen.invoke(window);
      } else {
        gtk_window_unfullscreen.invoke(window);
      }

    } catch (Throwable e) {
      throw new RuntimeException("Failed to toggle fullscreen", e);
    }
  }

  /** Maximizes the window to fill the screen without entering fullscreen mode. */
  public static void maximizeWindow(Webview webview) {
    try {
      var window = webview.nativeWindowPointer();

      // Check if already maximized
      int isMaximized = (int) gtk_window_is_maximized.invoke(window);

      if (isMaximized == 0) {
        gtk_window_maximize.invoke(window);
      } else {
        gtk_window_unmaximize.invoke(window);
      }

    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }

  /** Sets the window icon from a file path. */
  public static void setIcon(Webview webview, Path iconPath) {
    try (var arena = Arena.ofConfined()) {
      var window = webview.nativeWindowPointer();
      String absolutePath = iconPath.toAbsolutePath().toString();
      var pathString = arena.allocateFrom(absolutePath);

      // Load the pixbuf from file
      var error = arena.allocate(ADDRESS);
      var pixbuf = (MemorySegment) gdk_pixbuf_new_from_file.invoke(pathString, error);

      if (pixbuf.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Failed to load icon from path: " + absolutePath);
      }

      // Set the icon
      gtk_window_set_icon.invoke(window, pixbuf);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to set application icon", e);
    }
  }
}
