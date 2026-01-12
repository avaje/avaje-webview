package io.avaje.webview;

import static java.lang.foreign.ValueLayout.ADDRESS;
import module java.base;

final class MacOSHelper {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup OBJC =
      SymbolLookup.libraryLookup("libobjc.dylib", Arena.global());

  // Objective-C runtime functions
  private static final MethodHandle objc_getClass;
  private static final MethodHandle sel_registerName;

  // Message send handles for different arities
  private static final MethodHandle objc_msgSend_0;
  private static final MethodHandle objc_msgSend_1;
  private static final MethodHandle objc_msgSend_3;

  static {
    try {
      // id objc_getClass(const char *name)
      objc_getClass =
          OBJC.find("objc_getClass")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_getClass not found"));

      // SEL sel_registerName(const char *str)
      sel_registerName =
          OBJC.find("sel_registerName")
              .map(addr -> LINKER.downcallHandle(addr, FunctionDescriptor.of(ADDRESS, ADDRESS)))
              .orElseThrow(() -> new UnsatisfiedLinkError("sel_registerName not found"));

      MemorySegment msgSendAddr =
          OBJC.find("objc_msgSend")
              .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend not found"));

      // id objc_msgSend(id self, SEL op, ...)
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

  /** Sets the window appearance to light or dark mode. */
  public static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      MemorySegment nsAppearanceClass = getClass(arena, "NSAppearance");
      String appearanceName = shouldBeDark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";

      MemorySegment appearanceString = toNativeString(arena, appearanceName);
      MemorySegment appearance =
          send(nsAppearanceClass, getSelector(arena, "appearanceNamed:"), appearanceString);

      send(nsWindow, getSelector(arena, "setAppearance:"), appearance);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to set window appearance", e);
    }
  }

  public static void fullscreen(Webview webview) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      send(nsWindow, getSelector(arena, "toggleFullScreen:"), MemorySegment.NULL);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to fullscreen window", e);
    }
  }

  public static void maximizeWindow(Webview webview) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nsWindow = webview.nativeWindowPointer();
      send(nsWindow, getSelector(arena, "zoom:"), MemorySegment.NULL);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to maximize window", e);
    }
  }

  /** Creates the standard application menus (App Menu and Edit Menu). */
  public static void createMenus() {
    try (Arena arena = Arena.ofConfined()) {
      // 1. Prepare Classes
      MemorySegment NSMenu = getClass(arena, "NSMenu");
      MemorySegment NSMenuItem = getClass(arena, "NSMenuItem");
      MemorySegment NSProcessInfo = getClass(arena, "NSProcessInfo");
      MemorySegment NSApplication = getClass(arena, "NSApplication");

      // 2. Prepare Selectors
      MemorySegment alloc = getSelector(arena, "alloc");
      MemorySegment initWithTitle = getSelector(arena, "initWithTitle:");
      MemorySegment autorelease = getSelector(arena, "autorelease");
      MemorySegment addItem = getSelector(arena, "addItem:");
      MemorySegment setSubmenu = getSelector(arena, "setSubmenu:");
      MemorySegment initItemSel = getSelector(arena, "initWithTitle:action:keyEquivalent:");

      // 3. Create Menu Bar
      MemorySegment m_menuBar = send(NSMenu, alloc);
      send(m_menuBar, initWithTitle, toNativeString(arena, ""));
      send(m_menuBar, autorelease);

      // 4. Get App Name
      MemorySegment processInfo = send(NSProcessInfo, getSelector(arena, "processInfo"));
      MemorySegment appNameNS = send(processInfo, getSelector(arena, "processName"));
      String appNameJava = fromNativeString(arena, appNameNS);

      // --- Application Menu ---
      MemorySegment appMenuItem = send(NSMenuItem, alloc);
      send(appMenuItem, initItemSel, appNameNS, MemorySegment.NULL, toNativeString(arena, ""));
      send(m_menuBar, addItem, appMenuItem);

      MemorySegment appMenu = send(NSMenu, alloc);
      send(appMenu, initWithTitle, appNameNS);
      send(appMenu, autorelease);

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

      // --- Edit Menu ---
      MemorySegment editMenuItem = send(NSMenuItem, alloc);
      MemorySegment editStr = toNativeString(arena, "Edit");
      send(editMenuItem, initItemSel, editStr, MemorySegment.NULL, toNativeString(arena, ""));

      MemorySegment editMenu = send(NSMenu, alloc);
      send(editMenu, initWithTitle, editStr);
      send(editMenu, autorelease);

      send(editMenuItem, setSubmenu, editMenu);
      send(m_menuBar, addItem, editMenuItem);

      // Menu Actions
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Cut", "cut:", "x"));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Copy", "copy:", "c"));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Paste", "paste:", "v"));
      send(
          editMenu,
          addItem,
          send(getClass(arena, "NSMenuItem"), getSelector(arena, "separatorItem")));
      send(editMenu, addItem, createMenuItem(arena, initItemSel, "Select All", "selectAll:", "a"));

      // 5. Set Main Menu
      MemorySegment sharedApp = send(NSApplication, getSelector(arena, "sharedApplication"));
      send(sharedApp, getSelector(arena, "setMainMenu:"), m_menuBar);

    } catch (Throwable e) {
      throw new RuntimeException("Failed to create menus", e);
    }
  }

  private static MemorySegment getSelector(Arena arena, String name) throws Throwable {
    return (MemorySegment) sel_registerName.invoke(arena.allocateFrom(name));
  }

  private static MemorySegment getClass(Arena arena, String name) throws Throwable {
    return (MemorySegment) objc_getClass.invoke(arena.allocateFrom(name));
  }

  private static MemorySegment send(MemorySegment receiver, MemorySegment selector)
      throws Throwable {
    return (MemorySegment) objc_msgSend_0.invoke(receiver, selector);
  }

  private static MemorySegment send(
      MemorySegment receiver, MemorySegment selector, MemorySegment arg1) throws Throwable {
    return (MemorySegment) objc_msgSend_1.invoke(receiver, selector, arg1);
  }

  private static MemorySegment send(
      MemorySegment receiver,
      MemorySegment selector,
      MemorySegment arg1,
      MemorySegment arg2,
      MemorySegment arg3)
      throws Throwable {
    return (MemorySegment) objc_msgSend_3.invoke(receiver, selector, arg1, arg2, arg3);
  }

  private static MemorySegment toNativeString(Arena arena, String str) throws Throwable {
    if (str == null) return MemorySegment.NULL;
    MemorySegment nsStringClass = getClass(arena, "NSString");
    MemorySegment sel = getSelector(arena, "stringWithUTF8String:");
    return send(nsStringClass, sel, arena.allocateFrom(str));
  }

  private static String fromNativeString(Arena arena, MemorySegment nsString) throws Throwable {
    if (nsString.equals(MemorySegment.NULL) || nsString.byteSize() == 0) return "";
    MemorySegment sel = getSelector(arena, "UTF8String");
    MemorySegment utf8Addr = send(nsString, sel);
    return utf8Addr.getString(0);
  }

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
