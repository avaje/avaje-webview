package io.avaje.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JsonTest {

  // -------------------------------------------------------------------------
  // jsonGet — object key extraction
  // -------------------------------------------------------------------------

  @Test
  void jsonGet_stringValue() {
    assertEquals("bar", WebviewBase.jsonGet("{\"foo\":\"bar\"}", "foo"));
  }

  @Test
  void jsonGet_emptyStringValue() {
    assertTrue(WebviewBase.jsonGet("{\"foo\":\"\"}", "foo").isEmpty());
  }

  @Test
  void jsonGet_objectValue() {
    assertEquals("{}", WebviewBase.jsonGet("{\"foo\":{}}", "foo"));
  }

  @Test
  void jsonGet_nestedObject() {
    assertEquals("{\"bar\": 1}", WebviewBase.jsonGet("{\"foo\": {\"bar\": 1}}", "foo"));
  }

  @Test
  void jsonGet_arrayValue() {
    assertEquals("[1,2,3]", WebviewBase.jsonGet("{\"foo\":[1,2,3]}", "foo"));
  }

  @Test
  void jsonGet_numericValue() {
    assertEquals("42", WebviewBase.jsonGet("{\"foo\":42}", "foo"));
  }

  @Test
  void jsonGet_multipleKeys_first() {
    assertEquals("bar", WebviewBase.jsonGet("{\"foo\":\"bar\",\"baz\":\"qux\"}", "foo"));
  }

  @Test
  void jsonGet_multipleKeys_second() {
    assertEquals("qux", WebviewBase.jsonGet("{\"foo\":\"bar\",\"baz\":\"qux\"}", "baz"));
  }

  @Test
  void jsonGet_unicodeKeyAndValue() {
    assertEquals("バー", WebviewBase.jsonGet("{\"フー\":\"バー\"}", "フー"));
  }

  @Test
  void jsonGet_missingKey_emptyObject() {
    assertTrue(WebviewBase.jsonGet("{}", "foo").isEmpty());
  }

  @Test
  void jsonGet_missingKey_nonEmptyObject() {
    assertTrue(WebviewBase.jsonGet("{\"bar\":\"baz\"}", "foo").isEmpty());
  }

  @Test
  void jsonGet_emptyInput() {
    assertTrue(WebviewBase.jsonGet("", "foo").isEmpty());
  }

  @Test
  void jsonGet_unterminatedJson_returnsValue() {
    // mirrors C++ behaviour: partial JSON still yields the value if parseable
    assertEquals("bar", WebviewBase.jsonGet("{\"foo\":\"bar\"", "foo"));
  }

  // -------------------------------------------------------------------------
  // WebviewUtil.jsonEscape — raw escaping (no surrounding quotes)
  // -------------------------------------------------------------------------

  @Test
  void jsonEscapeRaw_empty() {
    assertTrue(WebviewUtil.jsonEscape("").isEmpty());
  }

  @Test
  void jsonEscapeRaw_noEscapingNeeded() {
    assertEquals("hello", WebviewUtil.jsonEscape("hello"));
  }

  @Test
  void jsonEscapeRaw_backslash() {
    assertEquals("\\\\", WebviewUtil.jsonEscape("\\"));
  }

  @Test
  void jsonEscapeRaw_doubleQuote() {
    assertEquals("\\\"", WebviewUtil.jsonEscape("\""));
  }

  @Test
  void jsonEscapeRaw_controlChars() {
    assertEquals("\\b\\f\\n\\r\\t", WebviewUtil.jsonEscape("\b\f\n\r\t"));
  }

  @Test
  void jsonEscapeRaw_nullChar() {
    assertEquals("\\u0000", WebviewUtil.jsonEscape("\0"));
  }

  @Test
  void jsonEscapeRaw_nonAscii() {
    // chars > 127 become unicode escapes — unlike C++ which leaves UTF-8 bytes as-is
    // use concatenation to avoid Java unicode-escape processing in string literals
    assertEquals("\\" + "u2328", WebviewUtil.jsonEscape("⌨"));
    final var expected = """
  	\\\
  	u30d5\
  	\\\
  	u30fc\
  	\\\
  	u30d0\
  	\\\
  	u30fc""";
    assertEquals(expected, WebviewUtil.jsonEscape("フーバー"));
  }

  @Test
  void jsonEscapeRaw_xssGuard() {
    // alert("gotcha") → alert(\"gotcha\")  — eval-safe
    assertEquals("alert(\\\"gotcha\\\")", WebviewUtil.jsonEscape("alert(\"gotcha\")"));
  }

  // -------------------------------------------------------------------------
  // WebviewBase.jsonEscape — with surrounding double-quotes
  // -------------------------------------------------------------------------

  @Test
  void jsonEscapeQuoted_simple() {
    assertEquals("\"hello\"", WebviewBase.jsonEscape("hello"));
  }

  @Test
  void jsonEscapeQuoted_empty() {
    assertEquals("\"\"", WebviewBase.jsonEscape(""));
  }

  @Test
  void jsonEscapeQuoted_quotesInsideEscaped() {
    assertEquals("\"alert(\\\"gotcha\\\")\"", WebviewBase.jsonEscape("alert(\"gotcha\")"));
  }

  @Test
  void jsonEscapeQuoted_backslash() {
    assertEquals("\"\\\\\"", WebviewBase.jsonEscape("\\"));
  }
}
