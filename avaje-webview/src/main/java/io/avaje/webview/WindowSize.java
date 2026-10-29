package io.avaje.webview;

/**
 * The size of a window's content area, measured the same way as {@link Webview#setSize(int, int)}.
 *
 * <p>Sizes are in logical pixels, so they stay the same on high-DPI screens: points on macOS and
 * 96 DPI pixels on Windows. They don't include the title bar or borders, except on Linux when GTK
 * draws the title bar itself.
 *
 * @param width content width
 * @param height content height
 */
public record WindowSize(int width, int height) {}
