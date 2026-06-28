package io.avaje.webview;

import java.lang.foreign.MemorySegment;

/**
 * SPI for platform-specific {@link Webview} implementations discovered via {@link ServiceLoader}.
 *
 * <p>Implementations are registered in {@code META-INF/services/io.avaje.webview.WebviewProvider}
 * or via {@code module-info.java} {@code provides} declarations.
 */
public interface WebviewProvider {

  /**
   * Returns {@code true} if this provider supports the current operating system.
   * Checked before {@link #create} is called.
   */
  boolean isSupported();

  /**
   * Creates a new {@link Webview} instance.
   *
   * @param debug            {@code true} enables developer tools
   * @param width            initial window width in pixels
   * @param height           initial window height in pixels
   * @param windowPointer    native parent window pointer, or {@link MemorySegment#NULL} for a new window
   */
  Webview create(boolean debug, int width, int height, MemorySegment windowPointer);
}
