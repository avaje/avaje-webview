package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Typed wrapper around {@code ICoreWebView2Settings}. */
final class ComWebView2Settings {

  private static final String IID_SETTINGS2 = "EE9A0F68-F46C-4E32-AC23-EF8CAC224D2A";

  private static final int VT_QUERY_INTERFACE = 0;
  private static final int VT_RELEASE = 2;

  /** {@code ICoreWebView2Settings2::put_UserAgent}, after the 21 slots it inherits. */
  private static final int VT_PUT_USER_AGENT = 22;

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

  /**
   * Calls {@code ICoreWebView2Settings2::put_UserAgent}, reached through {@code QueryInterface}
   * since it isn't part of the original settings interface.
   *
   * @param userAgent the user agent encoded as UTF-16LE before passing to COM
   * @throws IllegalStateException if the WebView2 runtime rejects the call
   */
  void putUserAgent(String userAgent) {
    try (var a = Arena.ofConfined()) {
      final var pSettings2 = a.allocate(ADDRESS);
      final var queryInterface =
          Win32.resolve(
              ptr, VT_QUERY_INTERFACE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      final var found =
          (int) queryInterface.invokeExact(ptr, Win32.guid(a, IID_SETTINGS2), pSettings2);
      check(found, "QueryInterface(ICoreWebView2Settings2)");

      final var settings2 = pSettings2.get(ADDRESS, 0);
      try {
        final var put =
            Win32.resolve(
                settings2, VT_PUT_USER_AGENT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        final var hr =
            (int) put.invokeExact(settings2, a.allocateFrom(userAgent, StandardCharsets.UTF_16LE));
        check(hr, "put_UserAgent");
      } finally {
        release(settings2);
      }
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Drops the reference {@code get_Settings} handed out. */
  void release() {
    try {
      release(ptr);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private static void release(MemorySegment comObject) throws Throwable {
    final var release =
        Win32.resolve(comObject, VT_RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS));
    final var _ = (int) release.invokeExact(comObject);
  }

  private static void check(int hr, String call) {
    if (hr != 0) {
      throw new IllegalStateException(call + " failed: 0x" + Integer.toHexString(hr));
    }
  }
}
