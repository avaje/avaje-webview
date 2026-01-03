package co.casterlabs.commons.platform;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * This class allows you to detect whether or not a machine uses GNU or MUSL
 * Libc.
 */
// Code adapted from here:
// https://github.com/lovell/detect-libc/blob/main/lib/detect-libc.js
public class LinuxLibC {

    /**
     * If this returns true then you know that this OS supports GNU LibC. It may
     * also support MUSL or other standards.
     */
    public static boolean isGNU() throws IOException {
        if (Platform.osDistribution != OSDistribution.LINUX) {
            throw new IllegalStateException("LinuxLibC is only supported on Linux.");
        }

        if ("true".equalsIgnoreCase(System.getProperty("casterlabs.commons.forcegnu"))) {
            return true;
        }

        try {
            return isGNUViaFS();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            return isGNUViaCommand();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    private static boolean isGNUViaFS() throws IOException {
        try (FileInputStream fin = new FileInputStream(new File("/usr/bin/ldd"))) {
            String ldd = _PlatformUtil.readInputStreamString(fin, StandardCharsets.UTF_8);
            return ldd.contains("GNU C Library");
        }
    }

    private static boolean isGNUViaCommand() throws IOException {
        Process unameProc = Runtime.getRuntime().exec("sh -c 'getconf GNU_LIBC_VERSION 2>&1 || true; ldd --version 2>&1 || true'");
        String unameResult = _PlatformUtil.readInputStreamString(unameProc.getInputStream(), StandardCharsets.UTF_8);

        return unameResult.contains("glibc");
    }

}
