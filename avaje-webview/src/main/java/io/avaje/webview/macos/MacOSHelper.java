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
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
      return true;
    }
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

  static void unmaximize(MemorySegment nsWindow) {
    if (isMaximized(nsWindow)) {
      try (var a = Arena.ofConfined()) {
        sendVoid1(nsWindow, sel(a, "zoom:"), MemorySegment.NULL);
      }
    }
  }

  static boolean isMaximized(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      return ((MemorySegment) send0(nsWindow, sel(a, "isZoomed"))).address() != 0;
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
   * {@code -[NSWindow addChildWindow:ordered:]} attaches {@code childWindow} to {@code
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
   * {@code -[NSWindow removeChildWindow:]} detaches a window added via {@link #addChildWindow}.
   */
  static void removeChildWindow(MemorySegment parentWindow, MemorySegment childWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(parentWindow, sel(a, "removeChildWindow:"), childWindow);
    }
  }

  /**
   * {@code -[NSWindow setAlphaValue:]} sets window opacity between 0.0 and 1.0. Used to dip and
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
   * {@code -[NSWindow center]} centers {@code window} on the screen it mostly occupies. Without
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
          (MemorySegment)
              ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, parentWindow, frameSel);
      final var cFrame =
          (MemorySegment)
              ObjC.MSG_SEND_GET_FRAME.invokeExact((SegmentAllocator) a, nsWindow, frameSel);
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
   * Installs a fill autoresizing mask on {@code view}, which already carries an oversized frame
   * from {@link ObjC#MSG_SEND_INIT_WITH_FRAME}, so it keeps covering the parent through a resize.
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

  /** {@code NSModalResponseOK}, what {@code -[NSOpenPanel runModal]} returns on Open. */
  private static final long NS_MODAL_RESPONSE_OK = 1L;

  /** {@code NSAlertFirstButtonReturn}, the first button added to an {@code NSAlert}. */
  private static final long NS_ALERT_FIRST_BUTTON = 1000L;

  /**
   * Runs an {@code NSOpenPanel} for an {@code <input type=file>} click, configured from the page's
   * {@code WKOpenPanelParameters}.
   *
   * @return the chosen {@code NSArray<NSURL*>*}, or {@code NULL} on cancel
   */
  static MemorySegment runOpenPanel(MemorySegment parameters) {
    try (var a = Arena.ofConfined()) {
      final var panel = send0(ObjC.getClass(a, "NSOpenPanel"), sel(a, "openPanel"));
      final var allowsDirectories = sel(a, "allowsDirectories");
      final var directories =
          ObjC.respondsTo(a, parameters, allowsDirectories)
              && ObjC.sendBool0(parameters, allowsDirectories);
      ObjC.sendVoidBool(panel, sel(a, "setCanChooseFiles:"), !directories);
      ObjC.sendVoidBool(panel, sel(a, "setCanChooseDirectories:"), directories);
      ObjC.sendVoidBool(
          panel,
          sel(a, "setAllowsMultipleSelection:"),
          ObjC.sendBool0(parameters, sel(a, "allowsMultipleSelection")));
      if (ObjC.sendLong0(panel, sel(a, "runModal")) != NS_MODAL_RESPONSE_OK) {
        return MemorySegment.NULL;
      }
      return send0(panel, sel(a, "URLs"));
    }
  }

  /** Shows a JavaScript {@code alert()} as a modal {@code NSAlert} with one OK button. */
  static void runAlert(MemorySegment message) {
    try (var a = Arena.ofConfined()) {
      ObjC.sendLong0(newAlert(a, message, false), sel(a, "runModal"));
    }
  }

  /**
   * Shows a JavaScript {@code confirm()} as a modal {@code NSAlert}.
   *
   * @return {@code true} when the user pressed OK
   */
  static boolean runConfirm(MemorySegment message) {
    try (var a = Arena.ofConfined()) {
      return ObjC.sendLong0(newAlert(a, message, true), sel(a, "runModal"))
          == NS_ALERT_FIRST_BUTTON;
    }
  }

  /**
   * Shows a JavaScript {@code prompt()} as a modal {@code NSAlert} with a text field.
   *
   * @return the entered {@code NSString*}, or {@code NULL} on cancel
   */
  static MemorySegment runPrompt(MemorySegment message, MemorySegment defaultText) {
    try (var a = Arena.ofConfined()) {
      final var alert = newAlert(a, message, true);
      final var field =
          (MemorySegment)
              ObjC.MSG_SEND_INIT_WITH_FRAME.invokeExact(
                  send0(ObjC.getClass(a, "NSTextField"), sel(a, "alloc")),
                  sel(a, "initWithFrame:"),
                  0d,
                  0d,
                  260d,
                  24d);
      sendVoid0(field, sel(a, "autorelease"));
      if (defaultText.address() != 0) {
        sendVoid1(field, sel(a, "setStringValue:"), defaultText);
      }
      sendVoid1(alert, sel(a, "setAccessoryView:"), field);
      sendVoid1(send0(alert, sel(a, "window")), sel(a, "setInitialFirstResponder:"), field);
      if (ObjC.sendLong0(alert, sel(a, "runModal")) != NS_ALERT_FIRST_BUTTON) {
        return MemorySegment.NULL;
      }
      return send0(field, sel(a, "stringValue"));
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Builds an autoreleased {@code NSAlert} showing {@code message} with OK, plus Cancel. */
  private static MemorySegment newAlert(Arena a, MemorySegment message, boolean cancel) {
    final var alert = send0(send0(ObjC.getClass(a, "NSAlert"), sel(a, "alloc")), sel(a, "init"));
    sendVoid0(alert, sel(a, "autorelease"));
    sendVoid1(alert, sel(a, "setMessageText:"), message);
    send1(alert, sel(a, "addButtonWithTitle:"), nsString(a, "OK"));
    if (cancel) {
      send1(alert, sel(a, "addButtonWithTitle:"), nsString(a, "Cancel"));
    }
    return alert;
  }

  /** {@code NSWindowStyleMaskFullScreen}, set while the window is in native fullscreen. */
  private static final long NS_FULL_SCREEN_MASK = 1L << 14;

  /** Reads {@code [view frame]} as {@code {x, y, width, height}} in points. */
  static double[] frame(MemorySegment viewOrWindow) {
    try (var a = Arena.ofConfined()) {
      final var f =
          (MemorySegment)
              ObjC.MSG_SEND_GET_FRAME.invokeExact(
                  (SegmentAllocator) a, viewOrWindow, sel(a, "frame"));
      return new double[] {
        f.get(JAVA_DOUBLE, 0), f.get(JAVA_DOUBLE, 8), f.get(JAVA_DOUBLE, 16), f.get(JAVA_DOUBLE, 24)
      };
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** The size of {@code nsWindow}'s content view in points, excluding the title bar. */
  static double[] contentSize(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      final var f = frame(send0(nsWindow, sel(a, "contentView")));
      return new double[] {f[2], f[3]};
    }
  }

  static boolean isMinimized(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      return ObjC.sendBool0(nsWindow, sel(a, "isMiniaturized"));
    }
  }

  static void deminiaturize(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "deminiaturize:"), MemorySegment.NULL);
    }
  }

  static boolean isFullscreen(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      return (ObjC.sendLong0(nsWindow, sel(a, "styleMask")) & NS_FULL_SCREEN_MASK) != 0;
    }
  }

  /** Orders {@code nsWindow} to the front, makes it key, and activates the application. */
  static void bringToFront(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "makeKeyAndOrderFront:"), MemorySegment.NULL);
      final var app = send0(ObjC.getClass(a, "NSApplication"), sel(a, "sharedApplication"));
      ObjC.sendVoidBool(app, sel(a, "activateIgnoringOtherApps:"), true);
    }
  }

  static void orderOut(MemorySegment nsWindow) {
    try (var a = Arena.ofConfined()) {
      sendVoid1(nsWindow, sel(a, "orderOut:"), MemorySegment.NULL);
    }
  }

  /** The URL of {@code wkWebView}'s current page, or an empty string. */
  static String currentUrl(MemorySegment wkWebView) {
    try (var a = Arena.ofConfined()) {
      final var url = send0(wkWebView, sel(a, "URL"));
      return url.address() == 0 ? "" : fromNSString(a, send0(url, sel(a, "absoluteString")));
    }
  }

  /** The URL a {@code WKNavigationAction} wants to load, from {@code action.request.URL}. */
  static String navigationUrl(MemorySegment navigationAction) {
    try (var a = Arena.ofConfined()) {
      final var request = send0(navigationAction, sel(a, "request"));
      final var url = request.address() == 0 ? request : send0(request, sel(a, "URL"));
      return url.address() == 0 ? "" : fromNSString(a, send0(url, sel(a, "absoluteString")));
    }
  }

  /**
   * Whether a {@code WKNavigationAction} loads into an existing frame. It has no target frame when
   * it would open a new window, as for a {@code target="_blank"} link.
   */
  static boolean hasTargetFrame(MemorySegment navigationAction) {
    try (var a = Arena.ofConfined()) {
      return send0(navigationAction, sel(a, "targetFrame")).address() != 0;
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
