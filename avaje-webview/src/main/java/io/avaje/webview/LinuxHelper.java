package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import module java.base;

final class LinuxHelper {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup
    GTK = loadLibrary("libgtk-4.so.1", "libgtk-4.so.0", "libgtk-4.so"),
    GOBJECT = loadLibrary("libgobject-2.0.so.0", "libgobject-2.0.so");

  private static final int TRUE = 1;
  private static final int FALSE = 0;

  private static final MethodHandle gtk_window_fullscreen;
  private static final MethodHandle gtk_window_maximize;
  private static final MethodHandle gtk_settings_get_default;
  private static final MethodHandle g_object_set;

  static {
    try {

      // void gtk_window_fullscreen(GtkWindow *window)
      gtk_window_fullscreen =
          GTK.find("gtk_window_fullscreen")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.ofVoid(ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("gtk_window_fullscreen not found"));

      // void gtk_window_maximize(GtkWindow *window)
      gtk_window_maximize =
          GTK.find("gtk_window_maximize")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.ofVoid(ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("gtk_window_maximize not found"));

      // GtkSettings* gtk_settings_get_default(void)
      gtk_settings_get_default =
          GTK.find("gtk_settings_get_default")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("gtk_settings_get_default not found"));
      
      // g_object_set
      g_object_set =
          GOBJECT.find("g_object_set")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
                  .appendArgumentLayouts(JAVA_INT, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("g_object_set not found"));

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static SymbolLookup loadLibrary(String... libraryNames) {

    for (String name : libraryNames) {
      try {
        return SymbolLookup.libraryLookup(name, Arena.global());
      } catch (IllegalArgumentException e) {
        // Library not found, try next name
      }
    }
    throw new UnsatisfiedLinkError(
        "Could not load any of the libraries: " + String.join(", ", libraryNames));
  }

  public static void fullscreen(Webview webview) {
    try {
      gtk_window_fullscreen.invoke(webview.nativeWindowPointer());
    } catch (Throwable e) {
      throw new RuntimeException("Failed to fullscreen window", e);
    }
  }

  public static void maximizeWindow(Webview webview) {
    try {
      gtk_window_maximize.invoke(webview.nativeWindowPointer());
    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }

  static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (Arena arena = Arena.ofConfined()) {

      MemorySegment settings = (MemorySegment) gtk_settings_get_default.invoke();

      if (settings.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Failed to get GTK settings");
      }

      // Use g_object_set with proper varargs termination
      MemorySegment propertyName =
          arena.allocateFrom("gtk-application-prefer-dark-theme", StandardCharsets.UTF_8);

      // g_object_set needs to be called with NULL terminator for __varargs__
      g_object_set.invoke(
          settings, propertyName, shouldBeDark ? TRUE : FALSE, MemorySegment.NULL);

    } catch (Throwable e) {
      throw new RuntimeException("Warning: Failed to set window appearance: " + e.getMessage(), e);
    }
  }
}
