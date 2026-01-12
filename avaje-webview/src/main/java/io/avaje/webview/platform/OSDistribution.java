/*
Copyright 2022 Casterlabs

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
*/
package io.avaje.webview.platform;

import static io.avaje.webview.platform.OSFamily.DOS;
import static io.avaje.webview.platform.OSFamily.UNIX;
import static io.avaje.webview.platform.OSFamily.VMS;
import static io.avaje.webview.platform.OSFamily.WINDOWS;

import module java.base;

public enum OSDistribution {

  // DOS
  MS_DOS(DOS, "MS-DOS", "MSDOS", "<manually detected>"),

  // Windows
  WINDOWS_9X(WINDOWS, "Windows 9x", "MSDOS", "windows (95|98|me|ce)"),
  WINDOWS_NT(WINDOWS, "Windows NT", "Windows", "win"),

  // Unix
  MACOS(UNIX, "macOS", "macOS", "mac|darwin"),
  SOLARIS(UNIX, "Solaris", "Solaris", "sun|solaris"),
  BSD(UNIX, "BSD", "BSD", "bsd"),
  LINUX(UNIX, "Linux", "Linux", "nux"),

  // VMS
  OPEN_VMS(VMS, "OpenVMS", "VMS", "vms"),

  /** This is the fallback, this is not to be considered to be a valid value. */
  GENERIC(null, "Generic", "Generic", "");

  private final OSFamily family;

  /** A friendly name for the distribution (e.g "macOS" or "Windows NT"). */
  private final String name;

  private final Pattern regex;

  OSDistribution(OSFamily family, String name, String target, String regex) {
    this.family = family;
    this.name = name;
    this.regex = Pattern.compile(regex);
  }

  static OSDistribution get(OSFamily family) {
    // If the OS Family is MS DOS then we can't detect it via normal means.
    // One way is to match path separator which changed in Windows 9x.
    if ((family == OSFamily.DOS) && ";".equals(System.getProperty("path.separator", ""))) {
      return MS_DOS;
    }

    String osName = System.getProperty("os.name", "<blank>").toLowerCase();
    for (OSDistribution os : values()) {
      if (os.family != family || !os.regex.matcher(osName).find()) continue;
      return os;
    }
    return GENERIC;
  }

  /**
   * See {@link #name}.
   *
   * @return the name of the distribution
   */
  @Override
  public String toString() {
    return this.name;
  }
}
