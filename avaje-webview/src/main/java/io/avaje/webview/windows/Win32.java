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

final class Win32 {

  private static final Linker LINKER     = Linker.nativeLinker();
  private static final SymbolLookup USER32   = SymbolLookup.libraryLookup("user32",   Arena.global());
  private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
  private static final SymbolLookup DWMAPI   = SymbolLookup.libraryLookup("dwmapi",   Arena.global());
  private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
  private static final SymbolLookup OLE32    = SymbolLookup.libraryLookup("ole32",    Arena.global());

  // -------------------------------------------------------------------------
  // Window style / message constants
  // -------------------------------------------------------------------------
  static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
  static final int WS_CHILD            = 0x40000000;
  static final int WS_THICKFRAME       = 0x00040000;
  static final int WS_MAXIMIZEBOX      = 0x00010000;
  static final int CW_USEDEFAULT       = 0x80000000;
  static final int SW_SHOW             = 5;
  static final int SW_MAXIMIZE         = 3;
  static final int WM_DESTROY          = 0x0002;
  static final int WM_CLOSE            = 0x0010;
  static final int WM_SIZE             = 0x0005;
  static final int WM_QUIT             = 0x0012;
  static final int WM_ACTIVATE         = 0x0006;
  static final int WM_GETMINMAXINFO    = 0x0024;
  static final int WM_SETTINGCHANGE    = 0x001A;
  static final int WM_APP              = 0x8000;
  static final int WA_INACTIVE         = 0;
  static final int GWL_STYLE           = -16;
  static final long GWLP_USERDATA      = -21L;
  static final int SWP_NOZORDER        = 0x0004;
  static final int SWP_NOACTIVATE      = 0x0010;
  static final int SWP_NOMOVE          = 0x0002;
  static final int SWP_FRAMECHANGED    = 0x0020;
  static final int SM_CXSCREEN         = 0;
  static final int SM_CYSCREEN         = 1;
  static final int DWMWA_USE_IMMERSIVE_DARK_MODE            = 20;
  static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_WIN11 = 19;
  static final int IMAGE_ICON          = 1;
  static final int LR_LOADFROMFILE     = 0x0010;
  static final int ICON_SMALL          = 0;
  static final int ICON_BIG            = 1;
  static final int WM_SETICON          = 0x0080;

  // Registry
  static final long HKEY_LOCAL_MACHINE = 0x80000002L;
  static final long HKEY_CURRENT_USER  = 0x80000001L;
  static final int  KEY_READ           = 0x20019;
  static final int  KEY_WOW64_32KEY    = 0x0200;
  static final int  ERROR_SUCCESS      = 0;
  static final int  REG_SZ             = 1;

  // COM
  static final int COINIT_APARTMENTTHREADED = 0x2;

  // COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC = 0
  static final int COREWEBVIEW2_MOVE_FOCUS_PROGRAMMATIC = 0;
  // COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ = 9
  static final int COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ = 9;
  // COREWEBVIEW2_PERMISSION_STATE_ALLOW = 1
  static final int COREWEBVIEW2_PERMISSION_STATE_ALLOW = 1;

  // -------------------------------------------------------------------------
  // Struct layouts
  // -------------------------------------------------------------------------

  // MSG: hwnd(8) + message(4) + pad(4) + wParam(8) + lParam(8) + time(4) + ptX(4) + ptY(4) + pad(4)
  static final StructLayout MSG_LAYOUT = MemoryLayout.structLayout(
      ADDRESS.withName("hwnd"),
      JAVA_INT.withName("message"),
      MemoryLayout.paddingLayout(4),
      JAVA_LONG.withName("wParam"),
      JAVA_LONG.withName("lParam"),
      JAVA_INT.withName("time"),
      JAVA_INT.withName("ptX"),
      JAVA_INT.withName("ptY"),
      MemoryLayout.paddingLayout(4)
  );

  // RECT: left(4) + top(4) + right(4) + bottom(4)
  static final StructLayout RECT_LAYOUT = MemoryLayout.structLayout(
      JAVA_INT.withName("left"),
      JAVA_INT.withName("top"),
      JAVA_INT.withName("right"),
      JAVA_INT.withName("bottom")
  );

  // MINMAXINFO: 5 × POINT (each POINT = 2 × JAVA_INT)
  // ptReserved(8) + ptMaxSize(8) + ptMaxPosition(8) + ptMinTrackSize(8) + ptMaxTrackSize(8) = 40 bytes
  static final long MINMAX_ptMaxSize_x   =  8L;
  static final long MINMAX_ptMaxSize_y   = 12L;
  static final long MINMAX_ptMinTrack_x  = 24L;
  static final long MINMAX_ptMinTrack_y  = 28L;
  static final long MINMAX_ptMaxTrack_x  = 32L;
  static final long MINMAX_ptMaxTrack_y  = 36L;

