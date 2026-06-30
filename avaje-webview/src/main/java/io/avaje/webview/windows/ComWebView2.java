package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** Typed wrapper around {@code ICoreWebView2}. */
final class ComWebView2 {

  private final MemorySegment ptr;
  private final MethodHandle getSettings;
  private final MethodHandle navigate;
  private final MethodHandle navigateToString;
  private final MethodHandle addWebMessageRcvd;
  private final MethodHandle addPermissionReq;
  private final MethodHandle addScriptOnDoc;
  private final MethodHandle removeScript;
  private final MethodHandle executeScript;

  /**
   * Binds all vtable method handles eagerly from the given {@code ICoreWebView2} COM pointer.
   *
   * <p>{@code add_ProcessFailed} is excluded from eager binding because its vtable index (25)
   * clashes with {@code add_WebMessageReceived} in older WebView2 releases; it is resolved inline
   * at call time to avoid the ambiguity.
   *
   * @param ptr COM object pointer; must remain valid for the lifetime of this wrapper
   */
  ComWebView2(MemorySegment ptr) {
    this.ptr = ptr;
    getSettings = Win32.resolve(ptr, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    navigate = Win32.resolve(ptr, 5, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    navigateToString = Win32.resolve(ptr, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    addPermissionReq =
        Win32.resolve(ptr, 23, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    addScriptOnDoc =
        Win32.resolve(ptr, 27, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    removeScript = Win32.resolve(ptr, 28, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    executeScript =
        Win32.resolve(ptr, 29, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    addWebMessageRcvd =
        Win32.resolve(ptr, 34, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  }

  /**
   * Calls {@code ICoreWebView2::get_Settings}
   *
   * @return a {@link ComWebView2Settings} wrapper, or {@code null} if the COM pointer is null
   */
  ComWebView2Settings getSettings() {
    try (var a = Arena.ofConfined()) {
      final var pSettings = a.allocate(ADDRESS);
      final var _ = (int) getSettings.invokeExact(ptr, pSettings);
      final var s = pSettings.get(ADDRESS, 0);
      return s.address() != 0 ? new ComWebView2Settings(s) : null;
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::Navigate}
   *
   * @param url URL to navigate to; encoded as UTF-16LE before passing to COM
   */
  void navigate(String url) {
    try (var a = Arena.ofConfined()) {
      final var _ = (int) navigate.invokeExact(ptr, a.allocateFrom(url, StandardCharsets.UTF_16LE));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::NavigateToString}
   *
   * @param html HTML content to display; encoded as UTF-16LE before passing to COM
   */
  void navigateToString(String html) {
    try (var a = Arena.ofConfined()) {
      final var _ =
          (int) navigateToString.invokeExact(ptr, a.allocateFrom(html, StandardCharsets.UTF_16LE));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::add_WebMessageReceived}
   *
   * @param handler COM object implementing {@code ICoreWebView2WebMessageReceivedEventHandler};
   *     must be a FFM upcall stub or COM-compatible vtable pointer
   */
  void addWebMessageReceived(MemorySegment handler) {
    try (var a = Arena.ofConfined()) {
      final var pToken = a.allocate(JAVA_LONG);
      final var _ = (int) addWebMessageRcvd.invokeExact(ptr, handler, pToken);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::add_PermissionRequested}.
   *
   * @param handler COM object implementing {@code ICoreWebView2PermissionRequestedEventHandler};
   *     must be a Panama upcall stub or COM-compatible vtable pointer
   */
  void addPermissionRequested(MemorySegment handler) {
    try (var a = Arena.ofConfined()) {
      final var pToken = a.allocate(JAVA_LONG);
      final var _ = (int) addPermissionReq.invokeExact(ptr, handler, pToken);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::add_ProcessFailed}.
   *
   * <p>Vtable handle is resolved inline rather than at construction to avoid index conflicts with
   * other interfaces that differ across WebView2 versions.
   *
   * @param handler COM object implementing {@code ICoreWebView2ProcessFailedEventHandler}
   */
  void addProcessFailed(MemorySegment handler) {
    try (var a = Arena.ofConfined()) {
      final var pToken = a.allocate(JAVA_LONG);
      final var mh =
          Win32.resolve(ptr, 25, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      final var _ = (int) mh.invokeExact(ptr, handler, pToken);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::AddScriptToExecuteOnDocumentCreated}.
   *
   * <p>The script runs on every subsequent page load. The completion callback ({@code handler})
   * receives the opaque script ID used to remove the script later.
   *
   * @param js JavaScript source; encoded as UTF-16LE before passing to COM
   * @param handler COM callback that receives the assigned script ID; may be {@code
   *     MemorySegment.NULL}
   * @return HRESULT from the COM call
   */
  int addScriptToExecuteOnDocumentCreated(String js, MemorySegment handler) {
    try (var a = Arena.ofConfined()) {
      return (int)
          addScriptOnDoc.invokeExact(ptr, a.allocateFrom(js, StandardCharsets.UTF_16LE), handler);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::RemoveScriptToExecuteOnDocumentCreated}.
   *
   * @param id opaque script ID string returned via the callback passed to {@link
   *     #addScriptToExecuteOnDocumentCreated}; encoded as UTF-16LE before passing to COM
   */
  void removeScriptToExecuteOnDocumentCreated(String id) {
    try (var a = Arena.ofConfined()) {
      final var _ =
          (int) removeScript.invokeExact(ptr, a.allocateFrom(id, StandardCharsets.UTF_16LE));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Calls {@code ICoreWebView2::ExecuteScript}).
   *
   * <p>Executes JavaScript in the top-level document. No completion callback is registered ({@code
   * NULL} is passed for the handler).
   *
   * @param js JavaScript source; encoded as UTF-16LE before passing to COM
   */
  void executeScript(String js) {
    try (var a = Arena.ofConfined()) {
      final var _ =
          (int)
              executeScript.invokeExact(
                  ptr, a.allocateFrom(js, StandardCharsets.UTF_16LE), MemorySegment.NULL);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
