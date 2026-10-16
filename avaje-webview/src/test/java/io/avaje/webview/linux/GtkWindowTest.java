package io.avaje.webview.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.avaje.webview.Webview;

/**
 * Linux checks that need GTK calls the cross-platform tests can't make, such as driving the close
 * button through {@code gtk_window_close} or asking whether the window is showing.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GtkWindowTest {

  @Test
  void userCloseCanBeVetoedThenAllowed() {
    final var asked = new AtomicInteger();
    try (var w = Webview.builder().build()) {
      // The first close request is refused, which must leave the window open for the second.
      w.onCloseRequested(() -> asked.incrementAndGet() > 1);
      w.onPageLoaded(
          _ -> {
            Gtk4.gtkWindowClose(w.nativeWindowPointer());
            Gtk4.gtkWindowClose(w.nativeWindowPointer());
          });
      w.run();
    }
    assertEquals(2, asked.get());
  }

  @Test
  void failedFirstLoadStillShowsWindow() {
    final var visible = new AtomicBoolean();
    // Nothing listens on port 1, so the load fails straight away.
    try (var w = Webview.builder().navigate("http://127.0.0.1:1/").build()) {
      w.onPageLoaded(
          _ -> {
            visible.set(Gtk4.gtkWidgetGetVisible(w.nativeWindowPointer()));
            w.close();
          });
      w.run();
    }
    assertTrue(visible.get());
  }

  @Test
  void showOnFirstLoadFalseKeepsWindowHidden() {
    final var visible = new AtomicBoolean(true);
    try (var w = Webview.builder().showOnFirstLoad(false).html("<p>hi</p>").build()) {
      w.onPageLoaded(
          _ -> {
            visible.set(Gtk4.gtkWidgetGetVisible(w.nativeWindowPointer()));
            w.close();
          });
      w.run();
    }
    assertFalse(visible.get());
  }
}
