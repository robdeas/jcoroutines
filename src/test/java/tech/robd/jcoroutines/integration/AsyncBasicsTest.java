/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/AsyncBasicsTest.java
 description: Integration tests for basic async/launch behavior, error propagation, cancellation, and timeouts.
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
package tech.robd.jcoroutines.integration;

import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.Coroutines;
import tech.robd.jcoroutines.JCoroutineScope;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.Probes;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class AsyncBasicsTest {

    @Test
        // Verifies async work runs on a Virtual Thread (VT).
        // No race mitigations needed: single-step computation returns thread info.
    void async_returnsResult_onVirtualThread() throws Exception {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            // Launch async work; get the CF via .result()
            CompletableFuture<Probes.ThreadInfo> cf = scope.async(c -> {
                return Probes.here(); // should be VT
            }).result();

            Probes.ThreadInfo info = cf.join();
            assertTrue(info.virtual(), "async tasks should run on VT");
        }
    }

    @Test
        // Ensures CompletableFuture view wraps exceptions in CompletionException on join().
        // No race: exception is thrown immediately inside async block.
    void async_resultJoin_propagatesExceptionAsCompletionException() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            CompletableFuture<Object> cf = scope.async(c -> {
                throw new RuntimeException("boom");
            }).result();

            CompletionException ex = assertThrows(CompletionException.class, cf::join);
            Throwable wrapped = ex.getCause();
            assertNotNull(wrapped, "Expected cause not to be null");
            assertEquals("boom", wrapped.getMessage());
        }
    }

    @Test
        // Handle.join() should surface the original exception (unwrapped).
        // No race: exception is thrown synchronously in the task body.
    void async_handleJoin_throwsOriginalException() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            JCoroutineHandle<Integer> h = scope.async(c -> {
                throw new IllegalStateException("oops");
            });
            Exception ex = assertThrows(Exception.class, h::join);
            assertEquals("oops", ex.getMessage());
        }
    }

    @Test
        // Cancelling a handle should cause both views to throw CancellationException:
        //  - h.result().join() uses CF semantics;
        //  - h.join() uses handle semantics (also cancellation).
        // No race: cancel() is called before any join and the task is in delay.
    void async_cancellation_viaHandle_cancelThenJoin() throws Exception {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            var h = scope.async(c -> {
                c.delay(1_000);
                return null;
            });

            assertTrue(h.cancel(), "first cancel should return true");

            // 1) CompletableFuture view → CancellationException on join()
            assertThrows(CancellationException.class, () -> h.result().join());

            // 2) Handle's join() also surfaces cancellation (unchecked)
            assertThrows(CancellationException.class, h::join);
        }
    }

    @Test
        // Structured concurrency: child launched in runBlocking should be canceled when runBlocking exits.
        // Race-avoidance: two latches (childStarted, childInDelay) and a brief sleep ensure the child
        // has actually entered delay before we leave the runBlocking scope.
    void launch_structured_cancelOnRunBlockingExit_ifNotAwaited() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            CountDownLatch childStarted = new CountDownLatch(1);
            CountDownLatch childInDelay = new CountDownLatch(1);
            AtomicReference<JCoroutineHandle<Void>> childRef = new AtomicReference<>();

            JCoroutineHandle<Void> child = scope.runBlocking(c -> {
                JCoroutineHandle<Void> h = c.launch(cc -> {
                    childStarted.countDown();
                    childInDelay.countDown();
                    cc.delay(10_000); // This should be cancelled
                });

                childRef.set(h);

                // Wait for child to actually start the delay operation
                try {
                    assertTrue(childStarted.await(1, TimeUnit.SECONDS),
                            "Child didn't start");
                    assertTrue(childInDelay.await(1, TimeUnit.SECONDS),
                            "Child didn't reach delay");

                    // Give it a moment to actually be blocked in delay
                    Thread.sleep(50);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                return h;
            });

            // Verify the child handle is what we expect
            assertSame(child, childRef.get());

            // When we exit the try-with-resources, scope should cancel all children


            // Child should be canceled due to structured concurrency
            assertThrows(CancellationException.class, () -> {
                assertNotNull(child);
                child.result().join();
            });
            assertNotNull(child);
            assertTrue(child.isCompleted());
            assertFalse(child.isActive());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Test
        // Verifies CompletableFuture.orTimeout triggers timeout; accepts several wrapping shapes
        // (CompletionException with TimeoutException cause, direct TimeoutException, or RuntimeException with cause).
        // No race: delay(300ms) > timeout(100ms).
    void async_orTimeout_timesOut() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            CompletableFuture<Object> cf = scope.async(c -> {
                c.delay(300);
                return null;
            }).result();
            Throwable thrown = assertThrows(Throwable.class, () ->
                    cf.orTimeout(100, TimeUnit.MILLISECONDS).join()
            );

            switch (thrown) {
                case CompletionException ce -> assertInstanceOf(TimeoutException.class, ce.getCause());
                case TimeoutException timeoutException -> {
                    // direct cause path is also acceptable depending on wrappers
                }
                case RuntimeException re when re.getCause() instanceof TimeoutException -> {
                    // unwrapped to RuntimeException with TimeoutException cause
                }
                case null, default -> fail("Expected timeout; got: " + thrown);
            }
        }
    }
}
