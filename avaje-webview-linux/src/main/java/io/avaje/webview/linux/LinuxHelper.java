package io.avaje.webview.linux;

import io.avaje.webview.Webview;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;

final class LinuxHelper {

  private LinuxHelper() {}

  static void fullscreen(Webview webview) {
    Gtk4.gtkWindowFullscreen(webview.nativeWindowPointer());
  }

  static void maximizeWindow(Webview webview) {
    Gtk4.gtkWindowMaximize(webview.nativeWindowPointer());
  }

  static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (Arena arena = Arena.ofConfined()) {
      var settings = Gtk4.gtkSettingsGetDefault();
      if (settings.address() == 0L) throw new RuntimeException("Failed to get GTK settings");
      var propertyName = arena.allocateFrom("gtk-application-prefer-dark-theme", StandardCharsets.UTF_8);
      GLib.gObjectSet(settings, propertyName, shouldBeDark ? 1 : 0, java.lang.foreign.MemorySegment.NULL);
    }
  }
}
