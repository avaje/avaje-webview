package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/** Typed wrapper around {@code ICoreWebView2Controller}. */
final class ComController {

  private final MemorySegment ptr;
  private final MethodHandle putIsVisible;
  private final MethodHandle putBounds;
  private final MethodHandle moveFocus;
  private final MethodHandle close;
  private final MethodHandle getCoreWebView2;

  /**
   * Binds all vtable method handles eagerly from the given {@code ICoreWebView2Controller} COM
   * pointer.
   *
   * @param ptr COM object pointer; must remain valid for the lifetime of this wrapper
   */
  ComController(MemorySegment ptr) {
    this.ptr = ptr;
    putIsVisible = Win32.resolve(ptr, 4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    putBounds = Win32.resolve(ptr, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    moveFocus = Win32.resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    close = Win32.resolve(ptr, 24, FunctionDescriptor.of(JAVA_INT, ADDRESS));
    getCoreWebView2 = Win32.resolve(ptr, 25, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  }

  /**
   * Calls {@code ICoreWebView2Controller::put_IsVisible}.
   *
   * @param visible {@code true} to show the WebView2 surface, {@code false} to hide it
   * @return HRESULT from the COM call
   */
  int putIsVisible(boolean visible) {
    try {
      return (int) putIsVisible.invokeExact(ptr, visible ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2Controller::put_Bounds}.
   *
   * @param rect pointer to a Win32 {@code RECT} struct describing the controller bounds in client
   *     coordinates
   */
  void putBounds(MemorySegment rect) {
    try {
      final var _ = (int) putBounds.invokeExact(ptr, rect);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2Controller::MoveFocus}.
   *
   * @param reason {@code COREWEBVIEW2_MOVE_FOCUS_REASON} constant (0=Programmatic, 1=Next,
   *     2=Previous)
   */
  void moveFocus(int reason) {
    try {
      final var _ = (int) moveFocus.invokeExact(ptr, reason);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2Controller::Close}.
   *
   * <p>Tears down the WebView2 controller and releases its resources. HRESULT is swallowed; errors
   * here are non-actionable during shutdown.
   */
  void close() {
    try {
      final var _ = (int) close.invokeExact(ptr);
    } catch (final Throwable ignored) {
    }
  }

  /**
   * Calls {@code ICoreWebView2Controller::get_CoreWebView2}.
   *
   * @return the raw {@code ICoreWebView2} COM pointer
   * @throws RuntimeException if the HRESULT is non-zero
   */
  MemorySegment getCoreWebView2() {
    try (var a = Arena.ofConfined()) {
      final var pWv2 = a.allocate(ADDRESS);
      final var hr = (int) getCoreWebView2.invokeExact(ptr, pWv2);
      if (hr != 0)
        throw new RuntimeException("get_CoreWebView2 failed: 0x" + Integer.toHexString(hr));
      return pWv2.get(ADDRESS, 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
