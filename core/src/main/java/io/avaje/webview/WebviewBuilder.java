package io.avaje.webview;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.PointerByReference;
import io.avaje.webview.platform.LinuxLibC;
import io.avaje.webview.platform.Platform;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

public final class WebviewBuilder {

    private static WebviewNative NATIVE_LIB;

    private boolean useTempDirectory;
    private String title;
    private boolean debug;
    private PointerByReference windowPointer;
    private int width = 800;
    private int height = 600;
    private String html;
    private String url;

    WebviewBuilder() {

    }

    public static WebviewBuilder builder() {
        return new WebviewBuilder();
    }

    public WebviewBuilder useTempDirectory(boolean useTempDirectory) {
        this.useTempDirectory = useTempDirectory;
        return this;
    }

    public WebviewBuilder title(String title) {
        this.title = title;
        return this;
    }

    public WebviewBuilder debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    public WebviewBuilder windowPointer(PointerByReference windowPointer) {
        this.windowPointer = windowPointer;
        return this;
    }

    public WebviewBuilder width(int width) {
        this.width = width;
        return this;
    }

    public WebviewBuilder height(int height) {
        this.height = height;
        return this;
    }

    public WebviewBuilder html(String html) {
        this.html = html;
        return this;
    }

    public WebviewBuilder url(String url) {
        this.url = url;
        return this;
    }

    public Webview build() {
        var n = initNative(this);
        var view = new Webview(n, debug, windowPointer, width, height);
        if (title != null) {
            view.setTitle(title);
        }
        if (url != null) {
            view.loadURL(url);
        } else if (html != null) {
            view.setHTML(html);
        }
        return view;
    }

    private synchronized WebviewNative initNative(WebviewBuilder bootstrap) {
        if (NATIVE_LIB == null) {
            NATIVE_LIB = bootstrap.initNativeLibrary();
        }
        return NATIVE_LIB;
    }


    private WebviewNative initNativeLibrary() {
        // Extract all of the libs.
        for (String lib : platformLibraries()) {
            File target = createTarget(lib);
            if (target.exists()) {
                target.delete();
            }
            System.out.println("target: " + target.getAbsolutePath());
            target.deleteOnExit();

            if (extractToFile(lib, target)) {
                // Load it. This is so Native will be able to link it.
                System.load(target.getAbsolutePath());
            }
        }

        // System.setProperty("jna.library.path", ".");

        return Native.load(
                "webview",
                WebviewNative.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8")
        );
    }

    private File createTarget(String lib) {
        var name = new File(lib).getName();
        if (useTempDirectory) {
            try {
                return File.createTempFile("webview-", "-" + name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new File(name);
    }

    private static List<String> platformLibraries() {
        try {
            String prefix = "/io/avaje/webview/nativelib/";
            switch (Platform.osDistribution) {
                case LINUX -> {
                    if (LinuxLibC.isGNU()) {
                        return List.of(prefix + "linux/" + Platform.archTarget + "/gnu/libwebview.so");
                    } else {
                        return List.of(prefix + "linux/" + Platform.archTarget + "/musl/libwebview.so");
                    }
                }
                case MACOS -> {
                    return List.of(prefix + "macos/" + Platform.archTarget + "/libwebview.dylib");
                }
                case WINDOWS_NT -> {
                    return List.of(prefix + "windows_nt/" + Platform.archTarget + "/webview.dll");
                }
                default ->
                        throw new IllegalStateException("Unsupported platform: " + Platform.osDistribution + ":" + Platform.archTarget);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean extractToFile(String lib, File target) {
        try (InputStream in = WebviewNative.class.getResourceAsStream(lib.toLowerCase())) {
            byte[] bytes = toBytes(in);
            Files.write(target.toPath(), bytes);
            return true;
        } catch (Exception e) {
            if (!e.getMessage().contains("used by another")) {
                System.err.println("Unable to extract native: " + lib);
                throw new RuntimeException(e);
            }
            return false;
        }
    }

    private static byte[] toBytes(@NonNull InputStream source) throws IOException {
        if (source == null) {
            throw new NullPointerException("source is marked non-null but is null");
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            streamTransfer(source, out, 2048);
            return out.toByteArray();
        }
    }

    private static void streamTransfer(@NonNull InputStream source, @NonNull OutputStream dest, int bufferSize) throws IOException {
        if (source == null) {
            throw new NullPointerException("source is marked non-null but is null");
        } else if (dest == null) {
            throw new NullPointerException("dest is marked non-null but is null");
        } else {
            byte[] buffer = new byte[bufferSize];
            int read;
            while ((read = source.read(buffer)) != -1) {
                dest.write(buffer, 0, read);
            }

            dest.flush();
        }
    }
}
