/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/TestAwaitUtils.java
 description: Tiny await toolkit for deterministic coroutine tests: awaitTrue, awaitLatch,
              awaitDone/Cancelled/Completed for JCoroutineHandle, and awaitFutureDone.
 license: Apache-2.0
 editable: yes
 structured: no
 [/File Info]
*/
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.robd.jcoroutines.tools;

import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic wait helpers for concurrent tests.
 */
public final class TestAwaitUtils {
    private TestAwaitUtils() {
    }

    /**
     * Poll a boolean condition until true or timeout (fails the test on timeout).
     */
    public static void awaitTrue(BooleanSupplier cond, long timeoutMs, long stepMs, String msg) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(stepMs);
            } catch (InterruptedException ignored) {
            }
        }
        fail(msg);
    }

    /**
     * Await a CountDownLatch or fail with a useful message.
     */
    public static void awaitLatch(CountDownLatch latch, long timeoutMs, String msg) {
        try {
            assertTrue(latch.await(timeoutMs, TimeUnit.MILLISECONDS), msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for latch: " + e);
        }
    }

    /**
     * Spin-wait until the handle's future is done (cancelled or completed) or fail on timeout.
     */
    public static void awaitDone(JCoroutineHandle<?> handle, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!handle.result().isDone() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(handle.result().isDone(), "Handle did not complete within " + timeoutMs + "ms");
    }

    /**
     * Confirm a handle cancels within timeout and that join() throws CancellationException.
     */
    public static void awaitCancelled(JCoroutineHandle<?> handle, long timeoutMs) {
        awaitDone(handle, timeoutMs);
        assertTrue(handle.result().isCancelled() || handle.result().isCompletedExceptionally(),
                "Future not cancelled/exceptional after completion");
        assertThrows(CancellationException.class, handle::join, "join() should throw CancellationException");
    }

    /**
     * Wait for successful completion and return the result.
     * Fails if cancelled/exceptional or if not done by the timeout.
     */
    public static <T> T awaitCompleted(JCoroutineHandle<T> handle, long timeoutMs) {
        awaitDone(handle, timeoutMs);
        if (handle.result().isCancelled()) {
            fail("Handle was cancelled");
        }
        try {
            return handle.join(); // your handle.join() rethrows underlying exceptions cleanly
        } catch (CancellationException ce) {
            fail("Handle cancelled unexpectedly: " + ce);
            throw ce; // unreachable
        } catch (RuntimeException re) {
            fail("Handle completed exceptionally: " + re);
            throw re; // unreachable
        } catch (Exception e) {
            fail("Handle join threw checked exception: " + e);
            throw new RuntimeException(e); // unreachable
        }
    }

    /**
     * Await a raw CompletableFuture completion (any outcome) or fail on timeout.
     */
    public static void awaitFutureDone(CompletableFuture<?> cf, long timeoutMs) {
        try {
            cf.get(timeoutMs, TimeUnit.MILLISECONDS); // we don't care about value; just completion
        } catch (TimeoutException te) {
            fail("Future not done within " + timeoutMs + "ms");
        } catch (CancellationException | ExecutionException ignored) {
            // done (cancelled or exceptional) is fine
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for future: " + ie);
        }
    }


    /**
     * Await a handle up to {@code timeoutMs} and return its result.
     * Fails the test if it times out, is cancelled, or completes exceptionally.
     */
    public static <T> T await(JCoroutineHandle<T> handle, long timeoutMs) {
        return awaitCompleted(handle, timeoutMs);
    }

    /**
     * Await a CompletableFuture up to {@code timeoutMs} and return its value.
     * Fails the test if it times out, is cancelled, or completes exceptionally.
     */
    public static <T> T await(CompletableFuture<T> cf, long timeoutMs) {
        try {
            return cf.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            fail("Future not done within " + timeoutMs + "ms");
            throw new AssertionError(te); // unreachable
        } catch (CancellationException ce) {
            fail("Future was cancelled");
            throw ce; // unreachable
        } catch (ExecutionException ee) {
            fail("Future completed exceptionally: " + ee.getCause());
            throw new AssertionError(ee); // unreachable
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("Interrupted while awaiting future: " + ie);
            throw new AssertionError(ie); // unreachable
        }
    }

    /**
     * Await a {@link CountDownLatch} for up to the given timeout.
     *
     * <p>This is a simple convenience that returns {@code true} if the latch
     * reached zero within the timeout, or {@code false} if it did not.
     * Unlike {@link #awaitLatch(CountDownLatch, long, String)} it does not
     * fail the test automatically.</p>
     *
     * @param latch   the latch to wait on (must not be null)
     * @param timeoutMs maximum time to wait, in milliseconds
     * @return {@code true} if the latch counted down to zero within the timeout,
     *         {@code false} otherwise
     */
    public static boolean await(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
