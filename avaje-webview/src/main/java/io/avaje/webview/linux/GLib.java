package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFM bindings for GLib ({@code libglib-2.0}) and GObject ({@code libgobject-2.0}).
 *
 * <p><b>Role of GLib vs GObject:</b> GLib is the low-level utility library — it owns the main event
 * loop ({@code GMainContext}/{@code GMainLoop}), the idle/timeout source scheduling API, and the
 * memory allocator ({@code g_malloc}/{@code g_free}). GObject sits on top of GLib and provides the
 * reference-counted object system that GTK and WebKitGTK build on. They ship as separate shared
 * libraries ({@code libglib-2.0.so.0} and {@code libgobject-2.0.so.0}), so we load both and chain
 * their lookups.
 *
 * <p><b>Thread safety:</b> The GLib main context (and therefore GtkWebView) is single-threaded.
 * Callers must not invoke any handle that touches GTK/GObject state from threads other than the one
 * that called {@code gtk_init}.
 */
final class GLib {

  /**
   * GLib idle-source priority for cross-thread dispatch wakeups.
   *
   * <p>We use {@code G_PRIORITY_HIGH_IDLE} (100) so our dispatch callback fires <em>before</em> the
   * GTK redraw pass but <em>after</em> any pending I/O at priority 0. This keeps the UI responsive
   * without starving network/file events.
   */
  static final int G_PRIORITY_HIGH_IDLE = 100;

  /**
   * Bit flag for {@code g_signal_handlers_disconnect_matched}: match handlers by their {@code data}
   * pointer.
   *
   * <p>{@code GSignalMatchType} is an enum whose values are individual bits so they can be ORed
   * together. Bit 4 ({@code 1 << 4 = 16}) is {@code G_SIGNAL_MATCH_DATA}. When passed to {@code
   * g_signal_handlers_disconnect_matched}, only the {@code data} argument is compared; all other
   * match criteria (signal id, detail quark, closure, function pointer) are ignored.
   */
  static final int G_SIGNAL_MATCH_DATA = 1 << 4;

