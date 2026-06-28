package io.avaje.webview.macos;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Panama downcall handles for the Objective-C runtime and key macOS frameworks.
 * All objc_msgSend variants are typed per use-site.
 */
final class ObjC {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup OBJC_LIB =
      SymbolLookup.libraryLookup("libobjc.A.dylib", Arena.global());

  // objc_getClass("ClassName") → id
  static final MethodHandle GET_CLASS =
      downcall(OBJC_LIB, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));

  // sel_registerName("selectorName") → SEL
  static final MethodHandle SEL_REGISTER_NAME =
      downcall(OBJC_LIB, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));

  // objc_allocateClassPair(superclass, name, extraBytes) → Class
  static final MethodHandle ALLOC_CLASS_PAIR =
      downcall(OBJC_LIB, "objc_allocateClassPair",
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

  // class_addMethod(cls, sel, imp, types) → BOOL
  static final MethodHandle CLASS_ADD_METHOD =
      downcall(OBJC_LIB, "class_addMethod",
          FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // objc_registerClassPair(cls) → void
  static final MethodHandle REGISTER_CLASS_PAIR =
      downcall(OBJC_LIB, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));

  // class_createInstance(cls, extraBytes) → id
  static final MethodHandle CLASS_CREATE_INSTANCE =
      downcall(OBJC_LIB, "class_createInstance",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

  // objc_msgSend base address — reused with varying descriptors
  static final MemorySegment MSG_SEND_ADDR =
      OBJC_LIB.find("objc_msgSend").orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend"));

  // Common descriptors for objc_msgSend
  static final MethodHandle MSG_SEND_0 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_1 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_2 =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_3 =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_5 =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_VOID_0 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  static final MethodHandle MSG_SEND_VOID_1 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  // initWithContentRect:styleMask:backing:defer:
  // (id, SEL, NSRect[4 doubles], NSUInteger, NSUInteger, BOOL) → id
  // On arm64: NSRect passed in-line as 4 doubles (x,y,w,h)
  static final MethodHandle MSG_SEND_NSWINDOW_INIT =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS,
              JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,  // NSRect
              JAVA_LONG, JAVA_LONG, JAVA_INT));                     // styleMask, backing, defer

  // initWithFrame:configuration:  (id, SEL, NSRect[4d], id) → id
  static final MethodHandle MSG_SEND_WKWEBVIEW_INIT =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS,
              JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,  // NSRect
              ADDRESS));                                             // configuration

  // initWithSource:injectionTime:forMainFrameOnly: (id, SEL, id, NSInteger, BOOL) → id
  static final MethodHandle MSG_SEND_WKUSERSCRIPT_INIT =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

  // evaluateJavaScript:completionHandler: (id, SEL, id, id) → void
  static final MethodHandle MSG_SEND_EVAL_JS =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  // setTitle: (id, SEL, id) → void
  static final MethodHandle MSG_SEND_SET_TITLE =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  // setMinSize: / setMaxSize: (id, SEL, NSSize[2 doubles]) → void
  static final MethodHandle MSG_SEND_SET_SIZE =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  // setContentSize: (id, SEL, NSSize[2 doubles]) → void
  static final MethodHandle MSG_SEND_SET_CONTENT_SIZE =
      LINKER.downcallHandle(MSG_SEND_ADDR,
          FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  private static MethodHandle downcall(SymbolLookup lib, String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        lib.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("ObjC symbol not found: " + sym)),
        desc);
  }

  // -------------------------------------------------------------------------
  // Convenience helpers
  // -------------------------------------------------------------------------

  static MemorySegment getClass(Arena a, String name) {
    try { return (MemorySegment) GET_CLASS.invokeExact(a.allocateFrom(name)); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static MemorySegment sel(Arena a, String name) {
    try { return (MemorySegment) SEL_REGISTER_NAME.invokeExact(a.allocateFrom(name)); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static MemorySegment send0(MemorySegment recv, MemorySegment sel) {
    try { return (MemorySegment) MSG_SEND_0.invokeExact(recv, sel); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static MemorySegment send1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try { return (MemorySegment) MSG_SEND_1.invokeExact(recv, sel, a1); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static MemorySegment send2(MemorySegment recv, MemorySegment sel, MemorySegment a1, MemorySegment a2) {
    try { return (MemorySegment) MSG_SEND_2.invokeExact(recv, sel, a1, a2); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static void sendVoid0(MemorySegment recv, MemorySegment sel) {
    try { MSG_SEND_VOID_0.invokeExact(recv, sel); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  static void sendVoid1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try { MSG_SEND_VOID_1.invokeExact(recv, sel, a1); }
    catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Wraps a Java String as an NSString via +[NSString stringWithUTF8String:]. */
  static MemorySegment nsString(Arena a, String s) {
    if (s == null) return MemorySegment.NULL;
    try {
      MemorySegment cls = getClass(a, "NSString");
      MemorySegment sel = sel(a, "stringWithUTF8String:");
      return (MemorySegment) MSG_SEND_1.invokeExact(cls, sel, a.allocateFrom(s));
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  /** Extracts a Java String from an NSString via -[NSString UTF8String]. */
  static String fromNSString(Arena a, MemorySegment ns) {
    if (ns.equals(MemorySegment.NULL)) return "";
    try {
      MemorySegment utf8Addr = send0(ns, sel(a, "UTF8String"));
      return utf8Addr.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) { throw new RuntimeException(t); }
  }

  private ObjC() {}
}
