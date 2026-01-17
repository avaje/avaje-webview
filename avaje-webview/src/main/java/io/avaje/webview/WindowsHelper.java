package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import module java.base;

final class WindowsHelper {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
  private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());

  // DWMAPI constants
  private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
  private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

  private static final int FALSE = 0;

  private static final int SWP_NOZORDER = 0x0004;
  private static final int SWP_SHOWWINDOW = 0x0040;
  private static final int GWL_STYLE = -16;
  private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
  private static final int SM_CXSCREEN = 0;
  private static final int SM_CYSCREEN = 1;
  private static final int SW_MAXIMIZE = 3;

  // Icon Constants
  private static final int WM_SETICON = 0x0080;
  private static final int ICON_SMALL = 0;
  private static final int ICON_BIG = 1;
  private static final int IMAGE_ICON = 1;
  private static final int LR_LOADFROMFILE = 0x0010;

  // Method handles
  private static final MethodHandle DwmSetWindowAttribute;
  private static final MethodHandle InvalidateRect;
  private static final MethodHandle SetWindowPos;
  private static final MethodHandle GetWindowRect;
  private static final MethodHandle GetSystemMetrics;
  private static final MethodHandle SetWindowLong;
  private static final MethodHandle GetWindowLong;
  private static final MethodHandle ShowWindow;

  private static final MethodHandle LoadImageW;
  private static final MethodHandle SendMessageW;

  // RECT structure layout
  private static final StructLayout RECT_LAYOUT =
      MemoryLayout.structLayout(
          JAVA_INT.withName("left"),
          JAVA_INT.withName("top"),
          JAVA_INT.withName("right"),
          JAVA_INT.withName("bottom"));

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

      // BOOL SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, UINT
      // uFlags)
      SetWindowPos =
          USER32
              .find("SetWindowPos")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr,
                          FunctionDescriptor.of(
                              JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                              JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("SetWindowPos not found"));

      // BOOL GetWindowRect(HWND hWnd, LPRECT lpRect)
      GetWindowRect =
          USER32
              .find("GetWindowRect")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("GetWindowRect not found"));

      // int GetSystemMetrics(int nIndex)
      GetSystemMetrics =
          USER32
              .find("GetSystemMetrics")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(JAVA_INT, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("GetSystemMetrics not found"));

      // LONG SetWindowLongW(HWND hWnd, int nIndex, LONG dwNewLong)
      SetWindowLong =
          USER32
              .find("SetWindowLongW")
              .or(() -> USER32.find("SetWindowLongA"))
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("SetWindowLong not found"));

      // LONG GetWindowLongW(HWND hWnd, int nIndex)
      GetWindowLong =
          USER32
              .find("GetWindowLongW")
              .or(() -> USER32.find("GetWindowLongA"))
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("GetWindowLong not found"));

      // BOOL ShowWindow(HWND hWnd, int nCmdShow)
      ShowWindow =
          USER32
              .find("ShowWindow")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)))
              .orElseThrow(() -> new UnsatisfiedLinkError("ShowWindow not found"));

      LoadImageW =
          USER32
              .find("LoadImageW")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr,
                          FunctionDescriptor.of(
                              ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)))
              .orElseThrow();

      SendMessageW =
          USER32
              .find("SendMessageW")
              .map(
                  addr ->
                      LINKER.downcallHandle(
                          addr,
                          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS)))
              .orElseThrow();

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
      MemorySegment hwnd = webview.nativeWindowPointer();

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

  public static void fullscreen(Webview webview) {
    try (Arena arena = Arena.ofConfined()) {
      var hwnd = webview.nativeWindowPointer();

      // Get the screen dimensions
      var rect = arena.allocate(RECT_LAYOUT);
      int rectResult = (int) GetWindowRect.invoke(hwnd, rect);

      if (rectResult == 0) {
        throw new RuntimeException("Failed to full screen window");
      }

      // Get screen width and height
      int screenWidth = (int) GetSystemMetrics.invoke(SM_CXSCREEN);
      int screenHeight = (int) GetSystemMetrics.invoke(SM_CYSCREEN);

      // Get current window style
      int currentStyle = (int) GetWindowLong.invoke(hwnd, GWL_STYLE);

      // Set the window style to remove decorations
      int newStyle = currentStyle & ~WS_OVERLAPPEDWINDOW;
      int styleResult = (int) SetWindowLong.invoke(hwnd, GWL_STYLE, newStyle);

      if (styleResult == 0) {
        throw new RuntimeException("Failed to full screen window");
      }

      // Set the window to cover the entire screen
      int posResult =
          (int)
              SetWindowPos.invoke(
                  hwnd,
                  MemorySegment.NULL,
                  0,
                  0,
                  screenWidth,
                  screenHeight,
                  SWP_NOZORDER | SWP_SHOWWINDOW);

      if (posResult == 0) {
        throw new RuntimeException("Failed to full screen window");
      }
    } catch (Throwable e) {
      throw new RuntimeException("Failed to fullscreen window", e);
    }
  }

  public static void maximizeWindow(Webview webview) {
    try {
      ShowWindow.invoke(webview.nativeWindowPointer(), SW_MAXIMIZE);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }

  public static void setIcon(Webview webview, Path iconPath) {

    if (!Files.exists(iconPath)) {
      throw new IllegalArgumentException("Icon file not found: " + iconPath);
    }

    var fileName = iconPath.getFileName().toString().toLowerCase();
    if (!fileName.endsWith(".ico")) {
      throw new IllegalArgumentException("Windows window icons must be .ico files: " + fileName);
    }

    try (var arena = Arena.ofConfined()) {
      var hwnd = webview.nativeWindowPointer();

      var absolutePath = iconPath.toAbsolutePath().toString();
      var pathSegment = arena.allocateFrom(absolutePath, StandardCharsets.UTF_16LE);

      // Load the icon with proper size for both window and taskbar
      var hIconBig =
          (MemorySegment)
              LoadImageW.invokeExact(
                  MemorySegment.NULL, pathSegment, IMAGE_ICON, 32, 32, LR_LOADFROMFILE);

      var hIconSmall =
          (MemorySegment)
              LoadImageW.invokeExact(
                  MemorySegment.NULL, pathSegment, IMAGE_ICON, 16, 16, LR_LOADFROMFILE);

      if (hIconBig.equals(MemorySegment.NULL) || hIconSmall.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Win32 LoadImageW failed for path: " + absolutePath);
      }

      // set icons
      SendMessageW.invoke(hwnd, WM_SETICON, (long) ICON_BIG, hIconBig);
      SendMessageW.invoke(hwnd, WM_SETICON, (long) ICON_SMALL, hIconSmall);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window icon", e);
    }
  }
}
