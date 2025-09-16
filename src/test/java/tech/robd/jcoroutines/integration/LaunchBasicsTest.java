/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/LaunchBasicsTest.java
 description: Basic launch tests: VT execution, handle-driven cancellation, structured-cancellation on runBlocking exit, multi-child waits, and exception surfacing.
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
package tech.robd.jcoroutines.integration;


import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.Coroutines;
import tech.robd.jcoroutines.JCoroutineScope;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.Probes;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class LaunchBasicsTest {

    @Test
        // VT execution: launch body runs on a Virtual Thread; handle's CF completes when body returns.
        // No race: single-step body completes immediately after start.
    void launch_runsOnVirtualThread_andCompletes() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            CompletableFuture<Probes.ThreadInfo> done = new CompletableFuture<>();

            JCoroutineHandle<Void> h = scope.launch(c -> {
                done.complete(Probes.here());
            });

            Probes.ThreadInfo info = done.join();
            assertTrue(info.virtual(), "launch should run on VT");
            assertTrue(h.result().isDone(), "handle CF should complete");
        }
    }

    @Test
        // Cancellation: cancel() on the handle causes both result().join() and join() to throw CancellationException.
        // Race-avoidance: CompletableFuture 'started' ensures the task entered the body before cancel();
        // long delay keeps the task in-flight for observable cancellation.
    void launch_cancellation_viaHandle() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            CompletableFuture<Boolean> started = new CompletableFuture<>();

            JCoroutineHandle<Void> h = scope.launch(c -> {
                started.complete(true);
                c.delay(5_000);     // long (may not be preempted depending on impl)
                // (No assertion about reaching here)
            });

            assertTrue(started.join(), "task should have started");

            assertTrue(h.cancel(), "first cancel true");

            // CF view → CancellationException
            assertThrows(CancellationException.class, () -> h.result().join());

            // Handle join → CancellationException (unchecked on join())
            assertThrows(CancellationException.class, h::join);
        }
    }


    @Test
        // Structured concurrency: child launched within runBlocking should be cancelled when runBlocking exits without awaiting.
        // Race-avoidance: CountDownLatch 'childInDelay' signals the child has entered delay;
        // we assert h.isActive() inside runBlocking, then exit to trigger structured cancellation.
    void launch_structured_cancelOnRunBlockingExit_ifNotAwaited_ByLatch() throws InterruptedException {
        CountDownLatch childInDelay = new CountDownLatch(1);
        AtomicBoolean childWasActive = new AtomicBoolean(false);
        JCoroutineHandle<Void> child;

        try (JCoroutineScope scope = Coroutines.newScope()) {
            child = scope.runBlocking(c -> {
                JCoroutineHandle<Void> h = c.launch(cc -> {
                    childInDelay.countDown();
                    // Check if we're active before the delay (should be true)

                    cc.delay(10_000); // Should be cancelled
                    fail("Child should have been cancelled");
                });

                // Wait for child to signal it's about to delay
                try {
                    assertTrue(childInDelay.await(2, TimeUnit.SECONDS),
                            "Child never signaled it was about to delay");

                    // Check if child is active while still in runBlocking
                    childWasActive.set(h.isActive());

                    // Don't wait here - let runBlocking return immediately
                    // Structured concurrency should cancel child when runBlocking exits

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                return h;
            });

            // By the time we get here, child should already be cancelled
            // due to structured concurrency when runBlocking exited

        } // Scope closes here, but child should already be cancelled

        // Verify the child was cancelled
        assertThrows(CancellationException.class, () -> {
            assertNotNull(child);
            child.result().join();
        });

        assertTrue(child.isCompleted(), "Child should be completed");
        assertFalse(child.isActive(), "Child should not be active after cancellation");

        // This is the key assertion - child was active during runBlocking
        assertTrue(childWasActive.get(), "Child should have been active before runBlocking exited");
    }

    @Test
        // Structured waiting: runBlocking awaits two launched children via result().join() so both finish before return.
        // No race: explicit joins keep children alive across the blocking body.
    void launch_multiple_children_finish_before_runBlocking_returns_when_waited() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            AtomicInteger count = new AtomicInteger();

            scope.runBlocking(c -> {
                JCoroutineHandle<Void> a = scope.launch(cc -> {
                    cc.delay(50);
                    count.incrementAndGet();
                });
                JCoroutineHandle<Void> b = scope.launch(cc -> {
                    cc.delay(50);
                    count.incrementAndGet();
                });
                // Wait explicitly to keep them alive across the blocking body
                a.result().join();
                b.result().join();
                return null;
            });

            assertEquals(2, count.get(), "both children should have completed before return");
        }
    }

    @Test
        // Exception propagation: errors thrown in launch surface on both CF view (join) and handle.join().
        // We accept common wrapping shapes for CF join (CompletionException, direct IllegalStateException, or RuntimeException with cause).
    void launch_exception_surfacesOnHandleResultJoin() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            JCoroutineHandle<Void> h = scope.launch(c -> {
                throw new IllegalStateException("boom");
            });

            // CF view: accept any of the common shapes
            Throwable t = assertThrows(Throwable.class, () -> h.result().join());
            if (t instanceof CompletionException ce) {
                Throwable cause = java.util.Objects.requireNonNull(ce.getCause(), "cause");
                assertTrue(cause instanceof IllegalStateException);
                assertEquals("boom", cause.getMessage());
            } else if (t instanceof IllegalStateException ise) {
                assertEquals("boom", ise.getMessage());
            } else if (t instanceof RuntimeException re && re.getCause() instanceof IllegalStateException ise) {
                assertEquals("boom", ise.getMessage());
            } else {
                fail("Expected IllegalStateException (direct or wrapped), got: " + t);
            }

            // Handle join() should expose the original throwable type
            Exception e2 = assertThrows(Exception.class, h::join);
            assertTrue(e2 instanceof IllegalStateException);
            assertEquals("boom", e2.getMessage());
        }
    }

}
