package io.avaje.webview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WebviewIntegrationTest {

  private static void rethrow(AtomicReference<Throwable> failure) {
    final var t = failure.get();
    if (t instanceof final AssertionError ae) throw ae;
    if (t != null) throw new RuntimeException(t);
  }

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  @Test
  void startAndTerminate() {
    try (var w = Webview.builder().build()) {
      w.dispatch(w::close);
      w.run();
    }
  }

  @Test
  void closeIsIdempotent() {
    try (var w = Webview.builder().build()) {
      w.dispatch(
          () -> {
            w.close();
            w.close();
          });
      w.run();
    }
  }

  @Test
  void closeFromBackgroundThread() {
    try (var w = Webview.builder().build()) {
      // Signal from inside the event loop so the webview is fully running before we close.
      w.bind(
          "__ready__",
          _ -> {
            Thread.ofVirtual().start(w::close);
            return "null";
          });
      w.setHTML("<h1>Hello World!</h1><script>window.__ready__();</script>");
      w.run();
    }
  }

  @Test
  void nativeWindowPointerNonNull() {
    try (var w = Webview.builder().build()) {
      assertNotEquals(0L, w.nativeWindowPointer().address());
      w.dispatch(w::close);
      w.run();
    }
  }

  // -------------------------------------------------------------------------
  // Bindings — invocation
  // -------------------------------------------------------------------------

  @Test
  void bindCallbackRuns() {
    final var called = new AtomicBoolean(false);

    try (var w = Webview.builder().build()) {
      w.bind(
          "ping",
          _ -> {
            called.set(true);
            w.dispatch(w::close);
            return "null";
          });
      w.setHTML("<script>window.ping();</script>");
      w.run();
    }
    assertTrue(called.get(), "bind callback was never invoked");
  }

  @Test
  void multipleBindingsAllInvoked() {
    final var callCount = new AtomicInteger(0);
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind(
          "a",
          _ -> {
            callCount.incrementAndGet();
            return "null";
          });
      w.bind(
          "b",
          _ -> {
            callCount.incrementAndGet();
            return "null";
          });
      w.bind(
          "c",
          _ -> {
            callCount.incrementAndGet();
            return "null";
          });
      w.bind(
          "done",
          _ -> {
            try {
              assertEquals(3, callCount.get());
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML(
          "<script>Promise.all([window.a(), window.b(), window.c()]).then(() => window.done());</script>");
      w.run();
    }
    rethrow(failure);
    assertEquals(3, callCount.get());
  }

  @Test
  void bindWithJsonParams() {
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind(
          "test",
          req -> {
            try {
              assertEquals("[\"hello\",42,true]", req);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML("<script>window.test('hello', 42, true);</script>");
      w.run();
    }
    rethrow(failure);
  }

  @Test
  void bindReturnValueReachesJs() {
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind("getData", _ -> "\"hello world\"");
      w.bind(
          "check",
          req -> {
            try {
              assertEquals("[\"hello world\"]", req);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML("<script>window.getData().then(r => window.check(r));</script>");
      w.run();
    }
    rethrow(failure);
  }

  @Test
  void bindReturnNullJsonResolvesWithNull() {
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind("getNull", _ -> "null");
      w.bind(
          "check",
          req -> {
            try {
              assertEquals("[null]", req);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML("<script>window.getNull().then(r => window.check(r));</script>");
      w.run();
    }
    rethrow(failure);
  }

  @Test
  void bindAndUnbind() {
    final var failure = new AtomicReference<Throwable>();
    final var counter = new AtomicInteger(0);

    try (var w = Webview.builder().build()) {
      w.bind(
          "test",
          req -> {
            try {
              switch (req) {
                case "[0]" -> {
                  assertEquals(0, counter.get());
                  w.bind(
                      "increment",
                      _ -> {
                        counter.incrementAndGet();
                        return "null";
                      });
                  w.eval(
                      "try{window.increment().then(r=>window.test(1)).catch(()=>window.test(1,1))}catch{window.test(1,1)}");
                }
                case "[1]" -> {
                  assertEquals(1, counter.get());
                  w.unbind("increment");
                  w.eval(
                      "try{window.increment().then(r=>window.test(2)).catch(()=>window.test(2,1))}catch{window.test(2,1)}");
                }
                case "[2,1]" -> {
                  assertEquals(1, counter.get());
                  w.bind(
                      "increment",
                      _ -> {
                        counter.incrementAndGet();
                        return "null";
                      });
                  w.eval(
                      "try{window.increment().then(r=>window.test(3)).catch(()=>window.test(3,1))}catch{window.test(3,1)}");
                }
                case "[3]" -> {
                  assertEquals(2, counter.get());
                  w.dispatch(w::close);
                }
                default -> {
                  failure.set(new AssertionError("Unexpected args: " + req));
                  w.dispatch(w::close);
                }
              }
            } catch (final Throwable t) {
              failure.set(t);
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML("<script>window.test(0);</script>");
      w.run();
    }
    rethrow(failure);
  }

  // -------------------------------------------------------------------------
  // Bindings — return value contract
  // -------------------------------------------------------------------------

  @Test
  void bindingReturnMustBeJson() {
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().enableDeveloperTools(true).build()) {
      w.bind("loadData", _ -> "\"hello\"");
      w.bind(
          "endTest",
          req -> {
            try {
              assertNotEquals("[1]", req, "Promise rejected — binding must return valid JSON");
              assertNotEquals("[2]", req, "Unexpected synchronous throw");
              assertEquals("[0]", req);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML(
          """
          <script>
            try {
              window.loadData()
                .then(() => window.endTest(0))
                .catch(() => window.endTest(1));
            } catch {
              window.endTest(2);
            }
          </script>""");
      w.run();
    }
    rethrow(failure);
  }

  @Test
  void bindingReturnMustNotBeJs() {
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind("loadData", _ -> "(()=>{document.body.innerHTML='gotcha';return 'hello';})()");
      w.bind(
          "endTest",
          req -> {
            try {
              assertNotEquals("[0]", req, "Promise resolved — raw JS must be rejected");
              assertNotEquals("[2]", req, "Unexpected synchronous throw");
              assertEquals("[1]", req);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML(
          """
          <script>
            try {
              window.loadData()
                .then(() => window.endTest(0))
                .catch(() => window.endTest(1));
            } catch {
              window.endTest(2);
            }
          </script>""");
      w.run();
    }
    rethrow(failure);
  }

  // -------------------------------------------------------------------------
  // eval
  // -------------------------------------------------------------------------

  @Test
  void evalInvokesBinding() {
    final var called = new AtomicBoolean(false);

    try (var w = Webview.builder().build()) {
      w.bind(
          "done",
          _ -> {
            called.set(true);
            w.dispatch(w::close);
            return "null";
          });
      w.bind(
          "ready",
          _ -> {
            w.eval("window.done()");
            return "null";
          });
      w.setHTML("<script>window.ready();</script>");
      w.run();
    }
    assertTrue(called.get(), "eval did not invoke binding");
  }

  // -------------------------------------------------------------------------
  // init script
  // -------------------------------------------------------------------------

  @Test
  void setInitScriptRunsOnLoad() {
    final var failure = new AtomicReference<Throwable>();
    final var called = new AtomicBoolean(false);

    try (var w = Webview.builder().build()) {
      w.setInitScript("window.__initRan = true;");
      w.bind(
          "check",
          req -> {
            try {
              assertEquals("[true]", req);
              called.set(true);
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              w.dispatch(w::close);
            }
            return "null";
          });
      w.setHTML("<script>window.check(window.__initRan === true);</script>");
      w.run();
    }
    rethrow(failure);
    assertTrue(called.get());
  }

  // -------------------------------------------------------------------------
  // setHTML — content replacement
  // -------------------------------------------------------------------------

  @Test
  void setHTMLCanBeCalledMultipleTimes() {
    final var callCount = new AtomicInteger(0);
    final var failure = new AtomicReference<Throwable>();

    try (var w = Webview.builder().build()) {
      w.bind(
          "loaded",
          req -> {
            final var count = callCount.incrementAndGet();
            if (count == 1) {
              assertEquals("[1]", req);
              w.setHTML("<script>window.loaded(2);</script>");
            } else {
              try {
                assertEquals("[2]", req);
              } catch (final Throwable t) {
                failure.set(t);
              } finally {
                w.dispatch(w::close);
              }
            }
            return "null";
          });
      w.setHTML("<script>window.loaded(1);</script>");
      w.run();
    }
    rethrow(failure);
    assertEquals(2, callCount.get());
  }

  // -------------------------------------------------------------------------
  // Multiple windows
  // -------------------------------------------------------------------------

  /**
   * Creates a second window from a background platform thread while the first window's GTK event
   * loop is already running. The second window dispatches its init to the GTK thread and blocks its
   * own run() on a CountDownLatch. Both bindings must fire; w2 closes before w1 so that openWindows
   * reaches 0 only after w2's latch is signaled and w2.run() can return.
   */
  @Test
  void multipleWindowsBothBindingsFire() throws InterruptedException {
    final var w1Pinged = new AtomicBoolean(false);
    final var w2Pinged = new AtomicBoolean(false);
    final var failure = new AtomicReference<Throwable>();
    final var w2Thread = new AtomicReference<Thread>();

    try (var w1 = Webview.builder().build()) {
      w1.bind(
          "ping1",
          _ -> {
            w1Pinged.set(true);
            // Spawn a platform thread (not virtual) so GTK thread-identity checks work correctly.
            final var t =
                Thread.ofPlatform()
                    .start(
                        () -> {
                          try (var w2 = Webview.builder().build()) {
                            w2.bind(
                                "ping2",
                                _ -> {
                                  try {
                                    w2Pinged.set(true);
                                    // Close w2 first (openWindows 2→1), then w1 (1→0 stops the GTK
                                    // loop).
                                    // Both dispatch() calls run immediately since we are on the GTK
                                    // thread here.
                                    w2.dispatch(w2::close);
                                    w1.dispatch(w1::close);
                                  } catch (final Throwable t2) {
                                    failure.set(t2);
                                    w1.dispatch(w1::close);
                                  }
                                  return "null";
                                });
                            w2.setHTML("<script>window.ping2();</script>");
                            w2.run(); // blocks on windowClosedLatch until w2 is closed above
                          } catch (final Throwable t2) {
                            failure.set(t2);
                            w1.dispatch(w1::close);
                          }
                        });
            w2Thread.set(t);
            return "null";
          });

      w1.setHTML("<script>window.ping1();</script>");
      w1.run();

      // Join the w2 thread so assertions see its writes.
      final var t = w2Thread.get();
      if (t != null) t.join(5_000);
    }

    rethrow(failure);
    assertTrue(w1Pinged.get(), "window 1 ping was not called");
    assertTrue(w2Pinged.get(), "window 2 ping was not called");
  }
}
