package io.avaje.webview;

import com.sun.jna.Library;
import com.sun.jna.Native;
import io.avaje.webview.platform.LinuxLibC;
import io.avaje.webview.platform.Platform;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

final class Bootstrap {

    static WebviewNative runSetup() {
        // Extract all of the libs.
        for (String lib : platformLibraries()) {
            File target = new File(new File(lib).getName());
            if (target.exists()) {
                target.delete();
            }
            target.deleteOnExit();

            if (extractToFile(lib, target)) {
                // Load it. This is so Native will be able to link it.
                System.load(target.getAbsolutePath());
            }
        }

        System.setProperty("jna.library.path", ".");

        return Native.load(
                "webview",
                WebviewNative.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8")
        );
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
