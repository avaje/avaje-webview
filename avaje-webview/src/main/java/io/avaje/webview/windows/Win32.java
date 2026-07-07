package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Panama FFM downcall handles and helpers for {@code user32}, {@code kernel32}, {@code dwmapi},
 * {@code advapi32}, and {@code ole32}.
 *
 * <h2>Constants</h2>
 *
 * <p>Window style and message constants ({@code WS_*}, {@code WM_*}, {@code SW_*}, {@code SM_*})
 * mirror {@code winuser.h}.
 *
 * <h2>Struct layouts and offsets</h2>
 *
 * <p>{@link #MSG_LAYOUT} and {@link #RECT_LAYOUT} describe x64 ABI memory layouts for the {@code
 * MSG} and {@code RECT} structs. The {@code MINMAX_*} longs are raw byte offsets into a {@code
 * MINMAXINFO} buffer; they are used with direct {@link MemorySegment} access rather than a named
 * layout to avoid building a full 40-byte struct descriptor for a write-only use case.
 *
 * <h2>Helper methods</h2>
 *
 * <p>Typed wrappers like {@link #fullscreen}, {@link #applyDarkMode}, and {@link #regQueryString}
 * compose multiple MethodHandle calls into a single operation, converting {@code Throwable} to
 * {@link RuntimeException} and hiding {@code invokeExact} boilerplate from call sites.
 */
final class Win32 {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
  private static final SymbolLookup KERNEL32 =
      SymbolLookup.libraryLookup("kernel32", Arena.global());
  private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi", Arena.global());
  private static final SymbolLookup ADVAPI32 =
      SymbolLookup.libraryLookup("advapi32", Arena.global());
  private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());

  // Window style / message constants
  static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
  static final int WS_POPUP = 0x80000000;
  static final int WS_CHILD = 0x40000000;
  static final int WS_THICKFRAME = 0x00040000;
  static final int WS_MAXIMIZEBOX = 0x00010000;
  static final int WS_CAPTION = 0x00C00000;
  static final int WM_NCLBUTTONDOWN = 0x00A1;
  static final int HTCAPTION = 2;
  static final int CW_USEDEFAULT = 0x80000000;
  static final int SW_HIDE = 0;
  static final int SW_SHOW = 5;
  static final int SW_MAXIMIZE = 3;
  static final int WM_DESTROY = 0x0002;
  static final int WM_CLOSE = 0x0010;
  static final int WM_SIZE = 0x0005;
  static final int WM_QUIT = 0x0012;
  static final int WM_ACTIVATE = 0x0006;
  static final int WM_GETMINMAXINFO = 0x0024;
  static final int WM_SETTINGCHANGE = 0x001A;
  static final int WM_APP = 0x8000;
  static final int WA_INACTIVE = 0;
  static final int GWL_STYLE = -16;
  static final int SWP_NOMOVE = 0x0002;
  static final int SWP_NOZORDER = 0x0004;
  static final int SWP_NOACTIVATE = 0x0010;
  static final int SWP_FRAMECHANGED = 0x0020;
  static final int WM_DPICHANGED = 0x02E0;
  static final int SM_CXSCREEN = 0;
  static final int SM_CYSCREEN = 1;
  static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
  static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_WIN11 = 19;
  static final int IMAGE_ICON = 1;
  static final int LR_LOADFROMFILE = 0x0010;
  static final int ICON_SMALL = 0;
  static final int ICON_BIG = 1;
  static final int WM_SETICON = 0x0080;

  // Registry
  static final long HKEY_LOCAL_MACHINE = 0x80000002L;
  static final long HKEY_CURRENT_USER = 0x80000001L;
  static final int KEY_READ = 0x20019;
  static final int KEY_WOW64_32KEY = 0x0200;
  static final int ERROR_SUCCESS = 0;
  // COM
  static final int COINIT_APARTMENTTHREADED = 0x2;

  // COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC = 0
  static final int COREWEBVIEW2_MOVE_FOCUS_PROGRAMMATIC = 0;
  // COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ = 9
  static final int COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ = 9;
  // COREWEBVIEW2_PERMISSION_STATE_ALLOW = 1
  static final int COREWEBVIEW2_PERMISSION_STATE_ALLOW = 1;

  /// Struct layouts

  /**
   * Memory layout for the Win32 {@code MSG} struct.
   *
   * <p>Used with {@link #GetMessageW} and {@link #DispatchMessageW} in the message pump loop.
   */
  static final StructLayout MSG_LAYOUT =
      MemoryLayout.structLayout(
          ADDRESS.withName("hwnd"),
          JAVA_INT.withName("message"),
          MemoryLayout.paddingLayout(4),
          JAVA_LONG.withName("wParam"),
          JAVA_LONG.withName("lParam"),
          JAVA_INT.withName("time"),
          JAVA_INT.withName("ptX"),
          JAVA_INT.withName("ptY"),
          MemoryLayout.paddingLayout(4));

  /**
   * Memory layout for the Win32 {@code RECT} struct.
   *
   * <p>Used with {@link #GetClientRect} to retrieve the client-area bounds of a window for WebView2
   * controller resize and widget positioning.
   */
  static final StructLayout RECT_LAYOUT =
      MemoryLayout.structLayout(
          JAVA_INT.withName("left"),
          JAVA_INT.withName("top"),
          JAVA_INT.withName("right"),
          JAVA_INT.withName("bottom"));

  /**
   * Byte offsets into a raw {@code MINMAXINFO} buffer (40 bytes, five consecutive {@code POINT}
   * structs, each {@code POINT} = 2 x {@code JAVA_INT}).
   *
   * <p>Written directly into the {@code lParam} pointer of {@code WM_GETMINMAXINFO} to enforce
   * minimum and maximum window dimensions.
   */
  static final long MINMAX_ptMaxSize_x = 8L;

  static final long MINMAX_ptMaxSize_y = 12L;
  static final long MINMAX_ptMinTrack_x = 24L;
  static final long MINMAX_ptMinTrack_y = 28L;
  static final long MINMAX_ptMaxTrack_x = 32L;
  static final long MINMAX_ptMaxTrack_y = 36L;

  /**
   * {@code RegisterClassExW(WNDCLASSEXW* lpwcx) -> ATOM}
   *
   * <p>Registers a window class from a {@code WNDCLASSEXW} struct.
   */
  static final MethodHandle RegisterClassExW =
      downcall(USER32, "RegisterClassExW", FunctionDescriptor.of(JAVA_SHORT, ADDRESS));

  /**
   * {@code CreateWindowExW(dwExStyle, lpClassName, lpWindowName, dwStyle, X, Y, nWidth, nHeight,
   * hWndParent, hMenu, hInstance, lpParam) -> HWND}
   *
   * <p>Creates a window of the registered class. Returns {@code NULL} on failure.
   */
  static final MethodHandle CreateWindowExW =
      downcall(
          USER32,
          "CreateWindowExW",
          FunctionDescriptor.of(
              ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
              ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code DefWindowProcW(hWnd, Msg, wParam, lParam) -> LRESULT}
   *
   * <p>Default message handler. must be called for any message the WndProc does not handle.
   * Skipping it causes visual glitches (e.g. missing title bar paint) and breaks system behaviors
   * like ALT+F4.
   */
  static final MethodHandle DefWindowProcW =
      downcall(
          USER32,
          "DefWindowProcW",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));

  /**
   * {@code DestroyWindow(hWnd) -> BOOL}
   *
   * <p>Destroys the window and all child windows. Sends {@code WM_DESTROY} and {@code WM_NCDESTROY}
   * to the window. Must be called from the thread that created the window.
   */
  static final MethodHandle DestroyWindow =
      downcall(USER32, "DestroyWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /**
   * {@code GetMessageW(lpMsg, hWnd, wMsgFilterMin, wMsgFilterMax) -> BOOL}
   *
   * <p>Blocking message pump pull. Returns {@code -1} on error, {@code 0} for {@code WM_QUIT},
   * positive for any other message. Pass {@code NULL}/{@code 0}/{@code 0}/{@code 0} to retrieve all
   * messages for all windows on the calling thread.
   */
  static final MethodHandle GetMessageW =
      downcall(
          USER32,
          "GetMessageW",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

  /** {@code TranslateMessage(lpMsg) -> BOOL}. Translates virtual-key messages into characters. */
  static final MethodHandle TranslateMessage =
      downcall(USER32, "TranslateMessage", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /** {@code DispatchMessageW(lpMsg) -> LRESULT}. Routes a message to its window's WndProc. */
  static final MethodHandle DispatchMessageW =
      downcall(USER32, "DispatchMessageW", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

  /**
   * {@code PostMessageW(hWnd, Msg, wParam, lParam) -> BOOL}
   *
   * <p>Posts a message to the target window's thread message queue without waiting for it to be
   * processed. Used to dispatch work from non-UI threads (e.g. posting {@link #WM_APP} to {@code
   * hwndMsg} to trigger {@code pending} queue drain).
   */
  static final MethodHandle PostMessageW =
      downcall(
          USER32,
          "PostMessageW",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));

  /**
   * {@code PostQuitMessage(nExitCode) -> void}. Posts {@code WM_QUIT} to the calling thread's
   * message queue.
   */
  static final MethodHandle PostQuitMessage =
      downcall(USER32, "PostQuitMessage", FunctionDescriptor.ofVoid(JAVA_INT));

  /**
   * {@code ShowWindow(hWnd, nCmdShow) -> BOOL}. Sets the window's show state ({@code SW_SHOW},
   * {@code SW_MAXIMIZE}, etc.).
   */
  static final MethodHandle ShowWindow =
      downcall(USER32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code UpdateWindow(hWnd) -> BOOL}. Forces an immediate repaint by sending {@code WM_PAINT}
   * directly.
   */
  static final MethodHandle UpdateWindow =
      downcall(USER32, "UpdateWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /** {@code SetFocus(hWnd) -> HWND}. Sets keyboard focus to the specified window. */
  static final MethodHandle SetFocus =
      downcall(USER32, "SetFocus", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code GetClientRect(hWnd, lpRect) -> BOOL}
   *
   * <p>Fills a {@link #RECT_LAYOUT} buffer with the client-area dimensions of {@code hWnd}. Client
   * coordinates have their origin at the top-left of the client area, so {@code left} and {@code
   * top} are always zero; only {@code right} and {@code bottom} carry the size.
   */
  static final MethodHandle GetClientRect =
      downcall(USER32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

  /**
   * {@code SetWindowPos(hWnd, hWndInsertAfter, X, Y, cx, cy, uFlags) -> BOOL}
   *
   * <p>Changes position, size, and/or Z-order of a window.
   */
  static final MethodHandle SetWindowPos =
      downcall(
          USER32,
          "SetWindowPos",
          FunctionDescriptor.of(
              JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

  /**
   * {@code MoveWindow(hWnd, X, Y, nWidth, nHeight, bRepaint) -> BOOL}
   *
   * <p>Moves and resizes a window. Unlike {@link #SetWindowPos}, has no flags parameter. Used for
   * repositioning child windows (the WebView2 widget) to fill the parent client area.
   */
  static final MethodHandle MoveWindow =
      downcall(
          USER32,
          "MoveWindow",
          FunctionDescriptor.of(
              JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

  /**
   * {@code GetSystemMetrics(nIndex) -> int}
   *
   * <p>returns screen or system metrics (e.g. {@link #SM_CXSCREEN}).
   */
  static final MethodHandle GetSystemMetrics =
      downcall(USER32, "GetSystemMetrics", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

  /**
   * {@code GetWindowLongW(hWnd, nIndex) -> LONG}
   *
   * <p>Reads a window attribute.
   */
  static final MethodHandle GetWindowLong =
      downcall(USER32, "GetWindowLongW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code SetWindowLongW(hWnd, nIndex, dwNewLong) -> LONG}
   *
   * <p>Writes a 32-bit window attribute.
   */
  static final MethodHandle SetWindowLong =
      downcall(
          USER32, "SetWindowLongW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

  /** {@code USER_DEFAULT_SCREEN_DPI}: the DPI at 100% scaling, used as the client-size baseline. */
  static final int DEFAULT_DPI = 96;

  /**
   * {@code GetDpiForWindow(hWnd) -> UINT}
   *
   * <p>Returns the DPI of the monitor the window is currently on.
   */
  static final MethodHandle GetDpiForWindow =
      downcall(USER32, "GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /** {@code SetThreadDpiAwarenessContext(dpiContext) -> DPI_AWARENESS_CONTEXT} */
  static final MethodHandle SetThreadDpiAwarenessContext =
      USER32
          .find("SetThreadDpiAwarenessContext")
          .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
          .orElse(null);

  /**
   * {@code AdjustWindowRectExForDpi(lpRect, dwStyle, bMenu, dwExStyle, dpi) -> BOOL}
   *
   * <p>Expands a client-area rect into the outer window rect that would produce it at a given DPI.
   */
  static final MethodHandle AdjustWindowRectExForDpi =
      downcall(
          USER32,
          "AdjustWindowRectExForDpi",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

  /**
   * {@code SetWindowTextW(hWnd, lpString) -> BOOL}
   *
   * <p>sets the window title bar text.
   */
  static final MethodHandle SetWindowTextW =
      downcall(USER32, "SetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

  /**
   * {@code InvalidateRect(hWnd, lpRect, bErase) -> BOOL} - marks a region dirty, triggering a
   * repaint.
   */
  static final MethodHandle InvalidateRect =
      downcall(
          USER32, "InvalidateRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

  /**
   * {@code LoadImageW(hInst, name, type, cx, cy, fuLoad) -> HANDLE}
   *
   * <p>Loads an image resource. With {@link #IMAGE_ICON} and {@link #LR_LOADFROMFILE}, loads an
   * {@code .ico} file from disk at the given pixel dimensions. Returns {@code NULL} on failure.
   */
  static final MethodHandle LoadImageW =
      downcall(
          USER32,
          "LoadImageW",
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

  /**
   * {@code SendMessageW(hWnd, Msg, wParam, lParam) -> LRESULT}.
   *
   * <p>Sends a message synchronously. Used to set the window icon via {@link #WM_SETICON}.
   */
  static final MethodHandle SendMessageW =
      downcall(
          USER32,
          "SendMessageW",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));

  /**
   * {@code ReleaseCapture() -> BOOL}
   *
   * <p>Releases the current mouse capture. Required before sending {@link #WM_NCLBUTTONDOWN} with
   * {@link #HTCAPTION} to start a native window-move loop from a synthetic (non-hardware) message -
   * without this, the window ignores the fake non-client click while it still holds mouse capture
   * from the original client-area click.
   */
  static final MethodHandle ReleaseCapture =
      downcall(USER32, "ReleaseCapture", FunctionDescriptor.of(JAVA_INT));

  /**
   * {@code GetModuleHandleW(lpModuleName) -> HMODULE}
   *
   * <p>Returns the module handle for the current process when passed {@code NULL}.
   */
  static final MethodHandle GetModuleHandleW =
      downcall(KERNEL32, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code LoadLibraryW(lpLibFileName) -> HMODULE}
   *
   * <p>Loads a DLL into the calling process. Used to load {@code EmbeddedBrowserWebView.dll} from
   * the Edge installation directory when {@code WebView2Loader.dll} is not available.
   */
  static final MethodHandle LoadLibraryW =
      downcall(KERNEL32, "LoadLibraryW", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code GetProcAddress(hModule, lpProcName) -> FARPROC}
   *
   * <p>Resolves an exported function address from a loaded module. Used to locate {@code
   * CreateWebViewEnvironmentWithOptionsInternal} inside the embedded Edge DLL.
   */
  static final MethodHandle GetProcAddress =
      downcall(KERNEL32, "GetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code DwmSetWindowAttribute(hwnd, dwAttribute, pvAttribute, cbAttribute) -> HRESULT}
   *
   * <p>Sets a Desktop Window Manager attribute on the window. Used with {@link
   * #DWMWA_USE_IMMERSIVE_DARK_MODE} (value 20, Windows 11+) to apply the system dark theme to the
   * window's non-client area. Falls back to attribute 19 on pre-Windows-11.
   *
   * <p>{@code pvAttribute} is a pointer to a {@code DWORD} value, not the value itself; {@code
   * cbAttribute} must be {@code sizeof(DWORD)} (4).
   */
  static final MethodHandle DwmSetWindowAttr =
      downcall(
          DWMAPI,
          "DwmSetWindowAttribute",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code RegOpenKeyExW(hKey, lpSubKey, ulOptions, samDesired, phkResult) -> LSTATUS}
   *
   * <p>Opens a registry key for reading.
   */
  static final MethodHandle RegOpenKeyExW =
      downcall(
          ADVAPI32,
          "RegOpenKeyExW",
          FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

  /**
   * {@code RegQueryValueExW(hKey, lpValueName, lpReserved, lpType, lpData, lpcbData) -> LSTATUS}
   *
   * <p>Reads a registry value.
   */
  static final MethodHandle RegQueryValueExW =
      downcall(
          ADVAPI32,
          "RegQueryValueExW",
          FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code RegCloseKey(hKey) -> LSTATUS}
   *
   * <p>Closes a registry key handle opened by {@link #RegOpenKeyExW}.
   */
  static final MethodHandle RegCloseKey =
      downcall(ADVAPI32, "RegCloseKey", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

  /**
   * {@code CoInitializeEx(pvReserved, dwCoInit) -> HRESULT}
   *
   * <p>Initializes COM on the calling thread. Must be called before any WebView2 COM operations.
   * {@link #COINIT_APARTMENTTHREADED} creates a Single-Threaded Apartment (STA), which WebView2
   * requires. Returns {@code S_OK} (0) on first init, {@code S_FALSE} (1) if already initialized as
   * STA, or {@code RPC_E_CHANGED_MODE} if the thread is already an MTA.
   */
  static final MethodHandle CoInitializeEx =
      downcall(OLE32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code CoTaskMemFree(pv) -> void}
   *
   * <p>Frees a buffer allocated by COM (e.g. BSTR/LPWSTR outputs from WebView2 callback arguments).
   * Must be called after consuming the string to avoid a native heap leak.
   */
  static final MethodHandle CoTaskMemFree =
      downcall(OLE32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));

  private static MethodHandle downcall(SymbolLookup lib, String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        lib.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + sym)),
        desc);
  }

  /** Returns the {@code HMODULE} for the current process (equivalent to passing {@code NULL}). */
  static MemorySegment getModuleHandle() {
    try {
      return (MemorySegment) GetModuleHandleW.invokeExact(MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Posts {@code WM_QUIT} with {@code code} to the calling thread's message queue, breaking the
   * pump loop.
   */
  static void postQuitMessage(int code) {
    try {
      PostQuitMessage.invokeExact(code);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets the show state of {@code hwnd} to {@code cmd} ({@code SW_SHOW}, {@code SW_MAXIMIZE},
   * etc.).
   */
  static void showWindow(MemorySegment hwnd, int cmd) {
    try {
      final var _ = (int) ShowWindow.invokeExact(hwnd, cmd);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Sets the title bar text of {@code hwnd} to {@code title} (UTF-16LE encoded). */
  static void setWindowText(MemorySegment hwnd, String title) {
    try (var a = Arena.ofConfined()) {
      final var _ =
          (int) SetWindowTextW.invokeExact(hwnd, a.allocateFrom(title, StandardCharsets.UTF_16LE));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Returns the client-area bounds of {@code hwnd} as a {@link #RECT_LAYOUT} segment allocated from
   * {@code a}. {@code left} and {@code top} are always zero; use {@code right} and {@code bottom}
   * for width and height.
   */
  static MemorySegment getClientRect(MemorySegment hwnd, Arena a) {
    final var rect = a.allocate(RECT_LAYOUT);
    try {
      final var _ = (int) GetClientRect.invokeExact(hwnd, rect);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    return rect;
  }

  /**
   * Converts a logical client-area size (at 96 DPI baseline) into the outer window size {@link
   * #SetWindowPos} expects, scaling to the window's current monitor DPI first.
   */
  static int[] frameSize(MemorySegment hwnd, int width, int height) {
    try (var a = Arena.ofConfined()) {
      final var dpi = (int) GetDpiForWindow.invokeExact(hwnd);
      final var scaledWidth = width * dpi / DEFAULT_DPI;
      final var scaledHeight = height * dpi / DEFAULT_DPI;
      final var style = (int) GetWindowLong.invokeExact(hwnd, GWL_STYLE);
      final var rect = a.allocate(RECT_LAYOUT);
      rect.set(JAVA_INT, 8, scaledWidth);
      rect.set(JAVA_INT, 12, scaledHeight);
      final var _ = (int) AdjustWindowRectExForDpi.invokeExact(rect, style, 0, 0, dpi);
      final var frameWidth = rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0);
      final var frameHeight = rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4);
      return new int[] {frameWidth, frameHeight};
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Switches {@code hwnd} to borderless fullscreen by stripping {@link #WS_OVERLAPPEDWINDOW} from
   * the window style and covering the entire primary monitor via {@link #SetWindowPos}.
   */
  static void fullscreen(MemorySegment hwnd) {
    try (var _ = Arena.ofConfined()) {
      final var screenW = (int) GetSystemMetrics.invokeExact(SM_CXSCREEN);
      final var screenH = (int) GetSystemMetrics.invokeExact(SM_CYSCREEN);
      final var style = (int) GetWindowLong.invokeExact(hwnd, GWL_STYLE);
      final var _ = (int) SetWindowLong.invokeExact(hwnd, GWL_STYLE, style & ~WS_OVERLAPPEDWINDOW);
      final var _ =
          (int)
              SetWindowPos.invokeExact(
                  hwnd,
                  MemorySegment.NULL,
                  0,
                  0,
                  screenW,
                  screenH,
                  SWP_NOZORDER | SWP_FRAMECHANGED);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Begins a native window-move loop for {@code hwnd}, as if the user had clicked and dragged the
   * title bar. Releases the current mouse capture first, then sends a synthetic {@link
   * #WM_NCLBUTTONDOWN} with {@link #HTCAPTION}, which {@code DefWindowProc} handles by entering the
   * standard move loop for as long as the mouse button stays down.
   */
  static void startWindowDrag(MemorySegment hwnd) {
    try {
      final var _ = (int) ReleaseCapture.invokeExact();
      final var _ =
          (long)
              SendMessageW.invokeExact(
                  hwnd, WM_NCLBUTTONDOWN, (long) HTCAPTION, MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Applies or removes the immersive dark-mode DWM attribute on {@code hwnd}. Tries attribute 20
   * (Windows 11+) first; if that returns a non-zero HRESULT, falls back to attribute 19 (Windows 10
   * build 1903+). Triggers an immediate repaint via {@link #InvalidateRect}.
   */
  static void applyDarkMode(MemorySegment hwnd, boolean dark) {
    try (var a = Arena.ofConfined()) {
      final var val = a.allocate(JAVA_INT);
      val.set(JAVA_INT, 0, dark ? 1 : 0);
      final var hr =
          (int)
              DwmSetWindowAttr.invokeExact(
                  hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, val, (int) JAVA_INT.byteSize());
      if (hr != 0) {
        final var _ =
            (int)
                DwmSetWindowAttr.invokeExact(
                    hwnd,
                    DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_WIN11,
                    val,
                    (int) JAVA_INT.byteSize());
      }
      final var _ = (int) InvalidateRect.invokeExact(hwnd, MemorySegment.NULL, 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Loads an {@code .ico} file from {@code iconPath} at 32×32 and 16×16 and sends {@link
   * #WM_SETICON} to {@code hwnd} for each size. Does nothing if {@link #LoadImageW} returns a null
   * handle for either size.
   */
  static void setIcon(MemorySegment hwnd, java.nio.file.Path iconPath) {
    try (var a = Arena.ofConfined()) {
      final var pathSeg =
          a.allocateFrom(iconPath.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
      final var big =
          (MemorySegment)
              LoadImageW.invokeExact(
                  MemorySegment.NULL, pathSeg, IMAGE_ICON, 32, 32, LR_LOADFROMFILE);
      final var small =
          (MemorySegment)
              LoadImageW.invokeExact(
                  MemorySegment.NULL, pathSeg, IMAGE_ICON, 16, 16, LR_LOADFROMFILE);
      if (big.address() != 0) {
        final var _ = (long) SendMessageW.invokeExact(hwnd, WM_SETICON, (long) ICON_BIG, big);
      }
      if (small.address() != 0) {
        final var _ = (long) SendMessageW.invokeExact(hwnd, WM_SETICON, (long) ICON_SMALL, small);
      }
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Reads a {@code REG_SZ} string value from the registry, or returns {@code null} on any error.
   *
   * <p>Uses a two-call pattern: first call with a null data buffer to retrieve the required byte
   * count, second call with an allocated buffer to read the value. The key is closed in a {@code
   * finally} block regardless of whether the value read succeeds.
   *
   * @param rootKey predefined key constant ({@link #HKEY_LOCAL_MACHINE} or {@link
   *     #HKEY_CURRENT_USER})
   * @param subKey registry subkey path
   * @param valueName name of the string value to read
   * @return the decoded UTF-16LE string with trailing nulls stripped, or {@code null} on error
   */
  static String regQueryString(long rootKey, String subKey, String valueName) {
    try (var a = Arena.ofConfined()) {
      final var pKey = a.allocate(JAVA_LONG);
      var status =
          (int)
              RegOpenKeyExW.invokeExact(
                  rootKey,
                  a.allocateFrom(subKey, StandardCharsets.UTF_16LE),
                  0,
                  KEY_READ | KEY_WOW64_32KEY,
                  pKey);
      if (status != ERROR_SUCCESS) return null;
      final var hkey = pKey.get(JAVA_LONG, 0);
      try {
        final var cbData = a.allocate(JAVA_INT);
        cbData.set(JAVA_INT, 0, 0);
        // First call: get buffer size
        final var _ =
            (int)
                RegQueryValueExW.invokeExact(
                    hkey,
                    a.allocateFrom(valueName, StandardCharsets.UTF_16LE),
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    cbData);
        final var bufSize = cbData.get(JAVA_INT, 0);
        if (bufSize <= 0) return null;
        final var buf = a.allocate(bufSize);
        cbData.set(JAVA_INT, 0, bufSize);
        status =
            (int)
                RegQueryValueExW.invokeExact(
                    hkey,
                    a.allocateFrom(valueName, StandardCharsets.UTF_16LE),
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    buf,
                    cbData);
        if (status != ERROR_SUCCESS) return null;
        // UTF-16LE, strip trailing nulls
        return buf.reinterpret(bufSize)
            .getString(0, StandardCharsets.UTF_16LE)
            .stripTrailing()
            .replace("\0", "");
      } finally {
        final var _ = (int) RegCloseKey.invokeExact(hkey);
      }
    } catch (final Throwable t) {
      return null;
    }
  }

  /** Reads a {@code REG_DWORD} value from the registry, or returns {@code -1} on any error. */
  static int regQueryDword(long rootKey, String subKey, String valueName) {
    try (var a = Arena.ofConfined()) {
      final var pKey = a.allocate(JAVA_LONG);
      var status =
          (int)
              RegOpenKeyExW.invokeExact(
                  rootKey,
                  a.allocateFrom(subKey, StandardCharsets.UTF_16LE),
                  0,
                  KEY_READ | KEY_WOW64_32KEY,
                  pKey);
      if (status != ERROR_SUCCESS) return -1;
      final var hkey = pKey.get(JAVA_LONG, 0);
      try {
        final var buf = a.allocate(JAVA_INT);
        final var cbData = a.allocate(JAVA_INT);
        cbData.set(JAVA_INT, 0, 4);
        status =
            (int)
                RegQueryValueExW.invokeExact(
                    hkey,
                    a.allocateFrom(valueName, StandardCharsets.UTF_16LE),
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    buf,
                    cbData);
        if (status != ERROR_SUCCESS) return -1;
        return buf.get(JAVA_INT, 0);
      } finally {
        final var _ = (int) RegCloseKey.invokeExact(hkey);
      }
    } catch (final Throwable t) {
      return -1;
    }
  }

  /**
   * Initializes COM on the calling thread as a Single-Threaded Apartment (STA).
   *
   * <p>WebView2 requires STA. The HRESULT is logged to stdout: {@code S_OK} (0x0) = first init on
   * this thread, {@code S_FALSE} (0x1) = already STA (safe to continue), {@code RPC_E_CHANGED_MODE}
   * (0x80010106) = thread is MTA (programming error).
   */
  static void coInitialize() {
    try {
      final var _ = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Sets the calling thread's DPI awareness to Per-Monitor V2 so that {@link #GetDpiForWindow} */
  static void enablePerMonitorDpiAwareness() {
    if (SetThreadDpiAwarenessContext == null) return;
    try {
      // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = (HANDLE)-4
      final var _ =
          (MemorySegment) SetThreadDpiAwarenessContext.invokeExact(MemorySegment.ofAddress(-4L));
    } catch (final Throwable ignored) {
    }
  }

  /**
   * Frees a COM-allocated memory block (e.g. a {@code LPWSTR} output from a WebView2 event
   * argument). Silently ignores failures - this is best-effort cleanup.
   */
  static void coTaskMemFree(MemorySegment ptr) {
    try {
      CoTaskMemFree.invokeExact(ptr);
    } catch (final Throwable t) {
      /* best effort */
    }
  }

  /**
   * Resolves the function at vtable index {@code idx} of {@code comObj} and returns a {@link
   * MethodHandle} bound to the given {@link FunctionDescriptor}.
   */
  static MethodHandle resolve(MemorySegment comObj, int idx, FunctionDescriptor fd) {
    return Linker.nativeLinker().downcallHandle(vtableFn(comObj, idx), fd);
  }

  /**
   * Reads the function pointer at vtable slot {@code idx} of a COM object.
   *
   * <p>A COM object's first field is a pointer to its vtable (an array of function pointers). This
   * method dereferences the vtable pointer and returns the address at position {@code idx}.
   */
  static MemorySegment vtableFn(MemorySegment comObj, int idx) {
    final var vtable =
        comObj
            .reinterpret(ADDRESS.byteSize())
            .get(ADDRESS, 0)
            .reinterpret((idx + 1) * ADDRESS.byteSize());
    return vtable.getAtIndex(ADDRESS, idx);
  }

  private Win32() {}
}
