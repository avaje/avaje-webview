package io.avaje.webview.macos;

import static io.avaje.webview.macos.ObjC.fromNSString;
import static io.avaje.webview.macos.ObjC.nsString;
import static io.avaje.webview.macos.ObjC.sel;
import static io.avaje.webview.macos.ObjC.send0;
import static io.avaje.webview.macos.ObjC.send1;
import static io.avaje.webview.macos.ObjC.send3;
import static io.avaje.webview.macos.ObjC.sendVoid0;
import static io.avaje.webview.macos.ObjC.sendVoid1;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

final class MacOSHelper {

  private MacOSHelper() {}

  static boolean startedOnFirstThread() {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return true;
    try {
      final var linker = Linker.nativeLinker();
      final var pid =
          (int)
              linker
                  .downcallHandle(
                      linker.defaultLookup().find("getpid").orElseThrow(),
                      FunctionDescriptor.of(ValueLayout.JAVA_INT))
                  .invokeExact();
      return "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid));
    } catch (final Throwable t) {
      return false;
    }
  }

  static void setWindowAppearance(MemorySegment nsWindow, boolean shouldBeDark) {
    try (var a = Arena.ofConfined()) {
      final var cls = ObjC.getClass(a, "NSAppearance");
      final var name = shouldBeDark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
      final var appearance = send1(cls, sel(a, "appearanceNamed:"), nsString(a, name));
      sendVoid1(nsWindow, sel(a, "setAppearance:"), appearance);
    }
  }

  static void fullscreen(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "toggleFullScreen:"), MemorySegment.NULL);
    }
  }

  static void maximize(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "zoom:"), MemorySegment.NULL);
    }
  }

  static void setIcon(Path iconPath) {
    try (var a = Arena.ofConfined()) {
      final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      final var NSImage = ObjC.getClass(a, "NSImage");
      final var image =
          send1(
              send0(NSImage, sel(a, "alloc")),
              sel(a, "initWithContentsOfFile:"),
              nsString(a, iconPath.toAbsolutePath().toString()));
      if (!image.equals(MemorySegment.NULL)) {
        sendVoid1(app, sel(a, "setApplicationIconImage:"), image);
      }
    }
  }

  static void createMenus() {
    try (var a = Arena.ofConfined()) {
      final var NSMenu = ObjC.getClass(a, "NSMenu");
      final var NSMenuItem = ObjC.getClass(a, "NSMenuItem");
      final var NSApp = ObjC.getClass(a, "NSApplication");
      final var NSPI = ObjC.getClass(a, "NSProcessInfo");

      final var alloc = sel(a, "alloc");
      final var autorelease = sel(a, "autorelease");
      final var initTitle = sel(a, "initWithTitle:");
      final var addItem = sel(a, "addItem:");
      final var setSubmenu = sel(a, "setSubmenu:");
      final var initItemSel = sel(a, "initWithTitle:action:keyEquivalent:");

      final var menuBar = send1(send0(NSMenu, alloc), initTitle, nsString(a, ""));
      sendVoid0(menuBar, autorelease);

      final var processInfo = send0(NSPI, sel(a, "processInfo"));
      final var appNameNS = send0(processInfo, sel(a, "processName"));
      final var appName = fromNSString(a, appNameNS);

      // App menu
      final var appItem =
          send3(
              send0(NSMenuItem, alloc),
              initItemSel,
              appNameNS,
              MemorySegment.NULL,
              nsString(a, ""));
      send1(menuBar, addItem, appItem);
      final var appMenu = send1(send0(NSMenu, alloc), initTitle, appNameNS);
      sendVoid0(appMenu, autorelease);
      final var quitItem =
          send3(
              send0(NSMenuItem, alloc),
              initItemSel,
              nsString(a, "Quit " + appName),
              sel(a, "terminate:"),
              nsString(a, "q"));
      send1(appMenu, addItem, quitItem);
      sendVoid1(appItem, setSubmenu, appMenu);

      // Edit menu
      final var editNS = nsString(a, "Edit");
      final var editItem =
          send3(send0(NSMenuItem, alloc), initItemSel, editNS, MemorySegment.NULL, nsString(a, ""));
      final var editMenu = send1(send0(NSMenu, alloc), initTitle, editNS);
      sendVoid0(editMenu, autorelease);
      sendVoid1(editItem, setSubmenu, editMenu);
      send1(menuBar, addItem, editItem);
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Cut", "cut:", "x");
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Copy", "copy:", "c");
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Paste", "paste:", "v");
      send1(editMenu, addItem, send0(NSMenuItem, sel(a, "separatorItem")));
      addEditItem(a, editMenu, NSMenuItem, initItemSel, addItem, "Select All", "selectAll:", "a");

      final var sharedApp = send0(NSApp, sel(a, "sharedApplication"));
      sendVoid1(sharedApp, sel(a, "setMainMenu:"), menuBar);
    }
  }

  private static void addEditItem(
      Arena a,
      MemorySegment menu,
      MemorySegment NSMenuItem,
      MemorySegment initItemSel,
      MemorySegment addItem,
      String title,
      String action,
      String key) {
    final var item =
        send3(
            send0(NSMenuItem, sel(a, "alloc")),
            initItemSel,
            nsString(a, title),
            sel(a, action),
            nsString(a, key));
    send1(menu, addItem, item);
  }
}
