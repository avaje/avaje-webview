package io.avaje.webview.linux;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;

import io.avaje.webview.Webview;

/**
 * GTK4-specific window operations helper for {@link GtkWebView}.
 *
 * <p>Groups three categories of operations:
 *
 * <ul>
 *   <li><b>Dark mode</b> — applied via the process-wide {@code GtkSettings} GObject property {@code
 *       gtk-application-prefer-dark-theme}. GTK4 does not expose a per-window appearance API; all
 *       windows in the process follow the same setting.
 *   <li><b>Maximize / fullscreen</b> — delegated to the window manager via GTK4 window hints. These
 *       are asynchronous requests: GTK sends a WM hint and the compositor/WM honors it at its
 *       discretion; the window state change is not immediate.
 *   <li><b>Icon</b> — intentionally unimplemented. GTK4 removed {@code gtk_window_set_icon} and the
 *       file-path–based icon API; app icons are now set through the {@code .desktop} file and the
 *       icon theme. {@code gtk_window_set_icon_name()} works with theme icon names, not arbitrary
 *       file paths.
 * </ul>
 */
final class LinuxHelper {

  private LinuxHelper() {}

  /** Requests the window manager to make the window fullscreen. */
  static void fullscreen(Webview webview) {
    Gtk4.gtkWindowFullscreen(webview.nativeWindowPointer());
  }

  /** Requests the window manager to maximize the window. */
  static void maximizeWindow(Webview webview) {
    Gtk4.gtkWindowMaximize(webview.nativeWindowPointer());
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
      GLib.gObjectSet(
          settings, propertyName, shouldBeDark ? 1 : 0, java.lang.foreign.MemorySegment.NULL);
    }
  }
}
