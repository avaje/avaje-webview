package io.avaje.webview;

/**
 * The error a {@link Webview#evalResult(String)} future fails with when the JavaScript has a
 * syntax error, throws, rejects, or returns something that can't be turned into JSON.
 */
public final class JavaScriptException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception with the error JavaScript reported.
   *
   * @param message the JavaScript error, usually like {@code "TypeError: x is undefined"}
   */
  public JavaScriptException(String message) {
    super(message);
  }
}
