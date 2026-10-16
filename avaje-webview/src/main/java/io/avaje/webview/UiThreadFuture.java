package io.avaje.webview;

import module java.base;

/** A future completed on the webview's UI thread, which refuses to block that same thread. */
final class UiThreadFuture<T> extends CompletableFuture<T> {

  private final BooleanSupplier onUIThread;

  UiThreadFuture(BooleanSupplier onUIThread) {
    this.onUIThread = onUIThread;
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    checkNotBlockingUiThread();
    return super.get();
  }

  @Override
  public T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    checkNotBlockingUiThread();
    return super.get(timeout, unit);
  }

  @Override
  public T join() {
    checkNotBlockingUiThread();
    return super.join();
  }

  @Override
  public <U> CompletableFuture<U> newIncompleteFuture() {
    return new UiThreadFuture<>(onUIThread);
  }

  private void checkNotBlockingUiThread() {
    if (!isDone() && onUIThread.getAsBoolean()) {
      throw new IllegalStateException(
          "Cannot block the webview UI thread without deadlock. Use then*/whenComplete, or block from a different thread.");
    }
  }
}
