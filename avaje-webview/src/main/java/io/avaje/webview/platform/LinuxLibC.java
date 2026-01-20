package io.avaje.webview.platform;

import module java.base;

import static java.lang.System.Logger.Level.ERROR;

/** This class allows you to detect whether or not a machine uses GNU or MUSL Libc. */
// Code adapted from here:
// https://github.com/lovell/detect-libc/blob/main/lib/detect-libc.js
public final class LinuxLibC {

  private static final System.Logger log = System.getLogger("io.avaje.webview");

  /**
   * If this returns true then you know that this OS supports GNU LibC. It may also support MUSL or
   * other standards.
   */
  public static boolean isGNU() throws IOException {
    if (Platform.OS_DISTRIBUTION != OSDistribution.LINUX) {
      throw new IllegalStateException("LinuxLibC is only supported on Linux.");
    }

    if ("true".equalsIgnoreCase(System.getProperty("casterlabs.commons.forcegnu"))) {
      return true;
    }

    try {
      return isGNUViaFS();
    } catch (IOException e) {
      log.log(ERROR, "Failed checking glibc presence via filesystem", e);
    }

    try {
      return isGNUViaCommand();
    } catch (IOException e) {
      log.log(ERROR, "Failed checking glibc presence via shell invocation", e);
    }

    return true;
  }

  private static boolean isGNUViaFS() throws IOException {
    try (FileInputStream fin = new FileInputStream(new File("/usr/bin/ldd"))) {
      String ldd = PlatformUtil.readInputStreamString(fin);
      return ldd.contains("GNU C Library");
    }
  }

  private static boolean isGNUViaCommand() throws IOException {
    var unameProc =
        new ProcessBuilder(
                "sh", "-c", "getconf GNU_LIBC_VERSION 2>&1 || true; ldd --version 2>&1 || true")
            .start();

    String unameResult = PlatformUtil.readInputStreamString(unameProc.getInputStream());

    return unameResult != null && unameResult.contains("glibc");
  }
}
