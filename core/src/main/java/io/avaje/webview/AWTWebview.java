package io.avaje.webview;

import com.sun.jna.Native;
import com.sun.jna.ptr.PointerByReference;

import java.awt.*;
import java.io.Closeable;
import java.util.function.Consumer;

/**
 * An AWT component a which will automatically initialize the webview when it's
 * considered "drawable".
 * 
 */
public class AWTWebview extends Canvas implements Closeable {
    private static final long serialVersionUID = 5199512256429931156L;

    private Webview webview;
    private final boolean debug;

    private Dimension lastSize = null;

    /**
     * The callback handler for when the Webview gets created.
     */
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
            Thread t = new Thread(() -> {
                var ptr = new PointerByReference(Native.getComponentPointer(this));
                this.webview = Webview.builder().debug(this.debug).windowPointer(ptr).build();
                // this.webview = new Webview(this.debug, this);

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
        this.webview.close();
        this.initialized = false;
        this.webview = null;
    }

}
