package io.avaje.webview;

import io.avaje.webview.platform.LinuxLibC;
import io.avaje.webview.platform.Platform;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.file.Files;
import java.util.Collections;

final class Bootstrap {

    static WebviewNative runSetup() {

        String[] libraries = null;
        try {
            String prefix = "/io/avaje/webview/natives/";
            switch (Platform.osDistribution) {
                case LINUX: {
                    if (LinuxLibC.isGNU()) {
                        libraries = new String[]{
                                prefix + Platform.archTarget + "/linux/gnu/libwebview.so"
                        };
                    } else {
                        libraries = new String[]{
                                prefix + Platform.archTarget + "/linux/musl/libwebview.so"
                        };
                    }
                    break;
                }

                case MACOS: {
                    libraries = new String[]{
                            prefix + Platform.archTarget + "/macos/libwebview.dylib"
                    };
                    break;
                }

                case WINDOWS_NT: {
                    libraries = new String[]{
//                        prefix + Platform.archTarget + "/windows_nt/WebView2Loader.dll",
                            prefix + Platform.archTarget + "/windows_nt/webview.dll"
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
                byte[] bytes = toBytes(in);
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
            int read = 0;

            while((read = source.read(buffer)) != -1) {
                dest.write(buffer, 0, read);
            }

            dest.flush();
        }
    }
}
