package io.avaje.webview.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Panama FFI handles into the Objective-C runtime (libobjc.A.dylib).
 *
 * <p>Every ObjC method call compiles to objc_msgSend(receiver, selector, args...). Panama's
 * invokeExact needs exact types, so we need a separate MethodHandle for each distinct argument
 * signature (can't use varargs). The MSG_SEND_N handles cover all-pointer calls; the named ones
 * handle calls with struct args (NSRect/NSSize as inline doubles) or primitive types (NSInteger,
 * BOOL).
 *
 * <p>ADDRESS = pointer (id, SEL, Class, etc.) = MemorySegment on the Java side. Arena scopes
 * off-heap memory lifetime — ofConfined() for short-lived call args, global() for things that need
 * to live forever.
 */
final class ObjC {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup OBJC_LIB =
      SymbolLookup.libraryLookup("libobjc.A.dylib", Arena.global());

  // -------------------------------------------------------------------------
  // ObjC runtime functions
  // -------------------------------------------------------------------------

  // objc_getClass("NSFoo") → Class  (same as NSClassFromString but without NSString overhead)
  static final MethodHandle GET_CLASS =
      downcall(OBJC_LIB, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));

  // sel_registerName("doThing:with:") → SEL
  // SELs are process-global interned strings; calling this twice returns the same pointer.
  static final MethodHandle SEL_REGISTER_NAME =
      downcall(OBJC_LIB, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));

  // objc_allocateClassPair(superCls, name, extraBytes) → Class
  // Creates a new ObjC class at runtime. "Pair" = class + its metaclass.
  // Used to synthesise a WKScriptMessageHandler without any Obj-C source code.
  static final MethodHandle ALLOC_CLASS_PAIR =
      downcall(
          OBJC_LIB,
          "objc_allocateClassPair",
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

  // class_addMethod(cls, sel, imp, typeEncoding) → BOOL
  // `imp` is a raw C function pointer — we pass a Panama upcall stub here.
  // typeEncoding is an ObjC type string, e.g. "v@:@@" (void, id, SEL, id, id).
  static final MethodHandle CLASS_ADD_METHOD =
      downcall(
          OBJC_LIB,
          "class_addMethod",
          FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // objc_registerClassPair(cls) → void
  // Finalises the class; must be called after all class_addMethod calls.
  static final MethodHandle REGISTER_CLASS_PAIR =
      downcall(OBJC_LIB, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));

  // class_createInstance(cls, extraBytes) → id
  // Allocates an instance without calling any -init. Fine for stateless handler objects.
  static final MethodHandle CLASS_CREATE_INSTANCE =
      downcall(
          OBJC_LIB, "class_createInstance", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

  // -------------------------------------------------------------------------
  // objc_msgSend — raw symbol reused with different FunctionDescriptors below
  // -------------------------------------------------------------------------

  static final MemorySegment MSG_SEND_ADDR =
      OBJC_LIB.find("objc_msgSend").orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend"));

  // All-pointer variants: (id receiver, SEL sel [, id argN...]) → id
  // Suffix = number of extra id args beyond the fixed (receiver, sel) pair.
  static final MethodHandle MSG_SEND_0 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_1 =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_2 =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_3 =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_5 =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(
              ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // Void-returning variants
  static final MethodHandle MSG_SEND_VOID_0 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_VOID_1 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  // -------------------------------------------------------------------------
  // Specialised handles for calls with non-pointer (struct/primitive) args
  // -------------------------------------------------------------------------

  // -[NSWindow initWithContentRect:styleMask:backing:defer:]
  // NSRect is passed as 4 inline doubles on arm64 (x, y, width, height).
  // styleMask + backing = NSUInteger (JAVA_LONG), defer = BOOL (JAVA_INT).
  static final MethodHandle MSG_SEND_NSWINDOW_INIT =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(
              ADDRESS,
              ADDRESS,
              ADDRESS,
              JAVA_DOUBLE,
              JAVA_DOUBLE,
              JAVA_DOUBLE,
              JAVA_DOUBLE, // NSRect
              JAVA_LONG,
              JAVA_LONG,
              JAVA_INT)); // styleMask, backing, defer

  // -[WKWebView initWithFrame:configuration:]   (NSRect + id)
  static final MethodHandle MSG_SEND_WKWEBVIEW_INIT =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(
              ADDRESS,
              ADDRESS,
              ADDRESS,
              JAVA_DOUBLE,
              JAVA_DOUBLE,
              JAVA_DOUBLE,
              JAVA_DOUBLE, // NSRect
              ADDRESS)); // WKWebViewConfiguration*

  // -[WKUserScript initWithSource:injectionTime:forMainFrameOnly:]
  // injectionTime = NSInteger (JAVA_LONG), forMainFrameOnly = BOOL (JAVA_INT)
  static final MethodHandle MSG_SEND_WKUSERSCRIPT_INIT =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

  // -[WKWebView evaluateJavaScript:completionHandler:]  → void
  // completionHandler is a block pointer; we pass NULL (fire-and-forget).
  static final MethodHandle MSG_SEND_EVAL_JS =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // -[NSWindow setTitle:]   → void
  static final MethodHandle MSG_SEND_SET_TITLE =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  // -[NSWindow setMinSize:] / setMaxSize:   NSSize = {double, double}
  static final MethodHandle MSG_SEND_SET_SIZE =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  // -[NSWindow setContentSize:]   same shape as setMinSize:
  static final MethodHandle MSG_SEND_SET_CONTENT_SIZE =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  private static MethodHandle downcall(SymbolLookup lib, String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        lib.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("ObjC symbol not found: " + sym)),
        desc);
  }

  // -------------------------------------------------------------------------
  // Convenience wrappers
  // sendN / sendVoidN correspond to [receiver sel arg1 ... argN].
  // allocateFrom(string) writes a null-terminated UTF-8 C string into the arena.
  // -------------------------------------------------------------------------

  /** [NSFoo class] */
  static MemorySegment getClass(Arena a, String name) {
    try {
      return (MemorySegment) GET_CLASS.invokeExact(a.allocateFrom(name));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * @selector(name)
   */
  static MemorySegment sel(Arena a, String name) {
    try {
      return (MemorySegment) SEL_REGISTER_NAME.invokeExact(a.allocateFrom(name));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** [recv sel] */
  static MemorySegment send0(MemorySegment recv, MemorySegment sel) {
    try {
      return (MemorySegment) MSG_SEND_0.invokeExact(recv, sel);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** [recv sel a1] */
  static MemorySegment send1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try {
      return (MemorySegment) MSG_SEND_1.invokeExact(recv, sel, a1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** [recv sel a1 a2] */
  static MemorySegment send2(
      MemorySegment recv, MemorySegment sel, MemorySegment a1, MemorySegment a2) {
    try {
      return (MemorySegment) MSG_SEND_2.invokeExact(recv, sel, a1, a2);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** [recv sel a1 a2 a3] */
  static MemorySegment send3(
      MemorySegment recv, MemorySegment sel, MemorySegment a1, MemorySegment a2, MemorySegment a3) {
    try {
      return (MemorySegment) MSG_SEND_3.invokeExact(recv, sel, a1, a2, a3);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** void [recv sel] */
  static void sendVoid0(MemorySegment recv, MemorySegment sel) {
    try {
      MSG_SEND_VOID_0.invokeExact(recv, sel);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** void [recv sel a1] */
  static void sendVoid1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try {
      MSG_SEND_VOID_1.invokeExact(recv, sel, a1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * +[NSString stringWithUTF8String:str] Returns an autoreleased NSString — valid for the current
   * run-loop drain, which is long enough for passing as a method argument in the same arena scope.
   */
  static MemorySegment nsString(Arena a, String s) {
    if (s == null) return MemorySegment.NULL;
    try {
      final var cls = getClass(a, "NSString");
      final var sel = sel(a, "stringWithUTF8String:");
      return (MemorySegment) MSG_SEND_1.invokeExact(cls, sel, a.allocateFrom(s));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * -[NSString UTF8String] → Java String. reinterpret(MAX_VALUE) gives Panama permission to read
   * past the declared segment bounds (the C string was allocated by native code, so Panama has no
   * size metadata for it).
   */
  static String fromNSString(Arena a, MemorySegment ns) {
    if (ns.equals(MemorySegment.NULL)) return "";
    try {
      final var utf8Addr = send0(ns, sel(a, "UTF8String"));
      return utf8Addr.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private ObjC() {}
}
