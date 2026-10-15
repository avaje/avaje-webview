package io.avaje.webview.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.avaje.webview.Webview;

/**
 * Drives the close button through {@code gtk_window_close}, which raises "close-request" the way a
 * real click does, something the cross-platform tests have no way to fake.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GtkCloseRequestTest {

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
}
