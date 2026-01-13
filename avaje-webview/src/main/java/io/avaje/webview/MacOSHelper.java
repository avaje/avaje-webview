package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import module java.base;

/**
 * A helper class that uses Java's Foreign Function & Memory API to interact with the MacOS
 * Objective-C runtime (AppKit). This allows controlling window appearance, fullscreen state, and
 * system menus without requiring native C/C++ JNI code.
 */
final class MacOSHelper {
  // The Linker allows Java to find and invoke native functions
  private static final Linker LINKER = Linker.nativeLinker();

  // Load the Objective-C runtime library, which is the heart of macOS app interaction
  private static final SymbolLookup OBJC =
      SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());

  // Method handles for core Objective-C runtime functions
  private static final MethodHandle objc_getClass;
  private static final MethodHandle sel_registerName;
  private static final MethodHandle getpid;

  // objc_msgSend is the "everything" function in Obj-C.
  // We need different handles based on the number of arguments (arity).
  private static final MethodHandle objc_msgSend_0; // No args: [obj selector]
  private static final MethodHandle objc_msgSend_1; // 1 arg:   [obj selector:arg]
  private static final MethodHandle objc_msgSend_3; // 3 args:  [obj selector:a b:c d:e]

  static {
    try {
      // Find 'objc_getClass' to look up classes like NSWindow or NSAppearance
      objc_getClass =
          OBJC.find("objc_getClass")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_getClass not found"));

      // Find 'sel_registerName' to convert strings into Objective-C Selectors (method IDs)
      sel_registerName =
          OBJC.find("sel_registerName")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("sel_registerName not found"));
      getpid =
          LINKER.downcallHandle(
              LINKER.defaultLookup().find("getpid").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT));
      // Base address for the dynamic message dispatcher
      MemorySegment msgSendAddr =
          OBJC.find("objc_msgSend")
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend not found"));

      // Map objc_msgSend to Java MethodHandles with specific parameter counts.
      // Every Obj-C call starts with (receiver, selector, ...)
      objc_msgSend_0 =
          LINKER.downcallHandle(msgSendAddr, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
      objc_msgSend_1 =
          LINKER.downcallHandle(
              msgSendAddr, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      objc_msgSend_3 =
          LINKER.downcallHandle(
              msgSendAddr,
              FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Checks if the application was started on the first thread */
  public static boolean startedOnFirstThread() {
    try {
      int pid = (int) getpid.invokeExact();
      return "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid));
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Sets the window appearance to light or dark mode. Equivalent to: [window
   * setAppearance:[NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]]
   */
  public static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    // Arena.ofConfined() ensures native memory is freed immediately after this block
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      MemorySegment nsAppearanceClass = getClass(arena, "NSAppearance");
      String appearanceName = shouldBeDark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";

      MemorySegment appearanceString = toNativeString(arena, appearanceName);
      // Look up and create the NSAppearance instance
      MemorySegment appearance =
          send(nsAppearanceClass, getSelector(arena, "appearanceNamed:"), appearanceString);

      // Apply the appearance to the window
      send(nsWindow, getSelector(arena, "setAppearance:"), appearance);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window appearance", e);
    }
  }

  /**
   * Enters native fullscreen mode using macOS's built-in fullscreen API. Equivalent to: [window
   * toggleFullScreen:nil]
   */
  public static void fullscreen(Webview webview) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      send(nsWindow, getSelector(arena, "toggleFullScreen:"), MemorySegment.NULL);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to fullscreen window", e);
    }
  }

  /**
   * Maximizes the window to fill the screen without entering fullscreen mode. Equivalent to
   * clicking the green 'zoom' button.
   */
  public static void maximizeWindow(Webview webview) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      // Zoom the window: [window zoom:nil]
      send(nsWindow, getSelector(arena, "zoom:"), MemorySegment.NULL);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }

  /**
   * Creates and attaches the standard macOS application menu and edit menu. This is required
   * because Cocoa apps started from the command line often have no menu.
   */
  public static void createMenus() {
    try (Arena arena = Arena.ofConfined()) {
      // 1. Prepare standard AppKit Classes
      MemorySegment NSMenu = getClass(arena, "NSMenu");
      MemorySegment NSMenuItem = getClass(arena, "NSMenuItem");
      MemorySegment NSProcessInfo = getClass(arena, "NSProcessInfo");
      MemorySegment NSApplication = getClass(arena, "NSApplication");

      // 2. Prepare Selectors (method names)
      MemorySegment alloc = getSelector(arena, "alloc");
      MemorySegment initWithTitle = getSelector(arena, "initWithTitle:");
      MemorySegment autorelease = getSelector(arena, "autorelease");
      MemorySegment addItem = getSelector(arena, "addItem:");
      MemorySegment setSubmenu = getSelector(arena, "setSubmenu:");
      MemorySegment initItemSel = getSelector(arena, "initWithTitle:action:keyEquivalent:");

      // 3. Create the top-level Menu Bar
      MemorySegment m_menuBar = send(NSMenu, alloc);
      send(m_menuBar, initWithTitle, toNativeString(arena, ""));
      send(m_menuBar, autorelease);

      // 4. Retrieve the application name from the OS
      MemorySegment processInfo = send(NSProcessInfo, getSelector(arena, "processInfo"));
      MemorySegment appNameNS = send(processInfo, getSelector(arena, "processName"));
      String appNameJava = fromNativeString(arena, appNameNS);

      // --- Application Menu (The one with the App's Name) ---
      MemorySegment appMenuItem = send(NSMenuItem, alloc);
      send(appMenuItem, initItemSel, appNameNS, MemorySegment.NULL, toNativeString(arena, ""));
      send(m_menuBar, addItem, appMenuItem);

      MemorySegment appMenu = send(NSMenu, alloc);
      send(appMenu, initWithTitle, appNameNS);
      send(appMenu, autorelease);

      // Add "Quit [AppName]" with Cmd+Q shortcut
      MemorySegment quitTitle = toNativeString(arena, "Quit " + appNameJava);
      MemorySegment quitMenuItem = send(NSMenuItem, alloc);
      send(
          quitMenuItem,
          initItemSel,
          quitTitle,
          getSelector(arena, "terminate:"),
          toNativeString(arena, "q"));

      send(appMenu, addItem, quitMenuItem);
      send(appMenuItem, setSubmenu, appMenu);

      // --- Edit Menu (Required for standard Cmd+C, Cmd+V support) ---
      MemorySegment editMenuItem = send(NSMenuItem, alloc);
      MemorySegment editStr = toNativeString(arena, "Edit");
      send(editMenuItem, initItemSel, editStr, MemorySegment.NULL, toNativeString(arena, ""));

      MemorySegment editMenu = send(NSMenu, alloc);
      send(editMenu, initWithTitle, editStr);
      send(editMenu, autorelease);

      send(editMenuItem, setSubmenu, editMenu);
      send(m_menuBar, addItem, editMenuItem);

      // Add standard Edit actions (mapping shortcuts to standard AppKit selectors)
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Cut", "cut:", "x"));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Copy", "copy:", "c"));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Paste", "paste:", "v"));
      send(
          editMenu,
          addItem,
          send(getClass(arena, "NSMenuItem"), getSelector(arena, "separatorItem")));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Select All", "selectAll:", "a"));

      // 5. Finalize: Set this menu bar as the global menu for the shared NSApplication
      MemorySegment sharedApp = send(NSApplication, getSelector(arena, "sharedApplication"));
      send(sharedApp, getSelector(arena, "setMainMenu:"), m_menuBar);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to create menus", e);
    }
  }

  /** Registers a string as an Objective-C selector. */
  private static MemorySegment getSelector(Arena arena, String name) throws Throwable {
    return (MemorySegment) sel_registerName.invoke(arena.allocateFrom(name));
  }

  /** Look up an Objective-C class by name. */
  private static MemorySegment getClass(Arena arena, String name) throws Throwable {
    return (MemorySegment) objc_getClass.invoke(arena.allocateFrom(name));
  }

  /** Sends a message to an object (no arguments). */
  private static MemorySegment send(MemorySegment receiver, MemorySegment selector)
      throws Throwable {
    return (MemorySegment) objc_msgSend_0.invoke(receiver, selector);
  }

  /** Sends a message to an object with one argument. */
  private static MemorySegment send(
      MemorySegment receiver, MemorySegment selector, MemorySegment arg1) throws Throwable {
    return (MemorySegment) objc_msgSend_1.invoke(receiver, selector, arg1);
  }

  /** Sends a message to an object with three arguments. Used primarily for menu item init. */
  private static MemorySegment send(
      MemorySegment receiver,
      MemorySegment selector,
      MemorySegment arg1,
      MemorySegment arg2,
      MemorySegment arg3)
      throws Throwable {
    return (MemorySegment) objc_msgSend_3.invoke(receiver, selector, arg1, arg2, arg3);
  }

  /** Converts a Java String to a native NSString object. */
  private static MemorySegment toNativeString(Arena arena, String str) throws Throwable {
    if (str == null) return MemorySegment.NULL;
    MemorySegment nsStringClass = getClass(arena, "NSString");
    MemorySegment sel = getSelector(arena, "stringWithUTF8String:");
    return send(nsStringClass, sel, arena.allocateFrom(str));
  }

  /** Converts a native NSString object back to a Java String. */
  private static String fromNativeString(Arena arena, MemorySegment nsString) throws Throwable {
    if (nsString.equals(MemorySegment.NULL) || nsString.byteSize() == 0) return "";
    MemorySegment sel = getSelector(arena, "UTF8String");
    MemorySegment utf8Addr = send(nsString, sel);
    // getString(0) reads the null-terminated C-string from the returned address
    return utf8Addr.getString(0);
  }

  /** Helper to allocate and initialize a new NSMenuItem. */
  private static MemorySegment createMenuItem(
      Arena arena, MemorySegment initSel, String title, String action, String key)
      throws Throwable {
    MemorySegment item = send(getClass(arena, "NSMenuItem"), getSelector(arena, "alloc"));
    return send(
        item,
        initSel,
        toNativeString(arena, title),
        getSelector(arena, action),
        toNativeString(arena, key));
  }
}
