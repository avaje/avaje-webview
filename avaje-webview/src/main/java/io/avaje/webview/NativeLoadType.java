package io.avaje.webview;

import static io.avaje.webview.platform.Platform.PREFIX;
import static java.util.Map.entry;
import static java.util.Map.ofEntries;

import java.util.Map;

enum NativeLoadType {
  LINUX_32_ARM_GNU("io.avaje.webview.linux.arm.gnu", PREFIX + "linux/arm/gnu/libwebview.so"),
  LINUX_32_ARM_MUSL("io.avaje.webview.linux.arm.musl", PREFIX + "linux/arm/musl/libwebview.so"),
  LINUX_32_GNU("io.avaje.webview.linux.gnu", PREFIX + "linux/x86/gnu/libwebview.so"),
  LINUX_32_MUSL("io.avaje.webview.linux32.arm.gnu", PREFIX + "linux/x86/musl/libwebview.so"),
  LINUX_64_ARM_GNU(
      "io.avaje.webview.linux.aarch64.gnu", PREFIX + "linux/aarch64/gnu/libwebview.so"),
  LINUX_64_ARM_MUSL(
      "io.avaje.webview.linux.aarch64.musl", PREFIX + "linux/aarch64/musl/libwebview.so"),
  LINUX_64_GNU("io.avaje.webview.linux.x86_64.gnu", PREFIX + "linux/x86_64/gnu/libwebview.so"),
  LINUX_64_MUSL("io.avaje.webview.linux.x86_64.musl", PREFIX + "linux/x86_64/musl/libwebview.so"),
  MAC_ARM("io.avaje.webview.mac.aarch64", PREFIX + "macos/aarch64/libwebview.dylib"),
  MAC("io.avaje.webview.mac.x86_64", PREFIX + "macos/x86_64/libwebview.dylib"),
  WINDOWS("io.avaje.webview.windows.x86_64", PREFIX + "windows_nt/x86_64/webview.dll"),
  WINDOWS_32("io.avaje.webview.windows.x86", PREFIX + "windows_nt/x86/webview.dll"),
  ;

  private static final Map<String, NativeLoadType> register =
      ofEntries(
          entry(LINUX_32_ARM_GNU.location, LINUX_32_ARM_GNU),
          entry(LINUX_32_ARM_MUSL.location, LINUX_32_ARM_MUSL),
          entry(LINUX_32_GNU.location, LINUX_32_GNU),
          entry(LINUX_32_MUSL.location, LINUX_32_MUSL),
          entry(LINUX_64_ARM_GNU.location, LINUX_64_ARM_GNU),
          entry(LINUX_64_ARM_MUSL.location, LINUX_64_ARM_MUSL),
          entry(LINUX_64_GNU.location, LINUX_64_GNU),
          entry(LINUX_64_MUSL.location, LINUX_64_MUSL),
          entry(WINDOWS.location, WINDOWS),
          entry(WINDOWS_32.location, WINDOWS),
          entry(MAC.location, MAC),
          entry(MAC_ARM.location, MAC_ARM));

  public String className;
  public String moduleName;
  public String location;

  NativeLoadType(String module, String location) {
    this.moduleName = module;
    this.location = location;
  }

  public static NativeLoadType get(String string) {
    return register.get(string);
  }
}
