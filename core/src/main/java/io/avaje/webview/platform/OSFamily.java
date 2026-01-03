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

public enum OSFamily {
    // @formatter:off

    UNIX     ("Unix",    "nux|bsd|.ix|sun|solaris|hp-ux|mac|darwin"),
    WINDOWS  ("Windows", "win"),
    DOS      ("DOS",     "dos"),
    VMS      ("VMS",     "vms"),

    GENERIC  ("Generic", ""),

    ;
    // @formatter:on

    /**
     * A friendly name for the family (e.g "Unix" or "Windows").
     */
    public final String name;
    private final String regex;

    OSFamily(String name, String regex) {
        this.name = name;
        this.regex = regex;
    }

    static OSFamily get() {
        String osName = System.getProperty("os.name", "<blank>").toLowerCase();

        // Search the enums for a match, returning it.
        for (OSFamily e : values()) {
            if (Pattern.compile(e.regex).matcher(osName).find()) {
                return e;
            }
        }

        // Fallback.
        return GENERIC;
    }

    /**
     * See {@link #name}.
     *
     * @return the name of the family
     */
    @Override
    public String toString() {
        return this.name;
    }

}