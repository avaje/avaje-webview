package io.avaje.webview.macos;

import io.avaje.webview.Webview;

import java.lang.foreign.*;
import java.net.URI;
import java.nio.file.Path;

import static io.avaje.webview.macos.ObjC.*;

final class MacOSHelper {

  private MacOSHelper() {}

  static boolean startedOnFirstThread() {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return true;
    try {
      var linker = Linker.nativeLinker();
      int pid = (int) linker.downcallHandle(
          linker.defaultLookup().find("getpid").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
      return "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid));
    } catch (Throwable t) {
      return false;
    }
  }

  static void setWindowAppearance(MemorySegment nsWindow, boolean shouldBeDark) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment cls = getClass(a, "NSAppearance");
      String name = shouldBeDark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
      MemorySegment appearance = send1(cls, sel(a, "appearanceNamed:"), nsString(a, name));
      sendVoid1(nsWindow, sel(a, "setAppearance:"), appearance);
    }
  }

  static void fullscreen(MemorySegment nsWindow) {
    try (Arena a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "toggleFullScreen:"), MemorySegment.NULL);
    }
  }

  static void maximize(MemorySegment nsWindow) {
    try (Arena a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "zoom:"), MemorySegment.NULL);
    }
  }

  static void setIcon(Path iconPath) {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment app = send0(getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      MemorySegment NSImage = getClass(a, "NSImage");
      MemorySegment image = send1(
          send0(NSImage, sel(a, "alloc")),
          sel(a, "initWithContentsOfFile:"),
          nsString(a, iconPath.toAbsolutePath().toString()));
      if (!image.equals(MemorySegment.NULL)) {
        sendVoid1(app, sel(a, "setApplicationIconImage:"), image);
      }
    }
  }

  static void createMenus() {
    try (Arena a = Arena.ofConfined()) {
      MemorySegment NSMenu     = getClass(a, "NSMenu");
      MemorySegment NSMenuItem = getClass(a, "NSMenuItem");
      MemorySegment NSApp      = getClass(a, "NSApplication");
      MemorySegment NSPI       = getClass(a, "NSProcessInfo");

      MemorySegment alloc         = sel(a, "alloc");
      MemorySegment autorelease   = sel(a, "autorelease");
      MemorySegment initTitle     = sel(a, "initWithTitle:");
      MemorySegment addItem       = sel(a, "addItem:");
      MemorySegment setSubmenu    = sel(a, "setSubmenu:");
      MemorySegment initItemSel   = sel(a, "initWithTitle:action:keyEquivalent:");

      MemorySegment menuBar = send1(send0(NSMenu, alloc), initTitle, nsString(a, ""));
      sendVoid0(menuBar, autorelease);

      MemorySegment processInfo = send0(NSPI, sel(a, "processInfo"));
      MemorySegment appNameNS   = send0(processInfo, sel(a, "processName"));
      String appName = fromNSString(a, appNameNS);

      // App menu
      MemorySegment appItem = send2(send0(NSMenuItem, alloc), initItemSel, appNameNS,
          MemorySegment.NULL, nsString(a, ""));
      send1(menuBar, addItem, appItem);
      MemorySegment appMenu = send1(send0(NSMenu, alloc), initTitle, appNameNS);
      sendVoid0(appMenu, autorelease);
      MemorySegment quitItem = send2(send0(NSMenuItem, alloc), initItemSel,
          nsString(a, "Quit " + appName), sel(a, "terminate:"), nsString(a, "q"));
      send1(appMenu, addItem, quitItem);
      sendVoid1(appItem, setSubmenu, appMenu);

      // Edit menu
      MemorySegment editNS   = nsString(a, "Edit");
      MemorySegment editItem = send2(send0(NSMenuItem, alloc), initItemSel, editNS,
          MemorySegment.NULL, nsString(a, ""));
      MemorySegment editMenu = send1(send0(NSMenu, alloc), initTitle, editNS);
      sendVoid0(editMenu, autorelease);
      sendVoid1(editItem, setSubmenu, editMenu);
      send1(menuBar, addItem, editItem);
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Cut",        "cut:",       "x");
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Copy",       "copy:",      "c");
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Paste",      "paste:",     "v");
      send1(editMenu, addItem, send0(NSMenuItem, sel(a, "separatorItem")));
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Select All", "selectAll:", "a");

      MemorySegment sharedApp = send0(NSApp, sel(a, "sharedApplication"));
      sendVoid1(sharedApp, sel(a, "setMainMenu:"), menuBar);
    }
  }

  private static void addEditItem(Arena a, MemorySegment menu, MemorySegment NSMenuItem,
      MemorySegment initItemSel, MemorySegment addItem,
      String title, String action, String key) {
    MemorySegment item = send2(send0(NSMenuItem, sel(a, "alloc")), initItemSel,
        nsString(a, title), sel(a, action), nsString(a, key));
    send1(menu, addItem, item);
  }
}
