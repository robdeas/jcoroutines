// src/main/java/tech/robd/jcoroutines/AsyncHandle.java
package tech.robd.jcoroutines;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class AsyncHandle<T> {
    private final Future<T> future;

    AsyncHandle(Future<T> future) {
        this.future = future;
    }

    /** Join and rethrow unchecked */
    public T join() {
        try {
            return future.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Interrupted");
        } catch (ExecutionException ee) {
            var cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    public boolean isDone() { return future.isDone(); }

    /** Best-effort cancel. */
    public void cancel() { future.cancel(true); }
}
