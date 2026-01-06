package io.avaje.webview;

import module java.base;
import module java.desktop;

/**
 * An AWT component which will automatically initialize the webview when it's considered "drawable".
 */
public class AWTWebview extends Canvas implements Closeable {
  private static final long serialVersionUID = 5199512256429931156L;

  private Webview webview;
  private final boolean debug;
  private Dimension lastSize = null;

  /** The callback handler for when the Webview gets created. */
  private Consumer<Webview> onInitialized;

  private boolean initialized = false;

  public AWTWebview() {
    this(false);
  }

  /**
   * @param debug Whether or not to allow the opening of inspect element/devtools.
   */
  public AWTWebview(boolean debug) {
    this.debug = debug;
    this.setBackground(Color.BLACK);
  }

  public Webview getWebview() {
    return webview;
  }

  public boolean isInitialized() {
    return initialized;
  }

  public void setOnInitialized(Consumer<Webview> onInitialized) {
    this.onInitialized = onInitialized;
  }

  @Override
  public void paint(Graphics g) {
    Dimension size = this.getSize();
    if (!size.equals(this.lastSize)) {
      this.lastSize = size;
      if (this.webview != null) {
        this.updateSize();
      }
    }

    if (!this.initialized) {
      this.initialized = true;

      // We need to create the webview off of the swing thread.
      Thread t =
          new Thread(
              () -> {
                MemorySegment windowPointer = getComponentPointer(this);
                this.webview =
                    Webview.builder()
                        .enableDeveloperTools(this.debug)
                        .windowPointer(windowPointer)
                        .build();

                this.updateSize();

                if (this.onInitialized != null) {
                  this.onInitialized.accept(this.webview);
                }

                this.webview.run();
              });
      t.setDaemon(false);
      t.setName("AWTWebview RunAsync Thread - #" + this.hashCode());
      t.start();
    }
  }

  private void updateSize() {
    int width = this.lastSize.width;
    int height = this.lastSize.height;
    this.webview.setFixedSize(width, height);
  }

  @Override
  public void close() {
    if (this.webview != null) {
      this.webview.close();
      this.webview = null;
    }
    this.initialized = false;
  }

  /**
   * Get the native window pointer for an AWT component. This is platform-specific and uses
   * different approaches based on the OS.
   */
  private static MemorySegment getComponentPointer(Component component) {
    try {
      // For modern JDKs with FFM support, we can use reflection to access
      // the native peer and get the window handle
      String osName = System.getProperty("os.name").toLowerCase();

      if (osName.contains("win")) {
        return getWindowsComponentPointer(component);
      }
      if (osName.contains("mac")) {
        return getMacComponentPointer(component);
      }
      if (osName.contains("nix") || osName.contains("nux")) {
        return getLinuxComponentPointer(component);
      }
      throw new UnsupportedOperationException("Unsupported platform: " + osName);
    } catch (Exception e) {
      throw new RuntimeException("Failed to get component native pointer", e);
    }
  }

  private static MemorySegment getWindowsComponentPointer(Component component) throws Exception {
    // Access the peer to get the HWND
    var peerField = Component.class.getDeclaredField("peer");
    peerField.setAccessible(true);
    Object peer = peerField.get(component);

    if (peer == null) {
      throw new IllegalStateException("Component peer is null");
    }

    // Get the HWND from the peer
    var hwndMethod = peer.getClass().getMethod("getHWnd");
    hwndMethod.setAccessible(true);
    long hwnd = (long) hwndMethod.invoke(peer);

    return MemorySegment.ofAddress(hwnd);
  }

  private static MemorySegment getMacComponentPointer(Component component) throws Exception {
    // Access the peer to get the native window pointer
    var peerField = Component.class.getDeclaredField("peer");
    peerField.setAccessible(true);
    Object peer = peerField.get(component);

    if (peer == null) {
      throw new IllegalStateException("Component peer is null");
    }

    // Get the native window pointer from the peer
    var ptrMethod = peer.getClass().getMethod("getAWTView");
    ptrMethod.setAccessible(true);
    long ptr = (long) ptrMethod.invoke(peer);

    return MemorySegment.ofAddress(ptr);
  }

  private static MemorySegment getLinuxComponentPointer(Component component) throws Exception {
    // Access the peer to get the X11 window handle
    var peerField = Component.class.getDeclaredField("peer");
    peerField.setAccessible(true);
    Object peer = peerField.get(component);

    if (peer == null) {
      throw new IllegalStateException("Component peer is null");
    }

    // Get the window handle from the peer
    var windowMethod = peer.getClass().getMethod("getWindow");
    windowMethod.setAccessible(true);
    long window = (long) windowMethod.invoke(peer);

    return MemorySegment.ofAddress(window);
  }
}
