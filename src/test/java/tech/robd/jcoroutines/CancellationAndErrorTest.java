/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/CancellationAndErrorTest.java
 description: Cancellation/error integration tests: interruptible pauses, delay semantics, channel receive cancel, handle state, token behavior, and scope cleanup.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 tags: [robokeytags,v1]
 [/File Info]
*/
/*
 * Copyright (c) 2025 Rob Deas Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package tech.robd.jcoroutines;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.TestAwaitUtils;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.*;

final class CancellationAndErrorTest {

    @Test
    @Timeout(3)
        // Interruptible pause: yieldAndPause(Duration, /*checkChannelsFrequently=*/true) should observe cancel promptly.
        // Race-avoidance: CountDownLatch 'entered' ensures task is in pause before cancel; elapsed < 150ms assertion.
    void cancellationDuringPauseIsImmediate() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);

        JCoroutineHandle<Void> h = Coroutines.launch(s -> {
            entered.countDown();
            // Use the interruptible primitive when you want fast cancel
            s.yieldAndPause(Duration.ofSeconds(10), true);
        });

        assertTrue(TestAwaitUtils.await(entered, 500L), "task never entered pause");

        long cancelAt = System.nanoTime();
        assertDoesNotThrow(h::cancel);

        TestAwaitUtils.awaitDone(h, 500); // helper: waits until h.result().isDone()

        // join should surface CancellationException immediately once future is cancelled
        assertThrows(CancellationException.class, h::join);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt);
        assertTrue(elapsedMs < 150, "cancel propagation too slow: " + elapsedMs + "ms");
    }


    @Test
    @Timeout(3)
        // Delay semantics: cancel should flip handle to done quickly even if delay is non-interruptible (task may sleep).
        // Race-avoidance: 'entered' gate ensures delay started before cancel; assertions use handle state + join().
    void cancellationDuringDelayCancelsHandleQuickly_butTaskMaySleep() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);

        JCoroutineHandle<Void> h = Coroutines.launch(s -> {
            entered.countDown();
            s.delay(10_000); // true delay; not required to break immediately
        });

        assertTrue(TestAwaitUtils.await(entered, 500), "task never entered delay");

        assertDoesNotThrow(h::cancel);

        // The handle's future should flip to done/cancelled promptly
        TestAwaitUtils.awaitDone(h, 500);
        assertTrue(h.result().isCancelled() || h.result().isCompletedExceptionally());

        // API view: join surfaces CancellationException directly
        assertThrows(CancellationException.class, h::join);
    }



    @Test
    @Timeout(2)
        // Channel blocking receive: cancel should unblock receive and surface CancellationException.
        // Race-avoidance: started/blocked latches and a brief sleep ensure receiver is truly parked before cancel;
        // idempotent cancel verified (first true, second false).
    void cancellationDuringChannelOperations() throws Exception {
        Channel<String> ch = ChannelUtils.unlimited();
        AtomicBoolean receiveCancelled = new AtomicBoolean(false);
        CountDownLatch receiveStarted = new CountDownLatch(1);
        CountDownLatch receiveBlocked = new CountDownLatch(1);

        try {
            JCoroutineHandle<String> receiver = async(s -> {
                try {
                    receiveStarted.countDown();

                    // Signal we're about to block, then actually block
                    receiveBlocked.countDown();
                    return ChannelUtils.receive(s, ch); // This should block
                } catch (CancellationException e) {
                    receiveCancelled.set(true);
                    throw e;
                }
            });

            // Wait for receiver to actually start and be blocked
            assertTrue(receiveStarted.await(500, TimeUnit.MILLISECONDS),
                    "Receiver didn't start");
            assertTrue(receiveBlocked.await(500, TimeUnit.MILLISECONDS),
                    "Receiver didn't reach blocking call");

            // Give it a moment to actually be blocked in the receive operation
            Thread.sleep(50);

            // Cancel once
            assertTrue(receiver.cancel(), "First cancel should return true");
            assertFalse(receiver.cancel(), "Second cancel should return false");

            // Verify cancellation propagated
            assertThrows(CancellationException.class, receiver::join);
            assertTrue(receiver.result().isCancelled());
            assertTrue(receiveCancelled.get(), "Receive operation should have been cancelled");

        } finally {
            ChannelUtils.close(ch);
        }
    }

    @Test
    @Timeout(2)
        // Exception propagation: RuntimeException inside async surfaces via handle.join() and marks CF exceptional.
        // No race: delay(10) just to ensure async scheduling; failure shape asserted.
    void exceptionPropagationThroughHandles() {
        JCoroutineHandle<String> handle = async(s -> {
            s.delay(10);
            throw new RuntimeException("Test exception");
        });

        RuntimeException exception = assertThrows(RuntimeException.class, () -> handle.join());
        assertEquals("Test exception", exception.getMessage());

        assertTrue(handle.isCompleted());
        assertFalse(handle.isActive());
        assertTrue(handle.result().isCompletedExceptionally());
    }


    @Test
    @Timeout(2)
        // Idempotent cancel: multiple concurrent cancel() calls should result in one logical cancellation path.
        // Race-avoidance: 'started' ensures the body is inside await(); 'gate' prevents normal completion;
        // join() vs handle state both accepted as evidence of cancellation.
    void concurrentCancellation() throws Exception {
        AtomicInteger cancellationCount = new AtomicInteger(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);

        try (JCoroutineScope scope = newScope()) {
            JCoroutineHandle<Void> handle = scope.launch(s -> {
                try {
                    started.countDown();    // we've entered the runnable

                    // Wait here; this is interruptible and will throw InterruptedException on cancel.
                    gate.await();

                    // If cancellation didn’t happen, do a tiny bit of work (won’t be reached on cancel)
                    s.delay(10);
                } catch (CancellationException e) {
                    cancellationCount.incrementAndGet();
                    throw e;
                } catch (InterruptedException ie) {
                    // Treat interrupts as cancellation for test robustness
                    cancellationCount.incrementAndGet();
                    throw new CancellationException("Interrupted while waiting at gate");
                }
            });

            // Ensure the task is actually running before we attempt concurrent cancellation
            assertTrue(started.await(200, TimeUnit.MILLISECONDS), "task didn't start in time");

            // Multiple threads try to cancel simultaneously (idempotent)
            Thread t1 = new Thread(handle::cancel);
            Thread t2 = new Thread(handle::cancel);
            Thread t3 = new Thread(handle::cancel);
            t1.start();
            t2.start();
            t3.start();
            t1.join();
            t2.join();
            t3.join();

            // Do not release the gate; cancellation should interrupt the await
            // and cause the block to exit via our catch above.

            // Verify cancellation via join() OR via handle state (depending on your impl)
            boolean cancelledViaJoin = false;
            try {
                handle.join();
            } catch (CancellationException ce) {
                cancelledViaJoin = true;
            }

            assertTrue(cancelledViaJoin || handle.result().isCancelled(), "handle should be cancelled");

            // Body executes once → we observe exactly one cancellation-path increment
            assertEquals(1, cancellationCount.get(), "cancellation observed exactly once");
        }
    }


    @Test
    @Timeout(2)
        // Handle lifecycle: isActive/isCompleted flags and CF states before/after completion; join returns value.
        // No race: small delay then return 42.
    void handleStateTransitions() throws Exception {
        JCoroutineHandle<Integer> handle = async(s -> {
            s.delay(20);
            return 42;
        });

        // Initially active
        assertTrue(handle.isActive());
        assertFalse(handle.isCompleted());

        // Wait for completion
        int result = handle.join();
        assertEquals(42, result);

        // Now completed
        assertFalse(handle.isActive());
        assertTrue(handle.isCompleted());
        assertFalse(handle.result().isCancelled());
        assertFalse(handle.result().isCompletedExceptionally());
    }

    @Test
    @Timeout(2)
        // Token API: onCancel returns a registration that can be closed; cancel flips token and fires callback.
        // Race-avoidance: tiny sleep lets body start; assertion occurs after cancel + join.
    void cancellationTokenBehavior() {
        JCoroutineHandle<Void> handle = launch(s -> {
            CancellationToken token = s.getCancellationToken();
            assertFalse(token.isCancelled());

            AtomicBoolean callbackFired = new AtomicBoolean(false);
            AutoCloseable autoCloseable = token.onCancel(() -> callbackFired.set(true));

            try {
                s.delay(100);
                autoCloseable.close();
            } catch (CancellationException e) {
                assertTrue(token.isCancelled());
                assertTrue(callbackFired.get());
                throw e;
            }
        });

        // Let it start
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }

        handle.cancel();
        assertThrows(CancellationException.class, handle::join);

    }

    @Test
    @Timeout(2)
        // Token hierarchy: cancel child token; parent remains not-cancelled.
        // No race: performed inside runBlockingCpu.
    void childTokenCancellationDoesNotAffectParent() throws Exception {
        AtomicReference<CancellationToken> parentToken = new AtomicReference<>();
        AtomicReference<CancellationToken> childToken = new AtomicReference<>();

        runBlockingCpu(s -> {
            parentToken.set(s.getCancellationToken());

            SuspendContext child = s.child();
            childToken.set(child.getCancellationToken());

            // Cancel child
            child.getCancellationToken().cancel();

            // Parent should still be active
            CancellationToken parent = parentToken.get();
            CancellationToken childTok = childToken.get();

            assertNotNull(parent);
            assertNotNull(childTok);
            assertFalse(parent.isCancelled());
            assertTrue(childTok.isCancelled());

            return (Void) null;
        });
    }

    @Test
    @Timeout(3)
        // Structured cancel on scope.close(): N launched tasks loop with checkCancellation()+yieldAndPause to be cancel-observable;
        // post-close, unwrap join results to count cancellations; avoids single long sleep to reduce flakiness.
    void resourceCleanupOnScopeClose() throws Exception {
        final int N = 5;
        CountDownLatch started = new CountDownLatch(N);
        JCoroutineHandle<?>[] handles;

        try (JCoroutineScope scope = newScope()) {
            handles = new JCoroutineHandle[N];

            for (int i = 0; i < N; i++) {
                handles[i] = scope.launch(s -> {
                    started.countDown();

                    // Cancellation-observable wait:
                    // Prefer a loop that periodically checks cancellation over a single long sleep.
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (System.nanoTime() < until) {
                        s.checkCancellation();          // throws if cancelled
                        s.yieldAndPause(Duration.ofMillis(10), true); // brief, cooperative pause
                    }
                });
            }

            // Give tasks time to enter their blocks
            assertTrue(started.await(1, TimeUnit.SECONDS), "tasks didn't all start in time");

            // Cancel everything
            scope.close();
        }

        // Unwrap 'join()' according to your handle contract
        int cancelled = 0;
        for (JCoroutineHandle<?> h : handles) {
            try {
                h.join();
            } catch (java.util.concurrent.CancellationException ce) {
                cancelled++;
            } catch (java.util.concurrent.CompletionException ce) {
                if (ce.getCause() instanceof java.util.concurrent.CancellationException) {
                    cancelled++;
                }
            } catch (Throwable ignored) {
                // treat others as not-cancelled for this assertion
            }
        }

        assertEquals(N, cancelled, "All tasks should be cancelled by scope close; got " + cancelled + "/" + N);
    }


    @Test
    @Timeout(2)
        // Timeout path: withTimeout(Duration.ofMillis(10)) cancels inner block; outer runBlockingCpu observes CancellationException.
        // No race: inner delay(100) is >> timeout.
    void timeoutCancellation() {
        assertThrows(CancellationException.class, () -> {
            runBlockingCpu(s -> {
                return s.withTimeout(Duration.ofMillis(10), ctx -> {
                    ctx.delay(100); // Will timeout
                    return "should not complete";
                });
            });
        });
    }

    @Test
    @Timeout(2)
        // seconds
    void massiveConcurrentOperations() throws Exception {
        final int COUNT = 100;
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger cancelled = new AtomicInteger(0);

        // Gate to keep all tasks parked inside their runnable until we decide who to cancel.
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger(0);

        JCoroutineHandle<?>[] handles;

        try (JCoroutineScope scope = newScope()) {
            handles = new JCoroutineHandle[COUNT];

            // Launch a lot of tasks
            for (int i = 0; i < COUNT; i++) {
                final int taskId = i;
                handles[i] = scope.launch(s -> {
                    try {
                        // mark that this task has actually entered the runnable
                        started.incrementAndGet();

                        // park here until the test releases the gate
                        gate.await();

                        // then do some work that is cancellable (delay should observe interruption)
                        s.delay(100 + (taskId % 50));
                        completed.incrementAndGet();

                    } catch (CancellationException e) {
                        cancelled.incrementAndGet();
                        throw e;
                    } catch (InterruptedException ie) {
                        // gate.await() can be interrupted by cancellation; treat as cancellation
                        cancelled.incrementAndGet();
                        throw new CancellationException("Interrupted while waiting at gate");
                    }
                });
            }

            // Wait until at least half have *entered* their runnable (avoid waiting for all on small pools)
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (started.get() < COUNT / 2 && System.nanoTime() < deadlineNanos) {
                Thread.sleep(5);
            }

            // Cancel half of them
            for (int i = COUNT / 2; i < COUNT; i++) {
                handles[i].cancel();
            }

            // Let the rest proceed
            gate.countDown();

            // Give time for both completions and cancellations to settle
            Thread.sleep(200);
        }

        int total = completed.get() + cancelled.get();
        assertTrue(total >= COUNT / 2, "Should have processed at least half: " + total);
        assertTrue(cancelled.get() > 0, "Some should be cancelled: " + cancelled.get());
    }
}