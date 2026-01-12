/**
 * MIT LICENSE
 *
 * <p>Copyright (c) 2024 Alex Bowles @ Casterlabs
 *
 * <p>Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * <p>The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * <p>THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.avaje.webview;

import module java.base;
import module org.jspecify;

final class WebviewUtil {

  static String getExceptionStack(@NonNull Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    e.printStackTrace(pw);

    String out = sw.toString();

    pw.flush();
    pw.close();
    sw.flush();

    return out.substring(0, out.length() - 2).replace("\r", "");
  }

  static String jsonEscape(@NonNull String input) {
    char[] chars = input.toCharArray();

    StringBuilder output = new StringBuilder();

    for (char ch : chars) {
      switch (ch) {
        case 0 -> output.append("\\u0000");
        case '\n' -> output.append("\\n");
        case '\t' -> output.append("\\t");
        case '\r' -> output.append("\\r");
        case '\\' -> output.append("\\\\");
        case '"' -> output.append("\\\"");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        default -> {
          if (ch > 127) {
            output.append("\\u").append(String.format("%04x", (int) ch));
          } else {
            output.append(ch);
          }
        }
      }
    }

    return output.toString();
  }

  static String forceSafeChars(@NonNull String input) {
    char[] chars = input.toCharArray();

    StringBuilder output = new StringBuilder();

    for (char ch : chars) {
      if (ch == 0) {
        output.append("\\u0000");
      } else if (ch > 127) {
        output.append("\\u").append(String.format("%04x", (int) ch));
      } else {
        output.append(ch);
      }
    }

    return output.toString();
  }
}