  // -------------------------------------------------------------------------
  // Method handles
  // -------------------------------------------------------------------------
  static final MethodHandle RegisterClassExW   = downcall(USER32,   "RegisterClassExW",   FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
  static final MethodHandle CreateWindowExW    = downcall(USER32,   "CreateWindowExW",    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle DefWindowProcW     = downcall(USER32,   "DefWindowProcW",     FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
  static final MethodHandle DestroyWindow      = downcall(USER32,   "DestroyWindow",      FunctionDescriptor.of(JAVA_INT, ADDRESS));
  static final MethodHandle GetMessageW        = downcall(USER32,   "GetMessageW",        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
  static final MethodHandle TranslateMessage   = downcall(USER32,   "TranslateMessage",   FunctionDescriptor.of(JAVA_INT, ADDRESS));
  static final MethodHandle DispatchMessageW   = downcall(USER32,   "DispatchMessageW",   FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  static final MethodHandle PostMessageW       = downcall(USER32,   "PostMessageW",       FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
  static final MethodHandle PostQuitMessage    = downcall(USER32,   "PostQuitMessage",    FunctionDescriptor.ofVoid(JAVA_INT));
  static final MethodHandle ShowWindow         = downcall(USER32,   "ShowWindow",         FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  static final MethodHandle UpdateWindow       = downcall(USER32,   "UpdateWindow",       FunctionDescriptor.of(JAVA_INT, ADDRESS));
  static final MethodHandle SetFocus           = downcall(USER32,   "SetFocus",           FunctionDescriptor.of(ADDRESS, ADDRESS));
  static final MethodHandle GetClientRect      = downcall(USER32,   "GetClientRect",      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  static final MethodHandle GetWindowRect      = downcall(USER32,   "GetWindowRect",      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  static final MethodHandle SetWindowPos       = downcall(USER32,   "SetWindowPos",       FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
  static final MethodHandle MoveWindow         = downcall(USER32,   "MoveWindow",         FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
  static final MethodHandle GetSystemMetrics   = downcall(USER32,   "GetSystemMetrics",   FunctionDescriptor.of(JAVA_INT, JAVA_INT));
  static final MethodHandle GetWindowLong      = downcall(USER32,   "GetWindowLongW",     FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  static final MethodHandle SetWindowLong      = downcall(USER32,   "SetWindowLongW",     FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
  static final MethodHandle GetWindowLongPtr   = downcall(USER32,   "GetWindowLongPtrW",  FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
  static final MethodHandle SetWindowLongPtr   = downcall(USER32,   "SetWindowLongPtrW",  FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
  static final MethodHandle SetWindowTextW     = downcall(USER32,   "SetWindowTextW",     FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  static final MethodHandle InvalidateRect     = downcall(USER32,   "InvalidateRect",     FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
  static final MethodHandle LoadImageW         = downcall(USER32,   "LoadImageW",         FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
  static final MethodHandle SendMessageW       = downcall(USER32,   "SendMessageW",       FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
  static final MethodHandle GetModuleHandleW   = downcall(KERNEL32, "GetModuleHandleW",   FunctionDescriptor.of(ADDRESS, ADDRESS));
  static final MethodHandle GetModuleFileNameW = downcall(KERNEL32, "GetModuleFileNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
  static final MethodHandle LoadLibraryW       = downcall(KERNEL32, "LoadLibraryW",       FunctionDescriptor.of(ADDRESS, ADDRESS));
  static final MethodHandle GetProcAddress     = downcall(KERNEL32, "GetProcAddress",     FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle DwmSetWindowAttr   = downcall(DWMAPI,   "DwmSetWindowAttribute", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
  static final MethodHandle RegOpenKeyExW      = downcall(ADVAPI32, "RegOpenKeyExW",      FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
  static final MethodHandle RegQueryValueExW   = downcall(ADVAPI32, "RegQueryValueExW",   FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle RegCloseKey        = downcall(ADVAPI32, "RegCloseKey",        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
  static final MethodHandle CoInitializeEx     = downcall(OLE32,    "CoInitializeEx",     FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  static final MethodHandle CoTaskMemFree      = downcall(OLE32,    "CoTaskMemFree",      FunctionDescriptor.ofVoid(ADDRESS));

  private static MethodHandle downcall(SymbolLookup lib, String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        lib.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + sym)), desc);
  }

  // -------------------------------------------------------------------------
  // Java-friendly wrappers
  // -------------------------------------------------------------------------

  static MemorySegment getModuleHandle() {
    try { return (MemorySegment) GetModuleHandleW.invokeExact(MemorySegment.NULL); }
    catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void postQuitMessage(int code) {
    try { PostQuitMessage.invokeExact(code); }
    catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void showWindow(MemorySegment hwnd, int cmd) {
    try { final var _ = (int) ShowWindow.invokeExact(hwnd, cmd); }
    catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void setWindowText(MemorySegment hwnd, String title) {
    try (var a = Arena.ofConfined()) {
      final var _ = (int) SetWindowTextW.invokeExact(hwnd, a.allocateFrom(title, StandardCharsets.UTF_16LE));
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static MemorySegment getClientRect(MemorySegment hwnd, Arena a) {
    final var rect = a.allocate(RECT_LAYOUT);
    try { final var _ = (int) GetClientRect.invokeExact(hwnd, rect); }
    catch (final Throwable t) { throw new RuntimeException(t); }
    return rect;
  }

  static void fullscreen(MemorySegment hwnd) {
    try (var _ = Arena.ofConfined()) {
      final var screenW = (int) GetSystemMetrics.invokeExact(SM_CXSCREEN);
      final var screenH = (int) GetSystemMetrics.invokeExact(SM_CYSCREEN);
      final var style   = (int) GetWindowLong.invokeExact(hwnd, GWL_STYLE);
      final var _ = (int) SetWindowLong.invokeExact(hwnd, GWL_STYLE, style & ~WS_OVERLAPPEDWINDOW);
      SetWindowPos.invokeExact(hwnd, MemorySegment.NULL, 0, 0, screenW, screenH,
        SWP_NOZORDER | SWP_FRAMECHANGED);
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void applyDarkMode(MemorySegment hwnd, boolean dark) {
    try (var a = Arena.ofConfined()) {
      final var val = a.allocate(JAVA_INT);
      val.set(JAVA_INT, 0, dark ? 1 : 0);
      final var hr = (int) DwmSetWindowAttr.invokeExact(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, val, (int) JAVA_INT.byteSize());
      if (hr != 0) {
        final var _ = (int) DwmSetWindowAttr.invokeExact(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_WIN11, val, (int) JAVA_INT.byteSize());
      }
      final var _ = (int) InvalidateRect.invokeExact(hwnd, MemorySegment.NULL, 0);
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void setIcon(MemorySegment hwnd, java.nio.file.Path iconPath) {
    try (var a = Arena.ofConfined()) {
      final var pathSeg = a.allocateFrom(iconPath.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
      final var big   = (MemorySegment) LoadImageW.invokeExact(MemorySegment.NULL, pathSeg, IMAGE_ICON, 32, 32, LR_LOADFROMFILE);
      final var small = (MemorySegment) LoadImageW.invokeExact(MemorySegment.NULL, pathSeg, IMAGE_ICON, 16, 16, LR_LOADFROMFILE);
      if (big.address()   != 0) { final var _ = (long) SendMessageW.invokeExact(hwnd, WM_SETICON, (long) ICON_BIG,   big); }
      if (small.address() != 0) { final var _ = (long) SendMessageW.invokeExact(hwnd, WM_SETICON, (long) ICON_SMALL, small); }
    } catch (final Throwable t) { throw new RuntimeException(t); }
  }

  /** Returns the string value from the given registry key, or null on error. */
  static String regQueryString(long rootKey, String subKey, String valueName) {
    try (var a = Arena.ofConfined()) {
      final var pKey = a.allocate(JAVA_LONG);
      var status = (int) RegOpenKeyExW.invokeExact(rootKey,
          a.allocateFrom(subKey, StandardCharsets.UTF_16LE),
          0, KEY_READ | KEY_WOW64_32KEY, pKey);
      if (status != ERROR_SUCCESS) return null;
      final var hkey = pKey.get(JAVA_LONG, 0);
      try {
        final var cbData = a.allocate(JAVA_INT);
        cbData.set(JAVA_INT, 0, 0);
        // First call: get buffer size
        final var _ = (int) RegQueryValueExW.invokeExact(hkey,
            a.allocateFrom(valueName, StandardCharsets.UTF_16LE),
            MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, cbData);
        final var bufSize = cbData.get(JAVA_INT, 0);
        if (bufSize <= 0) return null;
        final var buf = a.allocate(bufSize);
        cbData.set(JAVA_INT, 0, bufSize);
        status = (int) RegQueryValueExW.invokeExact(hkey,
            a.allocateFrom(valueName, StandardCharsets.UTF_16LE),
            MemorySegment.NULL, MemorySegment.NULL, buf, cbData);
        if (status != ERROR_SUCCESS) return null;
        // UTF-16LE, strip trailing nulls
        return buf.reinterpret(bufSize).getString(0, StandardCharsets.UTF_16LE).stripTrailing().replace("\0", "");
      } finally {
        final var _ = (int) RegCloseKey.invokeExact(hkey);
      }
    } catch (final Throwable t) { return null; }
  }

  static void coInitialize() {
    try {
      final var hr = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
      // S_OK (0x0) = first init, S_FALSE (0x1) = already STA, RPC_E_CHANGED_MODE (0x80010106) = thread is MTA (bug)
      System.out.println("[wv2] CoInitializeEx(APARTMENTTHREADED) hr=0x" + Integer.toHexString(hr));
    }
    catch (final Throwable t) { throw new RuntimeException(t); }
  }

  static void coTaskMemFree(MemorySegment ptr) {
    try { CoTaskMemFree.invokeExact(ptr); }
    catch (final Throwable t) { /* best effort */ }
  }

  private Win32() {}
}
