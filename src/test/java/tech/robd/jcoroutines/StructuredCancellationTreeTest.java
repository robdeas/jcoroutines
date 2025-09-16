/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/StructuredCancellationTreeTest.java
 description: Structured cancellation tree: nullable async returns; parent close cancels child+grandchild; child cancel doesn't affect parent.
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
 * You may not use this file except in compliance with the License.
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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.newScope;

final class StructuredCancellationTreeTest {


    @Test
    @Timeout(2)
        // Nullable async: async may legitimately return null and join() should reflect it;
        // Cancellation path: gated await() is interruptible so cancel() maps to CancellationException deterministically;
        // Race-avoidance: 'started' latch ensures entry before cancellation.
    void asyncCanReturnNull() throws Exception {
        try (JCoroutineScope scope = newScope()) {
            // --- explicit null return ---
            JCoroutineHandle<@org.jspecify.annotations.Nullable String> handle =
                    scope.<@org.jspecify.annotations.Nullable String>async(s -> {
                        s.delay(10);
                        return null;
                    });
            assertNull(handle.join());

            // --- conditional null return ---
            JCoroutineHandle<@org.jspecify.annotations.Nullable Integer> conditionalHandle =
                    scope.<@org.jspecify.annotations.Nullable Integer>async(s -> {
                        s.delay(5);
                        return Math.random() > 0.5 ? 42 : null;
                    });
            Integer result = conditionalHandle.join();
            assertTrue(result == null || result.equals(42));

            // --- cancellation with nullable async (robust, not relying on delay) ---
            AtomicBoolean cancelled = new AtomicBoolean(false);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1); // we will NOT open this

            JCoroutineHandle<@org.jspecify.annotations.Nullable String> cancellableHandle =
                    scope.<@org.jspecify.annotations.Nullable String>async(s -> {
                        try {
                            started.countDown();      // signal we've entered the body
                            gate.await();             // interruptible wait; cancel will interrupt
                            return null;              // unreachable if cancelled
                        } catch (CancellationException e) {
                            cancelled.set(true);
                            throw e;
                        } catch (InterruptedException ie) {
                            // Treat interruption as cancellation for test purposes
                            cancelled.set(true);
                            throw new CancellationException("Interrupted while parked");
                        }
                    });

            assertTrue(started.await(200, MILLISECONDS), "task didn't start in time");

            cancellableHandle.cancel();

            // join should fail for a cancelled async
            assertThrows(CancellationException.class, cancellableHandle::join);

            // and our flag inside the task should have observed the cancellation path exactly once
            assertTrue(cancelled.get(), "Task should have observed cancellation");
        }
    }


    @Test
    @Timeout(2)
        // Structured cancel: closing parent scope cancels child and its grandchild.
        // Race-avoidance: childStarted/grandStarted latches ensure both entered; shared gate.await() is interruptible;
        // join() must throw CancellationException and flags confirm cancellation observed in bodies.
    void parentCancelsChildrenAndGrandchildren() throws Exception {
        try (JCoroutineScope parent = newScope()) {
            AtomicBoolean childCancelled = new AtomicBoolean();
            AtomicBoolean grandCancelled = new AtomicBoolean();

            // Latches to ensure both tasks have actually started before closing the scope
            CountDownLatch childStarted = new CountDownLatch(1);
            CountDownLatch grandStarted = new CountDownLatch(1);
            // Gate both child + grandchild will wait on (interruptible)
            CountDownLatch gate = new CountDownLatch(1);

            JCoroutineHandle<Void> child = parent.launch(s -> {
                // launch grandchild inside child scope
                JCoroutineHandle<Void> grand = s.launch(x -> {
                    try {
                        grandStarted.countDown();     // grandchild entered runnable
                        gate.await();                  // park; will be interrupted on cancel
                        x.delay(1_000);                // won't be reached if cancelled at gate
                    } catch (CancellationException e) {
                        grandCancelled.set(true);
                        throw e;
                    } catch (InterruptedException ie) {
                        // Map interrupt to cancellation for test robustness
                        grandCancelled.set(true);
                        throw new CancellationException("grand interrupted -> cancelled");
                    }
                });

                try {
                    childStarted.countDown();          // child entered runnable
                    gate.await();                      // park; will be interrupted on cancel
                    s.delay(1_000);                    // won't be reached if cancelled at gate
                    // Optional: touch grand so the var isn't "unused" if your tooling warns
                    // grand.join();
                } catch (CancellationException e) {
                    childCancelled.set(true);
                    throw e;
                } catch (InterruptedException ie) {
                    childCancelled.set(true);
                    throw new CancellationException("child interrupted -> cancelled");
                }
            });

            // Ensure both child and grandchild are definitely running before cancelling
            assertTrue(childStarted.await(200, MILLISECONDS), "child didn't start in time");
            assertTrue(grandStarted.await(200, MILLISECONDS), "grandchild didn't start in time");

            // Cancel the whole tree
            parent.close();

            // Do not open the gate; cancellation should interrupt both awaits
            assertThrows(CancellationException.class, child::join);
            assertTrue(childCancelled.get(), "child should have observed cancellation");
            assertTrue(grandCancelled.get(), "grandchild should have observed cancellation");
        }
    }


    @Test
    @Timeout(3)
        // Isolation: cancelling a child does not cancel the parent; subsequent async in parent still completes.
        // Race-avoidance: 'gate' ensures child started; awaitDone(child) waits for completion before asserting survivor.join().
    void childCancelDoesNotAffectParent() throws Exception {
        try (JCoroutineScope parent = newScope()) {
            var gate = new CountDownLatch(1);
            JCoroutineHandle<Void> child = parent.launch(s -> {
                gate.countDown();
                s.delay(10_000);
            });
            assertTrue(gate.await(500, MILLISECONDS));

            assertDoesNotThrow(child::cancel);
            TestAwaitUtils.awaitDone(child, 500);
            assertTrue(child.result().isCancelled() || child.result().isCompletedExceptionally());
            assertThrows(CancellationException.class, child::join);

            JCoroutineHandle<Integer> survivor = parent.async(s -> {
                s.delay(5);
                return 1;
            });
            assertEquals(1, survivor.join());
        }
    }

}
