package io.avaje.webview.platform;

import java.util.Map;

public enum NativeLoadType {
  LINUX32("io.avaje.webview.linux32.LinuxSo"),
  LINUX_64("io.avaje.webview.linux64.LinuxSo"),
  WINDOWS("io.avaje.webview.windows.WindowsDLL"),
  MACOS("io.avaje.webview.macos.MacDylib"),
  ;
  public static final String PREFIX = "/io/avaje/webview/nativelib/";
  private static final Map<String, NativeLoadType> register =
      Map.of(
          "arm",
          LINUX32,
          "x86",
          LINUX32,
          "aarch64",
          LINUX_64,
          "x86_64",
          LINUX_64,
          "windows_nt",
          WINDOWS,
          "macos",
          MACOS);

  public String className;

  NativeLoadType(String string) {
    this.className = string;
  }

  public static NativeLoadType get(String string) {
    return register.get(string);
  }
}
