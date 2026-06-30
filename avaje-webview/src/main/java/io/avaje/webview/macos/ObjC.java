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
 * Panama FFI handles into the Objective-C runtime.
 *
 * <h2>objc_msgSend and the MSG_SEND_* handles</h2>
 *
 * <p>Every Objective-C method call compiles down to {@code objc_msgSend(receiver, selector,
 * args...)}. We resolve the raw symbol address once ({@link #MSG_SEND_ADDR}) and then create
 * multiple {@link MethodHandle} views over it, each with a different {@link FunctionDescriptor}
 * matching the exact argument and return type layout of the target method. Panama re-uses the same
 * native code entry point; only the Java-side type contract differs.
 */
final class ObjC {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup OBJC_LIB =
      SymbolLookup.libraryLookup("libobjc.A.dylib", Arena.global());

  /**
   * {@code objc_getClass(const char* name) -> Class}
   *
   * <p>Performs a hash lookup in the ObjC runtime's global class table and returns the {@code
   * Class} object for {@code name}, or {@code NULL} if the class is not registered. Classes are
   * registered when their containing framework is dlopen'd.
   */
  static final MethodHandle GET_CLASS =
      downcall(OBJC_LIB, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code sel_registerName(const char* str) -> SEL}
   *
   * <p>Interns {@code str} in a process-global selector table and returns a stable {@code SEL}
   * pointer. Calling this twice with the same string is cheap (a hash lookup) and returns the same
   * pointer both times, so caching selectors is optional. The returned pointer is valid for the
   * lifetime of the process.
   *
   * <p>In Obj-C source this is the {@code @selector(name)} directive; we must call the C API
   * directly since we have no Obj-C compiler.
   */
  static final MethodHandle SEL_REGISTER_NAME =
      downcall(OBJC_LIB, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));

  /**
   * {@code objc_allocateClassPair(Class superclass, const char* name, size_t extraBytes) -> Class}
   *
   * <p>Reserves a new Obj-C class (and its paired metaclass) in the runtime without registering it
   * yet. "Pair" refers to the class + metaclass structure the ObjC runtime always creates together.
   *
   * <p>Used to synthesize {@code WKScriptMessageHandler} and {@code NSWindowDelegate}
   * implementations without any Obj-C source code.
   */
  static final MethodHandle ALLOC_CLASS_PAIR =
      downcall(
          OBJC_LIB,
          "objc_allocateClassPair",
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

  /**
   * {@code class_addMethod(Class cls, SEL name, IMP imp, const char* types) -> BOOL}
   *
   * <p>Registers a method implementation on {@code cls}.
   */
  static final MethodHandle CLASS_ADD_METHOD =
      downcall(
          OBJC_LIB,
          "class_addMethod",
          FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code objc_registerClassPair(Class cls) -> void}
   *
   * <p>Finalizes the class and makes it visible to the ObjC runtime. Must be called after all
   * {@link #CLASS_ADD_METHOD} calls for the class are complete.
   */
  static final MethodHandle REGISTER_CLASS_PAIR =
      downcall(OBJC_LIB, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));

  /**
   * {@code class_createInstance(Class cls, size_t extraBytes) -> id}
   *
   * <p>Allocates an instance of {@code cls} and returns it without calling any {@code -init}
   * method.
   */
  static final MethodHandle CLASS_CREATE_INSTANCE =
      downcall(
          OBJC_LIB, "class_createInstance", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

  /**
   * Raw address of {@code objc_msgSend}.
   *
   * <p>We expose this as a {@link MemorySegment} (not a {@link MethodHandle}) so that call sites
   * that need one-off descriptors can build their own handle against the same native entry point
   * without an extra library lookup.
   */
  static final MemorySegment MSG_SEND_ADDR =
      OBJC_LIB.find("objc_msgSend").orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend"));

  /**
   * All-pointer {@code objc_msgSend} variants returning {@code id}.
   *
   * <p>The numeric suffix is the count of Obj-C arguments beyond the fixed {@code (id receiver, SEL
   * sel)} pair. For example, {@code MSG_SEND_1} corresponds to {@code [recv sel arg0]}. Every
   * argument and the return value is an opaque pointer ({@code ADDRESS}).
   */
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

  /**
   * Void-returning {@code objc_msgSend} variants.
   *
   * <p>Used when the Obj-C message has no return value (or where the return value is intentionally
   * discarded).
   */
  static final MethodHandle MSG_SEND_VOID_0 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  static final MethodHandle MSG_SEND_VOID_1 =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code -[NSWindow initWithContentRect:styleMask:backing:defer:]}
   *
   * <p>{@code NSRect} is a C struct {@code {CGFloat x, CGFloat y, CGFloat width, CGFloat height}}.
   * On the AArch64 ABI (and x86-64 System V ABI) a small struct like this is passed in floating-
   * point registers as four consecutive {@code double} values.
   *
   * <p>Parameter breakdown:
   *
   * <ul>
   *   <li>{@code receiver} (ADDRESS) - uninitialized {@code NSWindow*} from {@code +alloc}
   *   <li>{@code sel} (ADDRESS) - {@code initWithContentRect:styleMask:backing:defer:}
   *   <li>{@code contentRect.x, .y, .width, .height} (4× JAVA_DOUBLE) - initial frame in screen
   *       coordinates (origin is ignored; AppKit places the window at a default position)
   *   <li>{@code styleMask} (JAVA_LONG) - NSUInteger bitmask of window chrome flags
   *   <li>{@code backing} (JAVA_LONG) - {@code NSBackingStoreBuffered = 2}, the only valid value on
   *       modern macOS; the other enum values (Retained, Nonretained) were removed
   *   <li>{@code defer} (JAVA_INT / BOOL) - {@code 0} (NO) creates the backing store immediately
   *       rather than lazily; required so the window is usable before the first run-loop cycle
   * </ul>
   */
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

  /**
   * {@code -[WKWebView initWithFrame:configuration:]}
   *
   * <p>Same {@code NSRect}-as-four-doubles ABI as {@link #MSG_SEND_NSWINDOW_INIT}. The {@code
   * configuration} argument is a {@code WKWebViewConfiguration*} (ADDRESS). The configuration
   * object is read at construction time; properties changed on it after {@code
   * initWithFrame:configuration:} returns have no effect.
   */
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

  /**
   * {@code -[WKUserScript initWithSource:injectionTime:forMainFrameOnly:]}
   *
   * <p>Parameter types beyond {@code (receiver, sel, source)}:
   *
   * <ul>
   *   <li>{@code injectionTime} - {@code WKUserScriptInjectionTime}, which is an {@code NSInteger}
   *       (= {@code long} on 64-bit platforms). Maps to {@code JAVA_LONG}.
   *   <li>{@code forMainFrameOnly} - {@code BOOL}. In Obj-C's ABI, BOOL is {@code signed char}, but
   *       arguments passed through {@code objc_msgSend} are promoted to {@code int} by C's default
   *       argument promotion rules. Maps to {@code JAVA_INT}.
   * </ul>
   */
  static final MethodHandle MSG_SEND_WKUSERSCRIPT_INIT =
      LINKER.downcallHandle(
          MSG_SEND_ADDR,
          FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

  /**
   * {@code -[WKWebView evaluateJavaScript:completionHandler:] -> void}
   *
   * <p>The {@code completionHandler} argument is a block pointer ({@code ^(id result, NSError*
   * error){}}). We always pass {@code NULL} (fire-and-forget) because we don't need the JavaScript
   * return value or error information from the eval call.
   */
  static final MethodHandle MSG_SEND_EVAL_JS =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

  /** {@code -[NSWindow setTitle:] -> void} Sets the Title */
  static final MethodHandle MSG_SEND_SET_TITLE =
      LINKER.downcallHandle(MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

  /**
   * {@code -[NSWindow setMinSize:] / -[NSWindow setMaxSize:] -> void}
   *
   * <p>{@code NSSize} is a C struct {@code {CGFloat width, CGFloat height}} - two consecutive
   * doubles passed inline in floating-point registers on the AArch64 and x86-64 ABIs. Both {@code
   * -setMinSize:} and {@code -setMaxSize:} share this descriptor.
   *
   * <p>Passing {@code (0.0, 0.0)} to {@code setMaxSize:} is interpreted by AppKit as "no maximum
   * size constraint" - AppKit treats a zero NSSize as unconstrained for max, not as a zero-pixel
   * maximum.
   */
  static final MethodHandle MSG_SEND_SET_SIZE =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  /**
   * {@code -[NSWindow setContentSize:] -> void}
   *
   * <p>Structurally identical to {@link #MSG_SEND_SET_SIZE} (two inline doubles for NSSize). A
   * separate handle exists to distinguish {@code setContentSize:} from {@code setMinSize:} / {@code
   * setMaxSize:} at call sites - they do different things: this sets the current window content
   * area size, while setMinSize/setMaxSize constrain the resizable range.
   */
  static final MethodHandle MSG_SEND_SET_CONTENT_SIZE =
      LINKER.downcallHandle(
          MSG_SEND_ADDR, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE));

  private static MethodHandle downcall(SymbolLookup lib, String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        lib.find(sym).orElseThrow(() -> new UnsatisfiedLinkError("ObjC symbol not found: " + sym)),
        desc);
  }

  /**
   * Looks up an Obj-C class by name: {@code objc_getClass(name)}.
   *
   * <p>Performs a hash lookup in the ObjC runtime's class table. The class must already be
   * registered (its framework must be dlopen'd). {@code a.allocateFrom(name)} writes a
   * null-terminated UTF-8 C string into the arena for the duration of the call.
   *
   * @param a an arena that must outlive this call (the string is freed when the arena closes)
   * @param name the unqualified ObjC class name, e.g. {@code "NSWindow"}
   * @return the {@code Class} pointer, or {@code NULL} if not found
   */
  static MemorySegment getClass(Arena a, String name) {
    try {
      return (MemorySegment) GET_CLASS.invokeExact(a.allocateFrom(name));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Interns a selector string and returns the {@code SEL} pointer: {@code sel_registerName(name)}.
   *
   * <p>SELs are process-global interned identifiers. Calling this twice with the same string
   * returns the same pointer, so caching is optional - the cost is only a hash lookup, not an
   * allocation. The returned SEL is valid for the process lifetime.
   *
   * <p>Equivalent to the ObjC compiler directive {@code @selector(name)}.
   *
   * @param a arena for the temporary C string (freed when the arena closes)
   * @param name selector string, e.g. {@code "initWithFrame:configuration:"}
   * @return stable {@code SEL} pointer
   */
  static MemorySegment sel(Arena a, String name) {
    try {
      return (MemorySegment) SEL_REGISTER_NAME.invokeExact(a.allocateFrom(name));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a zero-argument message: {@code [recv sel]}.
   *
   * @return the {@code id} return value, or {@code NULL} if the method returns void or null
   */
  static MemorySegment send0(MemorySegment recv, MemorySegment sel) {
    try {
      return (MemorySegment) MSG_SEND_0.invokeExact(recv, sel);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a one-argument message: {@code [recv sel a1]}.
   *
   * @return the {@code id} return value
   */
  static MemorySegment send1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try {
      return (MemorySegment) MSG_SEND_1.invokeExact(recv, sel, a1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a two-argument message: {@code [recv sel a1 a2]}.
   *
   * @return the {@code id} return value
   */
  static MemorySegment send2(
      MemorySegment recv, MemorySegment sel, MemorySegment a1, MemorySegment a2) {
    try {
      return (MemorySegment) MSG_SEND_2.invokeExact(recv, sel, a1, a2);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a three-argument message: {@code [recv sel a1 a2 a3]}.
   *
   * @return the {@code id} return value
   */
  static MemorySegment send3(
      MemorySegment recv, MemorySegment sel, MemorySegment a1, MemorySegment a2, MemorySegment a3) {
    try {
      return (MemorySegment) MSG_SEND_3.invokeExact(recv, sel, a1, a2, a3);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a void zero-argument message: {@code [recv sel]}.
   *
   * <p>Use when the Obj-C method returns {@code void} and the return register can be ignored.
   */
  static void sendVoid0(MemorySegment recv, MemorySegment sel) {
    try {
      MSG_SEND_VOID_0.invokeExact(recv, sel);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Sends a void one-argument message: {@code [recv sel a1]}.
   *
   * <p>Use when the Obj-C method returns {@code void}.
   */
  static void sendVoid1(MemorySegment recv, MemorySegment sel, MemorySegment a1) {
    try {
      MSG_SEND_VOID_1.invokeExact(recv, sel, a1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Creates an autoreleased {@code NSString} from a Java string via {@code +[NSString
   * stringWithUTF8String:]}.
   *
   * <p>The returned object is autoreleased - it is valid until the current autorelease pool drains,
   * which happens at the top of each run-loop iteration. In practice, this is always safe for
   * passing as a method argument within the same arena scope, because we never retain the string
   * past the current call.
   *
   * @param a arena for the temporary UTF-8 C string argument; must outlive this call
   * @param s the Java string to convert, or {@code null} (returns {@code NULL} segment)
   * @return an autoreleased {@code NSString*}
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
   * Extracts a Java {@code String} from an {@code NSString} via {@code -[NSString UTF8String]}.
   *
   * @param a arena for the temporary selector allocation
   * @param ns the {@code NSString*} to read; returns {@code ""} if {@code NULL}
   * @return the string content as a Java {@code String}
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
