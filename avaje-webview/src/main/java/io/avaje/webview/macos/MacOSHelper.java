package io.avaje.webview.macos;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

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
import java.lang.foreign.SegmentAllocator;
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

  static void minimize(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "miniaturize:"), MemorySegment.NULL);
    }
  }

  /**
   * Begins a native window-move operation for {@code nsWindow}, as if the user had grabbed the
   * title bar, using the current NSEvent ({@code [NSApp currentEvent]}) as the originating mouse
   * event that {@code performWindowDragWithEvent:} requires.
   */
  static void startWindowDrag(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      final var event = send0(app, sel(a, "currentEvent"));
      sendVoid1(nsWindow, sel(a, "performWindowDragWithEvent:"), event);
    }
  }

  /**
   * {@code -[NSWindow addChildWindow:ordered:]} - attaches {@code childWindow} to {@code
   * parentWindow} so the window manager keeps it stacked above the parent and moves it together.
   * {@code ordered = NSWindowAbove (1)}.
   */
  static void addChildWindow(MemorySegment parentWindow, MemorySegment childWindow) {
    try (var a = Arena.ofConfined()) {
      Linker.nativeLinker()
          .downcallHandle(
              ObjC.MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_LONG))
          .invokeExact(
              parentWindow, sel(a, "addChildWindow:ordered:"), childWindow, 1L /* NSWindowAbove */);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * {@code -[NSWindow removeChildWindow:]} - detaches a window added via {@link #addChildWindow}.
   */
  static void removeChildWindow(MemorySegment parentWindow, MemorySegment childWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(parentWindow, sel(a, "removeChildWindow:"), childWindow);
    }
  }

  /**
   * {@code -[NSWindow setAlphaValue:]} - sets window opacity (0.0-1.0). Used to briefly dip and
   * restore a child window's opacity to "flash" it when the user clicks its disabled parent.
   */
  static void setAlphaValue(MemorySegment window, double alpha) {
    try (var a = Arena.ofConfined()) {
      Linker.nativeLinker()
          .downcallHandle(
              ObjC.MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE))
          .invokeExact(window, sel(a, "setAlphaValue:"), alpha);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * {@code -[NSWindow center]} - centers {@code window} on the screen it mostly occupies. Without
   * this, {@code initWithContentRect:} leaves the window at its raw origin, which is the
   * bottom-left corner of the main screen in Cocoa's coordinate system.
   */
  static void center(MemorySegment window) {
    try (var a = Arena.ofConfined()) {
      sendVoid0(window, sel(a, "center"));
    }
  }

  /**
   * Centers {@code nsWindow} relative to {@code parentWindow} by reading both frames and computing
   * the origin that places the child at the center of the parent.
   */
  static void centerOnParent(MemorySegment nsWindow, MemorySegment parentWindow) {
    try (var a = Arena.ofConfined()) {
      final var frameSel = sel(a, "frame");
      final var pFrame =
          (MemorySegment) ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, parentWindow, frameSel);
      final var cFrame = (MemorySegment) ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, nsWindow, frameSel);
      final double pX = pFrame.get(JAVA_DOUBLE, 0);
      final double pY = pFrame.get(JAVA_DOUBLE, 8);
      final double pW = pFrame.get(JAVA_DOUBLE, 16);
      final double pH = pFrame.get(JAVA_DOUBLE, 24);
      final double cW = cFrame.get(JAVA_DOUBLE, 16);
      final double cH = cFrame.get(JAVA_DOUBLE, 24);
      ObjC.MSG_SEND_SET_SIZE.invokeExact(
          nsWindow, sel(a, "setFrameOrigin:"), pX + (pW - cW) / 2, pY + (pH - cH) / 2);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Installs a fill autoresizing mask on {@code view} (already created with an oversized frame -
   * see {@link ObjC#MSG_SEND_INIT_WITH_FRAME}) so it keeps covering the parent as it resizes.
   * {@code NSViewWidthSizable (1<<1) | NSViewHeightSizable (1<<4) = 18}.
   */
  static void installFillAutoresizeMask(MemorySegment view) {
    try (var a = Arena.ofConfined()) {
      Linker.nativeLinker()
          .downcallHandle(
              ObjC.MSG_SEND_ADDR,
              FunctionDescriptor.ofVoid(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
          .invokeExact(view, sel(a, "setAutoresizingMask:"), 18L);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * Adds {@code guardView} as the frontmost subview of {@code window}'s content view, so it
   * intercepts every click before the real content underneath sees it.
   */
  static void attachClickGuard(MemorySegment window, MemorySegment guardView) {
    try (var a = Arena.ofConfined()) {
      final var contentView = send0(window, sel(a, "contentView"));
      sendVoid1(contentView, sel(a, "addSubview:"), guardView);
    }
  }

  /** Detaches a guard view added via {@link #attachClickGuard}. */
  static void removeClickGuard(MemorySegment guardView) {
    try (var a = Arena.ofConfined()) {
      sendVoid0(guardView, sel(a, "removeFromSuperview"));
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
