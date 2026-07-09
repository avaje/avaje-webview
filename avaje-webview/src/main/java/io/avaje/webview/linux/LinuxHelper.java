package io.avaje.webview.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import io.avaje.webview.Webview;

/** GTK4-specific window operations helper for {@link GtkWebView}. */
final class LinuxHelper {

  private LinuxHelper() {}

  /** Requests the window manager to make the window fullscreen. */
  static void fullscreen(Webview webview) {
    Gtk4.gtkWindowFullscreen(webview.nativeWindowPointer());
  }

  /** Asks the window manager to minimize (iconify) the window. */
  static void minimizeWindow(Webview webview) {
    Gtk4.gtkWindowMinimize(webview.nativeWindowPointer());
  }

  /** Requests the window manager to maximize the window. */
  static void maximizeWindow(Webview webview) {
    Gtk4.gtkWindowMaximize(webview.nativeWindowPointer());
  }

  /** Begins a native window-move grab, as if the user had grabbed the title bar. */
  static void startWindowDrag(Webview webview) {
    Gtk4.gtkWindowBeginMoveDrag(webview.nativeWindowPointer());
  }

  /**
   * Applies a dark or light appearance to all GTK windows in this process.
   *
   * @param webview used only to satisfy the method signature; GTK settings are process-global
   * @param shouldBeDark {@code true} for dark theme, {@code false} for light theme
   */
  static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (var arena = Arena.ofConfined()) {
      final var settings = Gtk4.gtkSettingsGetDefault();
      if (settings.address() == 0L) throw new RuntimeException("Failed to get GTK settings");
      final var propertyName =
          arena.allocateFrom("gtk-application-prefer-dark-theme", StandardCharsets.UTF_8);
      GLib.gObjectSet(settings, propertyName, shouldBeDark ? 1 : 0, MemorySegment.NULL);
    }
  }
}
