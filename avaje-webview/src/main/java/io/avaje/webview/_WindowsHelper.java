package io.avaje.webview;

import module java.base;

import static java.lang.foreign.ValueLayout.*;

final class _WindowsHelper {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
  private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

  private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
  private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
  private static final int FALSE = 0;

  private static final MethodHandle DwmSetWindowAttribute;
  private static final MethodHandle InvalidateRect;

  static {
    try {
      // int DwmSetWindowAttribute(HWND hwnd, DWORD dwAttribute, LPCVOID pvAttribute, DWORD
      // cbAttribute)
      DwmSetWindowAttribute =
          DWMAPI
              .find("DwmSetWindowAttribute")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr,
                          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("DwmSetWindowAttribute not found"));

      // BOOL InvalidateRect(HWND hWnd, const RECT *lpRect, BOOL bErase)
      InvalidateRect =
          USER32
              .find("InvalidateRect")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("InvalidateRect not found"));

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    // References:
    // https://docs.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute
    // https://winscp.net/forum/viewtopic.php?t=30088
    // https://gist.github.com/rossy/ebd83ba8f22339ce25ef68bfc007dfd2
    //
    // This is the code that we're mimicking (in c):
    /*
    DwmSetWindowAttribute(
        hwnd,
        DWMWA_USE_IMMERSIVE_DARK_MODE,
        &(BOOL) { TRUE },
        sizeof(BOOL)
    );
    InvalidateRect(hwnd, null, FALSE);
    */

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment hwnd = webview.getNativeWindowPointer();

      // Allocate a BOOL (int32) for the dark mode value
      MemorySegment boolValue = arena.allocate(JAVA_INT);
      boolValue.set(JAVA_INT, 0, shouldBeDark ? 1 : 0);

      // Try the newer constant first (Windows 10 20H1+)
      DwmSetWindowAttribute.invoke(
          hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, boolValue, (int) JAVA_INT.byteSize());

      // Try the older constant for compatibility (Windows 10 before 20H1)
      DwmSetWindowAttribute.invoke(
          hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, boolValue, (int) JAVA_INT.byteSize());

      // Repaint the window
      InvalidateRect.invoke(hwnd, MemorySegment.NULL, FALSE);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window appearance", e);
    }
  }
}
