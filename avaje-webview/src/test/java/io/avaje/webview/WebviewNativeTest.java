package io.avaje.webview;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WebviewNativeTest {

  @Test
  void test() {
    assertEquals("0.11.0", Webview.builder().build().version());
  }
}
