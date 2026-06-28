package io.avaje.webview.linux;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

final class GLib {

    static final int G_PRIORITY_HIGH_IDLE = 100;
    static final int G_SIGNAL_MATCH_DATA = 1 << 4;

    static final FunctionDescriptor GSOURCE_FUNC_DESC   = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor GDESTROY_NOTIFY_DESC = FunctionDescriptor.ofVoid(ADDRESS);

    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup GLIB_LIB =
            SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());
    private static final SymbolLookup GOBJECT_LIB =
            SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());
    private static final SymbolLookup LOOKUP = GLIB_LIB.or(GOBJECT_LIB);

    static final MethodHandle G_MAIN_CONTEXT_ITERATION = downcall("g_main_context_iteration",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle G_IDLE_ADD_FULL = downcall("g_idle_add_full",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle G_FREE = downcall("g_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_OBJECT_REF_SINK = downcall("g_object_ref_sink",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle G_OBJECT_UNREF = downcall("g_object_unref",
            FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle G_OBJECT_SET = downcall("g_object_set",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS).appendArgumentLayouts(JAVA_INT, ADDRESS));
    static final MethodHandle G_SIGNAL_CONNECT_DATA = downcall("g_signal_connect_data",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle G_SIGNAL_HANDLERS_DISCONNECT_MATCHED =
            downcall("g_signal_handlers_disconnect_matched",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("GLib symbol not found: " + sym)),
                desc);
    }

    static void gMainContextIteration(MemorySegment ctx, int mayBlock) {
        try { int _ = (int) G_MAIN_CONTEXT_ITERATION.invokeExact(ctx, mayBlock); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gIdleAddFull(int priority, MemorySegment fn, MemorySegment data, MemorySegment notify) {
        try { int _ = (int) G_IDLE_ADD_FULL.invokeExact(priority, fn, data, notify); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gFree(MemorySegment ptr) {
        try { G_FREE.invokeExact(ptr); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static MemorySegment gObjectRefSink(MemorySegment obj) {
        try { return (MemorySegment) G_OBJECT_REF_SINK.invokeExact(obj); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gObjectUnref(MemorySegment obj) {
        try { G_OBJECT_UNREF.invokeExact(obj); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gObjectSet(MemorySegment obj, MemorySegment propertyName, int value, MemorySegment terminator) {
        try { G_OBJECT_SET.invokeExact(obj, propertyName, value, terminator); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gSignalConnect(MemorySegment instance, String signal,
                               MemorySegment callback, MemorySegment data) {
        try (Arena a = Arena.ofConfined()) {
            long _ = (long) G_SIGNAL_CONNECT_DATA.invokeExact(
                    instance, a.allocateFrom(signal), callback, data,
                    MemorySegment.NULL, 0);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    static void gSignalHandlersDisconnectByData(MemorySegment instance, MemorySegment data) {
        try {
            int _ = (int) G_SIGNAL_HANDLERS_DISCONNECT_MATCHED.invokeExact(
                    instance, G_SIGNAL_MATCH_DATA, 0, 0, MemorySegment.NULL, MemorySegment.NULL, data);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    private GLib() {}
}