  /**
   * {@code FunctionDescriptor} for {@code gboolean(*func)(gpointer data)}.
   *
   * <p>The function must return {@code G_SOURCE_REMOVE} (0) to remove itself from the event loop
   * after one invocation, or {@code G_SOURCE_CONTINUE} (1) to keep firing. We always return 0
   * because we add a fresh idle source per {@link #gIdleAddFull} call — there is no need to reuse
   * the same source.
   */
  static final FunctionDescriptor GSOURCE_FUNC_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /** Lookup for {@code libglib-2.0.so.0} */
  private static final SymbolLookup GLIB_LIB =
      SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());

  /** Lookup for {@code libgobject-2.0.so.0}. */
  private static final SymbolLookup GOBJECT_LIB =
      SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());

  /**
   * Combined lookup. We chain them so a single {@link #downcall} helper resolves symbols from
   * either library without needing to know which one owns it.
   */
  private static final SymbolLookup LOOKUP = GLIB_LIB.or(GOBJECT_LIB);

  /**
   * {@code g_main_context_iteration(GMainContext* context, gboolean may_block) -> gboolean}
   *
   * <p>Runs a single iteration of the GLib main loop, processing all pending events. This is how we
   * drive the GTK event loop manually instead of calling the blocking {@code g_main_loop_run()}: by
   * looping while there are open windows we can exit the loop cleanly when the last window closes.
   *
   * <ul>
   *   <li>{@code context = NULL} — use the thread-default context, which is the one {@code
   *       gtk_init} installed on the calling thread.
   *   <li>{@code may_block = 1} — park the OS thread in {@code epoll_wait} (or equivalent) until at
   *       least one event arrives; saves CPU vs. busy-polling.
   *   <li>{@code may_block = 0} — process only events already queued; return immediately if none.
   * </ul>
   *
   * <p>Returns non-zero if any events were processed, zero if the context had no pending sources
   * (only relevant when {@code may_block = 0}).
   */
  static final MethodHandle G_MAIN_CONTEXT_ITERATION =
      downcall("g_main_context_iteration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code g_idle_add_full(gint priority, GSourceFunc function, gpointer data, GDestroyNotify
   * notify) -> guint}
   *
   * <p>Schedules {@code function} to run on the GLib main context at idle time with the given
   * {@code priority}. Returns a source ID (we discard it because we never cancel early; the
   * function removes itself by returning {@code G_SOURCE_REMOVE}).
   *
   * <p>We use this to wake the GTK thread from a non-GTK thread: queue the work in {@code
   * pendingDispatches}, then call {@code g_idle_add_full} with our {@code dispatchStub} upcall.
   * When the GTK thread's next loop iteration fires the stub, it drains the queue.
   *
   * <ul>
   *   <li>{@code priority} — {@link #G_PRIORITY_HIGH_IDLE} so dispatches fire before redraws.
   *   <li>{@code function} — a Panama upcall stub ({@link MemorySegment}) pointing to {@code
   *       GtkWebView#drainDispatchQueue}.
   *   <li>{@code data} — we pass {@code NULL}; the stub already captures {@code this} via {@code
   *       MethodHandle#bindTo}.
   *   <li>{@code notify} — {@code NULL}; nothing to free when the source is removed.
   * </ul>
   */
  static final MethodHandle G_IDLE_ADD_FULL =
      downcall(
          "g_idle_add_full", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code g_free(gpointer mem) -> void}
   *
   * <p>Frees memory that was allocated by GLib's own allocator (which may not be the system {@code
   * malloc}). <em>Must</em> be used for any {@code gchar*} or other pointer returned by a
   * GLib/GObject/WebKitGTK function that says the caller owns it — using Java's garbage collector
   * or the system {@code free()} would corrupt the GLib heap.
   */
  static final MethodHandle G_FREE = downcall("g_free", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code g_object_ref_sink(gpointer object) -> gpointer}
   *
   * <p>Claim ownership of a GObject that has a "floating" reference.
   *
   * <p>When GObject subclasses are constructed (e.g. {@code webkit_web_view_new()}), they start
   * life with a reference count of 1 <em>and</em> a floating flag set. The floating flag signals "I
   * haven't been adopted by a container yet." If a GTK container (like {@code GtkWindow}) takes
   * ownership via {@code gtk_window_set_child}, it calls {@code ref_sink} internally to clear the
   * float and increment the count — then when the container is destroyed, it unrefs and the child
   * is freed.
   *
   * <p>We create the {@code WebKitWebView} separately and add it as the window's child, but we also
   * want to hold our own reference so we control the lifetime. Calling {@code ref_sink} atomically
   * clears the floating flag and increments the reference count, converting the "we own this"
   * ownership from implicit (floating) to explicit (real ref). We then call {@code g_object_unref}
   * in {@code close()} to release our reference. Without this, GTK's internal reference management
   * could free the WebView prematurely.
   *
   * <p>Returns the same pointer passed in (useful for call-chaining, hence the {@link
   * MemorySegment} return type in {@link #gObjectRefSink}).
   */
  static final MethodHandle G_OBJECT_REF_SINK =
      downcall("g_object_ref_sink", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code g_object_unref(gpointer object) -> void}
   *
   * <p>Decrements the reference count of a GObject. When the count reaches zero, GObject runs the
   * object's {@code dispose} and {@code finalize} methods and frees the underlying memory. Must be
   * paired with every {@link #G_OBJECT_REF_SINK} or explicit {@code g_object_ref} call.
   */
  static final MethodHandle G_OBJECT_UNREF =
      downcall("g_object_unref", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code g_object_set(gpointer object, const gchar* first_property_name, ...) -> void}
   *
   * <p>Sets one or more GObject properties by name using a varargs C call. The argument list must
   * be terminated by a {@code NULL} sentinel — GLib reads pairs of (name, value) until it sees the
   * terminator.
   *
   * <p>Used by {@link LinuxHelper#setWindowAppearance} to flip {@code
   * gtk-application-prefer-dark-theme} on the singleton {@code GtkSettings} object.
   */
  static final MethodHandle G_OBJECT_SET =
      downcall(
          "g_object_set",
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS).appendArgumentLayouts(JAVA_INT, ADDRESS));

  /**
   * {@code g_signal_connect_data(gpointer instance, const gchar* detailed_signal, GCallback
   * c_handler, gpointer data, GClosureNotify destroy_data, GConnectFlags connect_flags) -> gulong}
   *
   * <p>Connects a C callback to a GObject signal. Returns a handler ID (we discard it here;
   * disconnection is done by data pointer via {@link #G_SIGNAL_HANDLERS_DISCONNECT_MATCHED}).
   *
   * <p>We use {@code connect_data} rather than the simpler macro {@code g_signal_connect} because
   * the macro expands to {@code connect_data} anyway, and calling the real function directly gives
   * us a stable, unambiguous Panama target:
   *
   * <ul>
   *   <li>5th arg {@code destroy_data} ({@code GClosureNotify}) — {@code NULL}; we don't need
   *       notification when the closure is destroyed.
   *   <li>6th arg {@code connect_flags = 0} — default behavior (not swapped, not after-signal).
   *       {@code G_CONNECT_SWAPPED} would swap instance and data; {@code G_CONNECT_AFTER} would
   *       fire after the default handler. Neither is needed here.
   * </ul>
   *
   * <p>{@code detailed_signal} may include a detail suffix, e.g. {@code
   * "script-message-received::__webview__"}, which scopes delivery to messages from the named
   * handler only.
   */
  static final MethodHandle G_SIGNAL_CONNECT_DATA =
      downcall(
          "g_signal_connect_data",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

  /**
   * {@code g_signal_handlers_disconnect_matched(gpointer instance, GSignalMatchType mask, guint
   * signal_id, GQuark detail, GClosure* closure, gpointer func, gpointer data) -> guint}
   *
   * <p>Disconnects all signal handlers on {@code instance} whose attributes match the given
   * criteria. Only bits set in {@code mask} are compared; others are ignored.
   *
   * <p>We always pass {@code mask = G_SIGNAL_MATCH_DATA} ({@link #G_SIGNAL_MATCH_DATA}), meaning
   * only the {@code data} pointer is compared. This lets us disconnect <em>all</em> handlers we
   * registered (regardless of which signal or which function) by passing the same {@code data}
   * value we used in {@link #G_SIGNAL_CONNECT_DATA}.
   *
   * <p>Returns the number of handlers actually disconnected.
   */
  static final MethodHandle G_SIGNAL_HANDLERS_DISCONNECT_MATCHED =
      downcall(
          "g_signal_handlers_disconnect_matched",
          FunctionDescriptor.of(
              JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(sym)
            .orElseThrow(() -> new UnsatisfiedLinkError("GLib symbol not found: " + sym)),
        desc);
  }

  /**
   * Runs one iteration of the GLib main context, blocking until an event arrives if {@code mayBlock
   * != 0}.
   *
   * <p>We drive the event loop manually in a {@code while (openWindows.get() > 0)} loop so we can
   * exit as soon as the last window closes, unlike {@code g_main_loop_run()} which has no
   * early-exit hook.
   *
   * @param ctx the {@code GMainContext*} to iterate, or {@code NULL} for the thread-default
   * @param mayBlock {@code 1} to block until an event is available; {@code 0} to poll once
   */
  static void gMainContextIteration(MemorySegment ctx, int mayBlock) {
    try {
      final var _ = (int) G_MAIN_CONTEXT_ITERATION.invokeExact(ctx, mayBlock);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Schedules {@code fn} to be called on the GTK main thread at idle time.
   *
   * <p>Safe to call from any thread. The stub fires on the GTK thread during the next main-loop
   * iteration at or after {@link #G_PRIORITY_HIGH_IDLE}.
   *
   * @param priority scheduling priority (lower = higher priority); use {@link
   *     #G_PRIORITY_HIGH_IDLE}
   * @param fn a {@code GSourceFunc} upcall stub — a C function pointer created by Panama
   * @param data user data passed to {@code fn}; we always pass {@code NULL} because the stub
   *     captures {@code this} via {@code bindTo}
   * @param notify {@code GDestroyNotify} called when source is removed; always {@code NULL}
   */
  static void gIdleAddFull(
      int priority, MemorySegment fn, MemorySegment data, MemorySegment notify) {
    try {
      final var _ = (int) G_IDLE_ADD_FULL.invokeExact(priority, fn, data, notify);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Frees a GLib-allocated block of memory.
   *
   * <p>Must be used (not the JVM GC) for any pointer returned by a native function that documents
   * the caller as responsible for freeing it. In practice: the {@code gchar*} returned by {@code
   * jsc_value_to_string}.
   *
   * @param ptr the pointer to free; must have been allocated by GLib's allocator
   */
  static void gFree(MemorySegment ptr) {
    try {
      G_FREE.invokeExact(ptr);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Claims ownership of a floating GObject by clearing its floating flag.
   *
   * <p>Returns the same pointer so callers can chain: {@code var wv =
   * gObjectRefSink(webkitWebViewNew())}. Without this call, GTK's container adoption logic may free
   * the object before we expect.
   *
   * @param obj a GObject with a floating reference (e.g. freshly constructed with {@code
   *     webkit_web_view_new})
   * @return the same {@code obj} pointer (refcount is now 1, floating flag cleared)
   * @see #G_OBJECT_REF_SINK
   */
  static MemorySegment gObjectRefSink(MemorySegment obj) {
    try {
      return (MemorySegment) G_OBJECT_REF_SINK.invokeExact(obj);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Releases one reference to a GObject.
   *
   * <p>Must be called exactly once for each {@link #gObjectRefSink} or {@code g_object_ref} call.
   * When the count hits zero the object is destroyed.
   *
   * @param obj a live GObject pointer; undefined behavior if already freed
   */
  static void gObjectUnref(MemorySegment obj) {
    try {
      G_OBJECT_UNREF.invokeExact(obj);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sets a single named GObject property to an integer value.
   *
   * <p>{@code g_object_set} is a varargs C function; this wrapper fixes the arity to {@code
   * (object, name, int_value, NULL)} for the one-property case we always use. The {@code
   * terminator} argument must be {@link MemorySegment#NULL} — GLib reads the argument list until it
   * sees a {@code NULL} name.
   *
   * @param obj the GObject whose property to set
   * @param propertyName a null-terminated UTF-8 C string naming the property
   * @param value the new integer value for the property
   * @param terminator must always be {@link MemorySegment#NULL}
   */
  static void gObjectSet(
      MemorySegment obj, MemorySegment propertyName, int value, MemorySegment terminator) {
    try {
      G_OBJECT_SET.invokeExact(obj, propertyName, value, terminator);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Connects a C callback upcall stub to a named signal on a GObject instance.
   *
   * <p>The signal name string only needs to live for the duration of this call, so we allocate it
   * in a {@code Arena.ofConfined()} that is closed by the try-with-resources before the method
   * returns. This avoids leaking a permanent off-heap buffer for a transient C string.
   *
   * <p>The returned handler ID is discarded; if disconnection is ever needed use {@link
   * #gSignalHandlersDisconnectByData}.
   *
   * @param instance the GObject to connect the signal on
   * @param signal the signal name, optionally with a detail suffix (e.g. {@code
   *     "script-message-received::__webview__"})
   * @param callback a Panama upcall stub matching the signal's C callback signature
   * @param data user data pointer passed to {@code callback}; we use {@code NULL} because the stub
   *     already captures the instance via {@code bindTo}
   */
  static void gSignalConnect(
      MemorySegment instance, String signal, MemorySegment callback, MemorySegment data) {
    try (var a = Arena.ofConfined()) {
      final var _ =
          (long)
              G_SIGNAL_CONNECT_DATA.invokeExact(
                  instance, a.allocateFrom(signal), callback, data, MemorySegment.NULL, 0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Disconnects all signal handlers on {@code instance} whose {@code data} pointer matches.
   *
   * <p>The mask is fixed to {@link #G_SIGNAL_MATCH_DATA}, so only the {@code data} argument is
   * compared. All other filter arguments ({@code signal_id = 0}, {@code detail = 0}, {@code closure
   * = NULL}, {@code func = NULL}) are ignored — GLib skips any field whose corresponding mask bit
   * is not set.
   *
   * @param instance the GObject from which to disconnect handlers
   * @param data the {@code data} pointer value that was passed to {@link #gSignalConnect}
   */
  static void gSignalHandlersDisconnectByData(MemorySegment instance, MemorySegment data) {
    try {
      final var _ =
          (int)
              G_SIGNAL_HANDLERS_DISCONNECT_MATCHED.invokeExact(
                  instance,
                  G_SIGNAL_MATCH_DATA,
                  // signal_id=0, detail=0: don't filter by signal identity
                  0,
                  0,
                  // closure=NULL, func=NULL: don't filter by closure or function pointer
                  MemorySegment.NULL,
                  MemorySegment.NULL,
                  data);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private GLib() {}
}
