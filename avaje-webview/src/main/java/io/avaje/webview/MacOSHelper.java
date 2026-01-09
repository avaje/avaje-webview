package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import module java.base;

final class MacOSHelper {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup OBJC =
      SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());
  // Objective-C runtime functions
  private static final MethodHandle objc_getClass;
  private static final MethodHandle sel_registerName;
  private static final MethodHandle objc_msgSend;

  static {
    try {
      // id objc_getClass(const char *name)
      objc_getClass =
          OBJC.find("objc_getClass")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_getClass not found"));

      // SEL sel_registerName(const char *str)
      sel_registerName =
          OBJC.find("sel_registerName")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("sel_registerName not found"));

      // id objc_msgSend(id self, SEL op, ...)
      objc_msgSend =
          OBJC.find("objc_msgSend")
              .map(
                  addr ->
                      LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend not found"));

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // Helper to send messages with one pointer argument
  private static MemorySegment msgSend(MemorySegment receiver, String selector, MemorySegment arg)
      throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment sel = (MemorySegment) sel_registerName.invoke(arena.allocateFrom(selector));

      return (MemorySegment) objc_msgSend.invoke(receiver, sel, arg);
    }
  }

  /**
   * Sets the window appearance to light or dark mode. On macOS, this uses NSAppearance to set the
   * window's appearance.
   */
  public static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();

      // Get NSAppearance class
      MemorySegment nsAppearanceClass =
          (MemorySegment) objc_getClass.invoke(arena.allocateFrom("NSAppearance"));

      // Create appearance name string
      String appearanceName = shouldBeDark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";

      // Get the appearance: [NSAppearance appearanceNamed:@"..."]
      MemorySegment nsStringClass =
          (MemorySegment) objc_getClass.invoke(arena.allocateFrom("NSString"));
      MemorySegment appearanceString =
          msgSend(nsStringClass, "stringWithUTF8String:", arena.allocateFrom(appearanceName));
      MemorySegment appearance = msgSend(nsAppearanceClass, "appearanceNamed:", appearanceString);

      // Set the window's appearance: [window setAppearance:appearance]
      msgSend(nsWindow, "setAppearance:", appearance);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window appearance", e);
    }
  }

  /** Enters native fullscreen mode using macOS's built-in fullscreen API. */
  public static void fullscreen(Webview webview) {
    try {
      MemorySegment nsWindow = webview.nativeWindowPointer();

      // Toggle fullscreen: [window toggleFullScreen:nil]
      msgSend(nsWindow, "toggleFullScreen:", MemorySegment.NULL);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to fullscreen window", e);
    }
  }

  /** Maximizes the window to fill the screen without entering fullscreen mode. */
  public static void maximizeWindow(Webview webview) {
    try {
      MemorySegment nsWindow = webview.nativeWindowPointer();

      // Zoom the window: [window zoom:nil]
      msgSend(nsWindow, "zoom:", MemorySegment.NULL);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }
}
