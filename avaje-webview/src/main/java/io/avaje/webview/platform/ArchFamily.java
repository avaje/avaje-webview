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
    // @formatter:off
    X86   ("x86",   false, "x86|i[0-9]86|ia32|amd64|ia64|itanium64"),
    ARM   ("arm",   false, "arm|aarch"),
    PPC   ("ppc",   true,  "ppc|power"),
    SPARC ("sparc", true,  "sparc"),
    MIPS  ("mips",  true,  "mips"),
    S390  ("s390",  true,  "s390"),
    RISCV ("riscv", false, "riscv"),
    ;
    // @formatter:on

    private final String name;
    private final boolean isUsuallyBigEndian;
    private final String regex;

    ArchFamily(String name, boolean isUsuallyBigEndian, String regex) {
        this.name = name;
        this.isUsuallyBigEndian = isUsuallyBigEndian;
        this.regex = regex;
    }


    static ArchFamily get() {
        String osArch = System.getProperty("os.arch", "<blank>").toLowerCase();

        // Search the enums for a match, returning it.
        for (ArchFamily arch : values()) {
            if (Pattern.compile(arch.regex).matcher(osArch).find()) {
                return arch;
            }
        }

        // Couldn't find a match.
        throw new UnsupportedOperationException("Unknown cpu arch: " + osArch);
    }

    /**
     * @return the standardized name of the architecture (e.g "x86" or "arm").
     */
    @Override
    public String toString() {
        return this.name;
    }

    /**
     * @param  wordSize    The word size, usually 32 or 64.
     * @param  isBigEndian Whether or not the processor is bigEndian or littleEndian.
     *                   Some CPUs don't support this so this will be silently
     *                   ignored.
     *
     * @return           A "standard" target name.
     */
    public String getArchTarget(int wordSize, boolean isBigEndian) {
        // https://github.com/llvm/llvm-project/blob/main/llvm/include/llvm/TargetParser/Triple.h
        switch (this) {
            case ARM:
                return wordSize == 64 ? //
                        (isBigEndian ? "aarch64_be" : "aarch64") : //
                        (isBigEndian ? "armeb" : "arm");

            case MIPS:
                return wordSize == 64 ? //
                        (isBigEndian ? "mips64" : "mips64el") : //
                        (isBigEndian ? "mips" : "mipsel");

            case PPC:
                return wordSize == 64 ? //
                        (isBigEndian ? "ppc64" : "ppc64le") : //
                        (isBigEndian ? "ppc" : "ppcle");

            case RISCV:
                return wordSize == 64 ? "riscv64" : "riscv32";

            case S390:
                return "systemz"; // TODO LLVM appears to not have a s390x variant?

            case SPARC:
                return wordSize == 64 ? //
                        ("sparcv9") : //
                        (isBigEndian ? "sparc" : "sparcel");

            case X86:
                return wordSize == 64 ? "x86_64" : "x86";

            // Don't create a `default:` entry.
            // We want the compiler to warn us about missed values.

        }

        throw new RuntimeException("Unable to figure out LLVM for arch: " + Platform.archFamily);
    }

}