package io.avaje.webview.windows;

import io.avaje.webview.Webview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WebviewIntegrationTest {

  private static void rethrow(AtomicReference<Throwable> failure) {
    Throwable t = failure.get();
    if (t instanceof AssertionError ae) throw ae;
    if (t != null) throw new RuntimeException(t);
  }

  @Test
  void startAndTerminate() {
    try (var w = Webview.create(false)) {
      w.dispatch(w::close);
      w.run();
    }
  }

  @Test
  void bindAndUnbind() {
    var failure = new AtomicReference<Throwable>();
    var counter = new AtomicInteger(0);

    try (var w = Webview.create(false)) {
      w.bind("test", req -> {
        try {
          switch (req) {
            case "[0]" -> {
              assertEquals(0, counter.get());
              w.bind("increment", args -> { counter.incrementAndGet(); return "null"; });
              w.eval("try{window.increment().then(r=>window.test(1)).catch(()=>window.test(1,1))}catch{window.test(1,1)}");
            }
            case "[1]" -> {
              assertEquals(1, counter.get());
              w.unbind("increment");
              w.eval("try{window.increment().then(r=>window.test(2)).catch(()=>window.test(2,1))}catch{window.test(2,1)}");
            }
            case "[2,1]" -> {
              assertEquals(1, counter.get());
              w.bind("increment", args -> { counter.incrementAndGet(); return "null"; });
              w.eval("try{window.increment().then(r=>window.test(3)).catch(()=>window.test(3,1))}catch{window.test(3,1)}");
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
        } catch (Throwable t) {
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

  @Test
  void bindingReturnMustBeJson() {
    var failure = new AtomicReference<Throwable>();

    try (var w = Webview.create(false)) {
      w.bind("loadData", req -> "\"hello\"");
      w.bind("endTest", req -> {
        try {
          assertNotEquals("[1]", req, "Promise rejected — binding must return valid JSON");
          assertNotEquals("[2]", req, "Unexpected synchronous throw");
          assertEquals("[0]", req);
        } catch (Throwable t) {
          failure.set(t);
        } finally {
          w.dispatch(w::close);
        }
        return "null";
      });
      w.setHTML("""
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
    var failure = new AtomicReference<Throwable>();

    try (var w = Webview.create(false)) {
      w.bind("loadData", req ->
          "(()=>{document.body.innerHTML='gotcha';return 'hello';})()");
      w.bind("endTest", req -> {
        try {
          assertNotEquals("[0]", req, "Promise resolved — raw JS must be rejected");
          assertNotEquals("[2]", req, "Unexpected synchronous throw");
          assertEquals("[1]", req);
        } catch (Throwable t) {
          failure.set(t);
        } finally {
          w.dispatch(w::close);
        }
        return "null";
      });
      w.setHTML("""
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
}
