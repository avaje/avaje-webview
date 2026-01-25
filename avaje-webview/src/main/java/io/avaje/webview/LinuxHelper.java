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

  // Load GTK 4 and GLib libraries
  private static final SymbolLookup GTK;
  private static final SymbolLookup GLIB;

  // GTK Window and Application handles
  private static final MethodHandle gtk_window_set_decorated;
  private static final MethodHandle gtk_window_fullscreen;
  private static final MethodHandle gtk_window_unfullscreen;
  private static final MethodHandle gtk_window_maximize;
  private static final MethodHandle gtk_window_unmaximize;
  private static final MethodHandle gtk_window_is_fullscreen;
  private static final MethodHandle gtk_window_is_maximized;

  // GTK Settings for theme
  private static final MethodHandle gtk_settings_get_default;
  private static final MethodHandle g_object_set;

  // GTK Application and Menu
  private static final MethodHandle gtk_application_set_menubar;
  private static final MethodHandle g_menu_new;
  private static final MethodHandle g_menu_append;
  private static final MethodHandle g_menu_append_submenu;
  private static final MethodHandle g_simple_action_new;
  private static final MethodHandle g_action_map_add_action;
  private static final MethodHandle g_signal_connect_data;

  // GTK Window icon
  private static final MethodHandle gdk_pixbuf_new_from_file;
  private static final MethodHandle gtk_window_set_icon;

  // GTK Application name
  private static final MethodHandle g_set_application_name;
  private static final MethodHandle gtk_window_set_title;

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
      GTK = gtkLookup;

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
      GLIB = glibLookup;

      // GTK Window functions
      gtk_window_set_decorated =
          findFunction(
              GTK, "gtk_window_set_decorated", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

      gtk_window_fullscreen =
          findFunction(GTK, "gtk_window_fullscreen", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_unfullscreen =
          findFunction(GTK, "gtk_window_unfullscreen", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_maximize =
          findFunction(GTK, "gtk_window_maximize", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_unmaximize =
          findFunction(GTK, "gtk_window_unmaximize", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_is_fullscreen =
          findFunction(GTK, "gtk_window_is_fullscreen", FunctionDescriptor.of(JAVA_INT, ADDRESS));

      gtk_window_is_maximized =
          findFunction(GTK, "gtk_window_is_maximized", FunctionDescriptor.of(JAVA_INT, ADDRESS));

      // GTK Settings
      gtk_settings_get_default =
          findFunction(GTK, "gtk_settings_get_default", FunctionDescriptor.of(ADDRESS));

      // GObject property setter (variadic, we'll use a specific signature)
      g_object_set =
          findFunction(
              GLIB, "g_object_set", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

      // GTK Application
      gtk_application_set_menubar =
          findFunction(
              GTK, "gtk_application_set_menubar", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      // GMenu
      g_menu_new = findFunction(GLIB, "g_menu_new", FunctionDescriptor.of(ADDRESS));

      g_menu_append =
          findFunction(GLIB, "g_menu_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

      g_menu_append_submenu =
          findFunction(
              GLIB, "g_menu_append_submenu", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

      // GAction
      g_simple_action_new =
          findFunction(
              GLIB, "g_simple_action_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

      g_action_map_add_action =
          findFunction(
              GLIB, "g_action_map_add_action", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      g_signal_connect_data =
          findFunction(
              GLIB,
              "g_signal_connect_data",
              FunctionDescriptor.of(
                  JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

      // Icon functions
      gdk_pixbuf_new_from_file =
          findFunction(
              GTK, "gdk_pixbuf_new_from_file", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

      gtk_window_set_icon =
          findFunction(GTK, "gtk_window_set_icon", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

      // Application name
      g_set_application_name =
          findFunction(GLIB, "g_set_application_name", FunctionDescriptor.ofVoid(ADDRESS));

      gtk_window_set_title =
          findFunction(GTK, "gtk_window_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

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

  /**
   * Creates and attaches a standard application menu with File and Edit menus. GTK 4 uses GMenu for
   * application menus.
   */
  public static void createMenus(MemorySegment application) {
    try (var arena = Arena.ofConfined()) {

      var menubar = (MemorySegment) g_menu_new.invoke();

      // Create File menu
      var fileMenu = (MemorySegment) g_menu_new.invoke();

      // Add "Quit" action
      var quitLabel = arena.allocateFrom("Quit");
      var quitAction = arena.allocateFrom("app.quit");
      g_menu_append.invoke(fileMenu, quitLabel, quitAction);

      // Add File submenu to menubar
      var fileLabel = arena.allocateFrom("File");
      g_menu_append_submenu.invoke(menubar, fileLabel, fileMenu);

      // Create Edit menu
      var editMenu = (MemorySegment) g_menu_new.invoke();

      var cutLabel = arena.allocateFrom("Cut");
      var cutAction = arena.allocateFrom("app.cut");
      g_menu_append.invoke(editMenu, cutLabel, cutAction);

      var copyLabel = arena.allocateFrom("Copy");
      var copyAction = arena.allocateFrom("app.copy");
      g_menu_append.invoke(editMenu, copyLabel, copyAction);

      var pasteLabel = arena.allocateFrom("Paste");
      var pasteAction = arena.allocateFrom("app.paste");
      g_menu_append.invoke(editMenu, pasteLabel, pasteAction);

      var selectAllLabel = arena.allocateFrom("Select All");
      var selectAllAction = arena.allocateFrom("app.select-all");
      g_menu_append.invoke(editMenu, selectAllLabel, selectAllAction);

      // Add Edit submenu to menubar
      var editLabel = arena.allocateFrom("Edit");
      g_menu_append_submenu.invoke(menubar, editLabel, editMenu);

      // Set the menubar on the application
      gtk_application_set_menubar.invoke(application, menubar);

      // Register actions (these would need actual implementations)
      registerAction(application, "quit", null);
      registerAction(application, "cut", null);
      registerAction(application, "copy", null);
      registerAction(application, "paste", null);
      registerAction(application, "select-all", null);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to create menus", e);
    }
  }

  /** Registers a simple action with the application. */
  private static void registerAction(
      MemorySegment application, String name, MemorySegment callback) {
    try (var arena = Arena.ofConfined()) {
      var actionName = arena.allocateFrom(name);
      var action = (MemorySegment) g_simple_action_new.invoke(actionName, MemorySegment.NULL);

      // Connect callback if provided
      if (callback != null && !callback.equals(MemorySegment.NULL)) {
        var signal = arena.allocateFrom("activate");
        g_signal_connect_data.invoke(
            action, signal, callback, MemorySegment.NULL, MemorySegment.NULL, 0);
      }

      g_action_map_add_action.invoke(application, action);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to register action: " + name, e);
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

  /** Sets the application name and updates the window title. */
  public static void setApplicationName(Webview webview, String name) {
    try (var arena = Arena.ofConfined()) {
      var nameString = arena.allocateFrom(name);

      // Set the application name globally
      g_set_application_name.invoke(nameString);

      // Update window title
      var window = webview.nativeWindowPointer();
      gtk_window_set_title.invoke(window, nameString);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to set application name", e);
    }
  }
}
