/*
Copyright 2022 Casterlabs

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
*/
package io.avaje.webview.platform;

import java.util.regex.Pattern;

public enum ArchFamily {
  X86("x86", "x86|i[0-9]86|ia32|amd64|ia64|itanium64"),
  ARM("arm", "arm|aarch"),
  ;

  private final String name;
  private final Pattern regex;

  ArchFamily(String name, String regex) {
    this.name = name;
    this.regex = Pattern.compile(regex);
  }

  static ArchFamily get() {
    String osArch = System.getProperty("os.arch", "<blank>").toLowerCase();
    for (ArchFamily arch : values()) {
      if (!arch.regex.matcher(osArch).find()) continue;
      return arch;
    }
    throw new UnsupportedOperationException("Unsupported cpu arch: " + osArch);
  }

  /**
   * @return the standardized name of the architecture (e.g "x86" or "arm").
   */
  @Override
  public String toString() {
    return this.name;
  }

  /**
   * @param wordSize The word size, usually 32 or 64.
   * @param isBigEndian Whether or not the processor is bigEndian or littleEndian. Some CPUs don't
   *     support this so this will be silently ignored.
   * @return A "standard" target name.
   */
  public String getArchTarget(int wordSize) {
    return switch (this) {
      case ARM -> wordSize == 64 ? "aarch64" : "arm";
      case X86 -> wordSize == 64 ? "x86_64" : "x86";
    };
  }
}
