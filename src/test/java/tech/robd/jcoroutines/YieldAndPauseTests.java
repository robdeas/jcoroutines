/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/YieldAndPauseTests.java
 description: yieldAndPause behavior: cancellation propagation, normal completion, already-cancelled token, interruption mapping, PT fast-path, zero/negative durations, and checkChannelsFrequently.
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
import org.junit.jupiter.api.TestInstance;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.JCoroutineScopeImpl;
import tech.robd.jcoroutines.tools.DebugUtils;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YieldAndPauseTests {

    @Test
        // Cancellation propagation: cancelling the handle during yieldAndPause should surface CancellationException on join().
        // Race-avoidance: CountDownLatch 'started' ensures the task entered the suspend call before cancel().
    void yieldAndPause_propagatesCancellationException() {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);

            JCoroutineHandle<Void> handle = scope.async(ctx -> {
                started.countDown();
                try {
                    ctx.yieldAndPause(Duration.ofSeconds(5));
                    return null;
                } catch (CancellationException e) {
                    cancelled.set(true);
                    throw e;
                }
            });

            // Wait for operation to start
            assertTrue(started.await(1, TimeUnit.SECONDS));

            // Cancel and verify cancellation propagates
            handle.cancel();
            assertThrows(CancellationException.class, handle::join);
            assertTrue(cancelled.get(), "CancellationException should have been thrown and caught");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
        // Normal completion: short yieldAndPause should complete without throwing; side-effect flag set to true.
        // No race: single op with deterministic short duration.
    void yieldAndPause_completesNormallyWithoutCancellation() {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            AtomicBoolean completed = new AtomicBoolean(false);

            JCoroutineHandle<Void> handle = scope.async(ctx -> {
                ctx.yieldAndPause(Duration.ofMillis(100)); // Short duration
                completed.set(true);
                return null;
            });

            // Should complete without exception
            assertDoesNotThrow(handle::join);
            assertTrue(completed.get(), "Operation should have completed normally");
        }
    }

    @Test
        // Pre-cancelled token: yieldAndPause should throw CancellationException immediately when token is already cancelled.
        // No race: token.cancel() is invoked before the call.
    void yieldAndPause_throwsOnAlreadyCancelledToken() {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            SuspendContext ctx = SuspendContext.create(scope);

            // Cancel the token first
            ctx.getCancellationToken().cancel();

            // Should throw immediately
            assertThrows(CancellationException.class, () -> {
                ctx.yieldAndPause(Duration.ofSeconds(1));
            });
        }
    }

    @Test
        // Interruption mapping: interrupting a VT blocked in yieldAndPause should surface as CancellationException.
        // Race-avoidance: a pauseStarted flag + small sleeps ensure the thread is actually blocked before interrupt();
        // finished latch prevents timing flakiness when asserting the caught exception.
    void yieldAndPause_handlesInterruption() throws InterruptedException {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            AtomicReference<Exception> caughtException = new AtomicReference<>();
            AtomicBoolean pauseStarted = new AtomicBoolean(false);
            CountDownLatch finished = new CountDownLatch(1);

            Thread testThread = Thread.ofVirtual().start(() -> {
                try {
                    SuspendContext ctx = SuspendContext.create(scope);

                    // Hook into yieldAndPause to signal when it actually starts blocking
                    pauseStarted.set(true);
                    ctx.yieldAndPause(Duration.ofSeconds(5));

                    fail("Should have been interrupted");
                } catch (Exception e) {
                    caughtException.set(e);
                } finally {
                    finished.countDown();
                }
            });

            // Wait for pause to actually start with polling
            int attempts = 0;
            while (!pauseStarted.get() && attempts < 100) {
                Thread.sleep(10);
                attempts++;
            }
            assertTrue(pauseStarted.get(), "Pause never started");

            // Additional wait to ensure we're blocked
            Thread.sleep(50);

            testThread.interrupt();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, caughtException.get());
        }
    }

    @Test
        // Platform-thread fast path: on PT, yieldAndPause should return immediately (measured < 50ms).
        // Race-avoidance: join() the PT to ensure timing is measured after completion.
    void yieldAndPause_returnsImmediatelyOnNonVirtualThread() throws InterruptedException {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            SuspendContext ctx = SuspendContext.create(scope);
            AtomicLong elapsed = new AtomicLong();

            // Run on platform thread
            Thread platformThread = Thread.ofPlatform().start(() -> {
                long start = System.currentTimeMillis();
                ctx.yieldAndPause(Duration.ofSeconds(1));
                elapsed.set(System.currentTimeMillis() - start);
            });

            platformThread.join(1000);

            // Should return immediately (< 50ms)
            assertTrue(elapsed.get() < 50, "Should return immediately on platform thread, took: " + elapsed.get() + "ms");
        }
    }

    @Test
        // Edge cases: zero and negative durations should be handled gracefully without throwing.
        // No race: direct calls.
    void yieldAndPause_handlesZeroAndNegativeDurations() {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            SuspendContext ctx = SuspendContext.create(scope);

            // Zero duration should return immediately
            assertDoesNotThrow(() -> ctx.yieldAndPause(Duration.ZERO));

            // Negative duration should be handled gracefully
            assertDoesNotThrow(() -> ctx.yieldAndPause(Duration.ofMillis(-100)));
        }
    }

    @Test
        // Frequent checking: when 'checkChannelsFrequently' is true, token checks should be >= the normal path.
        // Race-avoidance: custom token increments a counter in isCancelled(); runs two pauses back-to-back and compares counts.
    void yieldAndPause_respectsCheckChannelsFrequently() {
        try (JCoroutineScope scope = new JCoroutineScopeImpl()) {
            AtomicInteger cancellationChecks = new AtomicInteger(0);


            CancellationToken countingToken = new CancellationToken() {
                @Override
                public boolean isCancelled() {
                    cancellationChecks.incrementAndGet();
                    return false;
                }

                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public AutoCloseable onCancel(Runnable callback) {
                    return () -> {
                    };
                }

                @Override
                public void onCancelQuietly(Runnable callback) {
                }

                @Override
                public CancellationToken child() {
                    return this;
                }
            };

            SuspendContext ctx = SuspendContext.create(scope, countingToken);


            ctx.yieldAndPause(Duration.ofMillis(500), true);
            int frequentChecks = cancellationChecks.get();

            cancellationChecks.set(0);


            ctx.yieldAndPause(Duration.ofMillis(500), false);
            int normalChecks = cancellationChecks.get();

            DebugUtils.print("Frequent checks: " + frequentChecks);
            System.out.println("Normal checks: " + normalChecks);

            assertTrue(frequentChecks >= normalChecks,
                    "Frequent checking should check at least as often: " + frequentChecks + " vs " + normalChecks);
        }
    }


}