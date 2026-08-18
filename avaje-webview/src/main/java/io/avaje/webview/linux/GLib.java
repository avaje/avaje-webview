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
 * <p>The GLib main context is single threaded, so no handle here that touches GTK or GObject state
 * may be called from a thread other than the one that called {@code gtk_init}.
 */
final class GLib {

  /**
   * Idle-source priority for cross-thread dispatch wakeups. At 100 the dispatch callback runs
   * ahead of the GTK redraw pass but behind pending I/O at priority 0, which keeps the UI moving
   * without starving network or file events.
   */
  static final int G_PRIORITY_HIGH_IDLE = 100;

  /**
   * Bit flag for {@code g_signal_handlers_disconnect_matched}: match handlers by their {@code data}
   * pointer.
   *
   * <p>{@code GSignalMatchType} values are single bits meant to be ORed together, and bit 4 is
   * {@code G_SIGNAL_MATCH_DATA}. On its own it leaves signal id, detail quark, closure and function
   * pointer out of the comparison.
   */
  private static final int G_SIGNAL_MATCH_DATA = 1 << 4;

  /**
   * {@code FunctionDescriptor} for {@code gboolean(*func)(gpointer data)}.
   *
   * <p>Returning {@code G_SOURCE_REMOVE} (0) takes the source back off the event loop after one
   * call, {@code G_SOURCE_CONTINUE} (1) keeps it firing. Always 0 here, since {@link #gIdleAddFull}
   * adds a fresh source each time.
   */
  static final FunctionDescriptor GSOURCE_FUNC_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);

  private static final Linker LINKER = Linker.nativeLinker();

  /** Lookup for {@code libglib-2.0.so.0} */
  private static final SymbolLookup GLIB_LIB =
      SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());

  /** Lookup for {@code libgobject-2.0.so.0}. */
  private static final SymbolLookup GOBJECT_LIB =
      SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());

  /** Chained so {@link #downcall} resolves a symbol without caring which library owns it. */
  private static final SymbolLookup LOOKUP = GLIB_LIB.or(GOBJECT_LIB);

  /**
   * {@code g_main_context_iteration(GMainContext* context, gboolean may_block) -> gboolean}
   *
   * <p>Runs one iteration of the GLib main loop, processing whatever events are pending. Driving
   * the loop this way rather than through the blocking {@code g_main_loop_run()} leaves room to
   * stop as soon as the last window closes.
   *
   * <ul>
   *   <li>{@code context = NULL}: use the thread-default context, which is the one {@code
   *       gtk_init} installed on the calling thread.
   *   <li>{@code may_block = 1}: park the OS thread in {@code epoll_wait} (or equivalent) until at
   *       least one event arrives.
   *   <li>{@code may_block = 0}: process only events already queued; return immediately if none.
   * </ul>
   *
   * <p>Returns non-zero if any events were processed, zero if the context had no pending sources
   * (only relevant when {@code may_block = 0}).
   */
  private static final MethodHandle G_MAIN_CONTEXT_ITERATION =
      downcall("g_main_context_iteration", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

  /**
   * {@code g_idle_add_full(gint priority, GSourceFunc function, gpointer data, GDestroyNotify
   * notify) -> guint}
   *
   * <p>Schedules {@code function} to run on the GLib main context at idle time with the given
   * {@code priority}. The returned source ID is discarded, as nothing is ever cancelled early and
   * the function removes itself by returning {@code G_SOURCE_REMOVE}.
   *
   * <p>This is how the GTK thread gets woken from elsewhere: queue the work in {@code
   * pendingDispatches}, add an idle source for {@code dispatchStub}, and the next loop iteration
   * drains the queue.
   *
   * <ul>
   *   <li>{@code priority}: {@link #G_PRIORITY_HIGH_IDLE} so dispatches fire before redraws.
   *   <li>{@code function}: a Panama upcall stub ({@link MemorySegment}) pointing to {@code
   *       GtkWebView#drainDispatchQueue}.
   *   <li>{@code data}: {@code NULL}, since the stub already captures {@code this} via {@code
   *       MethodHandle#bindTo}.
   *   <li>{@code notify}: {@code NULL}; nothing to free when the source is removed.
   * </ul>
   */
  private static final MethodHandle G_IDLE_ADD_FULL =
      downcall(
          "g_idle_add_full", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code g_free(gpointer mem) -> void}
   *
   * <p>Frees memory from GLib's own allocator, and the only correct way to release a {@code
   * gchar*} or other pointer a GLib, GObject or WebKitGTK function hands to the caller. The system
   * {@code free()} would corrupt the GLib heap.
   */
  private static final MethodHandle G_FREE = downcall("g_free", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code g_object_ref_sink(gpointer object) -> gpointer}
   *
   * <p>Claims ownership of a GObject holding a floating reference. A freshly constructed GObject
   * such as {@code webkit_web_view_new()} starts at refcount 1 with the floating flag set, meaning
   * no container has adopted it yet. Sinking clears that flag and leaves a real reference behind.
   *
   * <p>The {@code WebKitWebView} is built here and handed to the window as its child, so without a
   * sink the only reference would be GTK's, and the view could go away underneath us. The matching
   * {@code g_object_unref} happens in {@code close()}.
   *
   * <p>Returns the pointer it was given, which is why {@link #gObjectRefSink} returns a {@link
   * MemorySegment}.
   */
  private static final MethodHandle G_OBJECT_REF_SINK =
      downcall("g_object_ref_sink", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code g_object_unref(gpointer object) -> void}
   *
   * <p>Drops one reference. At zero GObject runs {@code dispose} and {@code finalize} and frees
   * the memory. Pairs with every {@link #G_OBJECT_REF_SINK} or {@code g_object_ref}.
   */
  private static final MethodHandle G_OBJECT_UNREF =
      downcall("g_object_unref", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code g_object_set(gpointer object, const gchar* first_property_name, ...) -> void}
   *
   * <p>Sets one or more GObject properties by name using a varargs C call. The argument list must
   * be terminated by a {@code NULL} sentinel.
   *
   * <p>Used by {@link LinuxHelper#setWindowAppearance} to flip {@code
   * gtk-application-prefer-dark-theme} on the singleton {@code GtkSettings} object.
   */
  private static final MethodHandle G_OBJECT_SET =
      downcall(
          "g_object_set",
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS).appendArgumentLayouts(JAVA_INT, ADDRESS));

  /**
   * {@code g_signal_connect_data(gpointer instance, const gchar* detailed_signal, GCallback
   * c_handler, gpointer data, GClosureNotify destroy_data, GConnectFlags connect_flags) -> gulong}
   *
   * <p>Connects a C callback to a GObject signal. The handler ID is discarded, since disconnection
   * goes by data pointer through {@link #G_SIGNAL_HANDLERS_DISCONNECT_MATCHED}.
   *
   * <ul>
   *   <li>5th arg {@code destroy_data} ({@code GClosureNotify}): {@code NULL}, nothing needs to
   *       know when the closure is destroyed.
   *   <li>6th arg {@code connect_flags = 0}: default behavior (not swapped, not after-signal).
   *       {@code G_CONNECT_SWAPPED} would swap instance and data; {@code G_CONNECT_AFTER} would
   *       fire after the default handler. Neither is needed here.
   * </ul>
   *
   * <p>{@code detailed_signal} may include a detail suffix, e.g. {@code
   * "script-message-received::__webview__"}, which scopes delivery to messages from the named
   * handler only.
   */
  private static final MethodHandle G_SIGNAL_CONNECT_DATA =
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
   * <p>Returns the number of handlers actually disconnected.
   */
  private static final MethodHandle G_SIGNAL_HANDLERS_DISCONNECT_MATCHED =
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
   * <p>Called from a {@code while (openWindows.get() > 0)} loop, which is what lets the event loop
   * stop with the last window. {@code g_main_loop_run()} offers no such early exit.
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
   * @param fn a {@code GSourceFunc} upcall stub, a C function pointer created by Panama
   * @param data user data passed to {@code fn}, always {@code NULL} here as the stub captures
   *     {@code this} via {@code bindTo}
   * @param notify {@code GDestroyNotify} called when source is removed; always {@code NULL}
   */
  static void gIdleAddFull(MemorySegment fn, MemorySegment data, MemorySegment notify) {
    try {
      final var _ = (int) G_IDLE_ADD_FULL.invokeExact(G_PRIORITY_HIGH_IDLE, fn, data, notify);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Frees a GLib-allocated block of memory.
   *
   * <p>Required for any pointer a native function hands over with ownership. In practice that is
   * the {@code gchar*} from {@code jsc_value_to_string}.
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
   * <p>Returns the same pointer so calls can chain, as in {@code var wv =
   * gObjectRefSink(webkitWebViewNew())}. Skip it and GTK's container adoption can free the object
   * sooner than expected.
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
   * @param obj a live GObject pointer, undefined behaviour if it has already been freed
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
   * (object, name, int_value, NULL)} for the one-property case in use here. The {@code terminator}
   * argument must be {@link MemorySegment#NULL}, since GLib reads the argument list until it sees
   * a {@code NULL} name.
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
   * @param instance the GObject to connect the signal on
   * @param signal the signal name, optionally with a detail suffix (e.g. {@code
   *     "script-message-received::__webview__"})
   * @param callback a Panama upcall stub matching the signal's C callback signature
   * @param data user data pointer passed to {@code callback}, {@code NULL} here as the stub
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
   * <p>The mask is fixed to {@link #G_SIGNAL_MATCH_DATA}, so only {@code data} is compared and the
   * other filters ({@code signal_id}, {@code detail}, {@code closure}, {@code func}) are ignored.
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
                  // signal_id, detail: no filtering on signal identity
                  0,
                  0,
                  // closure, func: no filtering on closure or function pointer
                  MemorySegment.NULL,
                  MemorySegment.NULL,
                  data);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private GLib() {}
}
