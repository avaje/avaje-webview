package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/** Typed wrapper around {@code ICoreWebView2Settings}. */
final class ComWebView2Settings {

  private final MemorySegment ptr;
  private final MethodHandle putIsStatusBarEnabled;
  private final MethodHandle putAreDevToolsEnabled;

  /**
   * Binds all vtable method handles eagerly from the given {@code ICoreWebView2Settings} COM
   * pointer.
   *
   * @param ptr COM object pointer; must remain valid for the lifetime of this wrapper
   */
  ComWebView2Settings(MemorySegment ptr) {
    this.ptr = ptr;
    putIsStatusBarEnabled =
        Win32.resolve(ptr, 10, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    putAreDevToolsEnabled =
        Win32.resolve(ptr, 12, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  }

  /**
   * Calls {@code ICoreWebView2Settings::put_IsStatusBarEnabled}.
   *
   * @param enabled {@code false} to hide the status bar shown in the lower-left corner on hover
   */
  void putIsStatusBarEnabled(boolean enabled) {
    try {
      final var _ = (int) putIsStatusBarEnabled.invokeExact(ptr, enabled ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2Settings::put_AreDevToolsEnabled}.
   *
   * @param enabled {@code false} to prevent the user from opening the browser developer tools panel
   */
  void putAreDevToolsEnabled(boolean enabled) {
    try {
      final var _ = (int) putAreDevToolsEnabled.invokeExact(ptr, enabled ? 1 : 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
