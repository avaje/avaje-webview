package dev.webview.webview_java;

import co.casterlabs.commons.io.streams.StreamUtil;
import co.casterlabs.commons.platform.LinuxLibC;
import co.casterlabs.commons.platform.Platform;
import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collections;

final class Bootstrap {

    static WebviewNative runSetup() {

        String[] libraries = null;
        try {
            switch (Platform.osDistribution) {
                case LINUX: {
                    if (LinuxLibC.isGNU()) {
                        libraries = new String[]{
                                "/dev/webview/webview_java/natives/" + Platform.archTarget + "/linux/gnu/libwebview.so"
                        };
                    } else {
                        libraries = new String[]{
                                "/dev/webview/webview_java/natives/" + Platform.archTarget + "/linux/musl/libwebview.so"
                        };
                    }
                    break;
                }

                case MACOS: {
                    libraries = new String[]{
                            "/dev/webview/webview_java/natives/" + Platform.archTarget + "/macos/libwebview.dylib"
                    };
                    break;
                }

                case WINDOWS_NT: {
                    libraries = new String[]{
//                        "/dev/webview/webview_java/natives/" + Platform.archTarget + "/windows_nt/WebView2Loader.dll",
                            "/dev/webview/webview_java/natives/" + Platform.archTarget + "/windows_nt/webview.dll"
                    };
                    break;
                }

                default: {
                    throw new IllegalStateException("Unsupported platform: " + Platform.osDistribution + ":" + Platform.archTarget);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Extract all of the libs.
        for (String lib : libraries) {
            File target = new File(new File(lib).getName());
            if (target.exists()) {
                target.delete();
            }
            target.deleteOnExit();

            // Copy it to a file.
            try (InputStream in = WebviewNative.class.getResourceAsStream(lib.toLowerCase())) {
                byte[] bytes = StreamUtil.toBytes(in);
                Files.write(target.toPath(), bytes);
            } catch (Exception e) {
                if (e.getMessage().contains("used by another")) continue; // Ignore.

                System.err.println("Unable to extract native: " + lib);
                throw new RuntimeException(e);
            }

            System.load(target.getAbsolutePath()); // Load it. This is so Native will be able to link it.
        }

        System.setProperty("jna.library.path", ".");

        return Native.load(
                "webview",
                WebviewNative.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8")
        );
    }
}
