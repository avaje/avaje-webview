/*
Copyright 2022 Casterlabs

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
*/
package io.avaje.webview.platform;

import module java.base;

public class Platform {

  /* ---------------- */
  /* CPU Architecture */
  /* ---------------- */

  /**
   * Whether or not the current machine's endianess is big endian.
   *
   * @implNote This just calls {@link ByteOrder#nativeOrder()}.
   */
  public static final boolean isBigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

  /**
   * The processor's word size/bitness, or -1 if unknown. Usually 32 or 64.
   *
   * @implNote Some IBM Z mainframes will return 32 even though their words are 31 bits long.
   */
  public static final int wordSize = PlatformUtil.getWordSize();

  /** The CPU Architecture of the host, e.g x86 or arm. */
  public static final ArchFamily archFamily = ArchFamily.get();

  /** The CPU Target of the host, e.g x86_64 or aarch64. */
  public static final String archTarget = archFamily.getArchTarget(wordSize, isBigEndian);

  /* ---------------- */
  /* Operating System */
  /* ---------------- */

  /** The family of the host's OS, e.g macOS or Windows NT */
  public static final OSFamily OS_FAMILY = OSFamily.get();

  /** The family distribution of the host's OS, e.g Unix or Windows */
  public static final OSDistribution OS_DISTRIBUTION = OSDistribution.get(OS_FAMILY);
}
