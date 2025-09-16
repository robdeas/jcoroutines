/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/DispatcherLaunchTest.java
 description: Dispatcher.launch tests: handle creation, cancellation, exception propagation, batch launches, mass-cancel, post-shutdown launch, status probing, memory-ish scenario, and factory smoke.
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

import org.junit.jupiter.api.*;
import tech.robd.jcoroutines.advanced.Dispatcher;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.CancellationAssertions;
import tech.robd.jcoroutines.tools.DebugUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatcherLaunchTest {

    private Dispatcher dispatcher;

    @BeforeEach
        // Change from @BeforeAll
    void setup() {
        dispatcher = Dispatcher.virtualThreads();
    }

    @AfterEach
        // Change from @AfterAll
    void cleanup() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
        // Happy-path: launch returns a handle; join() completes and flips state to completed/!active.
        // No race: short delay inside body to avoid pre-start assumptions.
    void launchReturnsHandle() {
        AtomicBoolean executed = new AtomicBoolean(false);

        JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
            ctx.delay(10);
            executed.set(true);
        });

        assertNotNull(handle, "Launch should return a handle");

        // Wait for completion
        assertDoesNotThrow(handle::join);

        assertTrue(executed.get(), "Task should have executed");
        assertTrue(handle.isCompleted(), "Handle should be completed");
        assertFalse(handle.isActive(), "Handle should not be active after completion");
    }

    @Test
        // Cancellation path: task loops with checkCancellation() + small sleep; cancel() should cause join() to throw CancellationException.
        // Race-avoidance: CountDownLatch 'taskInLoop' ensures body entered loop before cancel; second cancel() is idempotent.
    void launchCanBeCancelled() throws InterruptedException {
        CountDownLatch taskInLoop = new CountDownLatch(1);
        CountDownLatch taskCancelled = new CountDownLatch(1);
        AtomicReference<Exception> taskException = new AtomicReference<>();

        JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
            try {
                for (int i = 0; i < 5000; i++) {
                    if (i == 0) taskInLoop.countDown(); // Signal exactly once
                    ctx.checkCancellation();
                    Thread.sleep(1);
                }
                fail("Should have been cancelled");
            } catch (CancellationException | InterruptedException e) {
                taskException.set(e);
                taskCancelled.countDown();
                if (e instanceof InterruptedException) {
                    throw new CancellationException("Interrupted");
                }
                throw e;
            }
        });

        // Wait up to 5 seconds for task to enter loop
        assertTrue(taskInLoop.await(5, TimeUnit.SECONDS), "Task didn't start loop");

        assertTrue(handle.cancel());
        assertFalse(handle.cancel());

        // Wait for cancellation to propagate
        assertTrue(taskCancelled.await(5, TimeUnit.SECONDS), "Task wasn't cancelled");

        assertThrows(CancellationException.class, handle::join);
        assertTrue(handle.isCompleted());
        assertFalse(handle.isActive());
    }

    @Test
        // Exception propagation: RuntimeException thrown inside the body should surface via handle.join().
        // No race: small delay then throw; just assert message is present.
    void launchHandlesExceptions() {
        String errorMessage = "Test exception from dispatcher launch";

        JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
            ctx.delay(10);
            throw new RuntimeException(errorMessage);
        });

        Exception exception = assertThrows(Exception.class, handle::join);
        Objects.requireNonNull(exception.getMessage());
        assertTrue(exception.getMessage().contains(errorMessage));

        assertTrue(handle.isCompleted());
        assertFalse(handle.isActive());
    }


    @Test
        // Batch launches: N launched tasks complete; join each handle and verify completedCount == N.
        // No race: variable delays stagger completions; we join all handles before asserting.
    void multipleLaunchOperationsWork() throws Exception {
        int taskCount = 10;
        AtomicInteger completedCount = new AtomicInteger(0);
        List<JCoroutineHandle<Void>> handles = new ArrayList<>();

        // Launch multiple operations
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
                ctx.delay(50 + (taskId * 10)); // Variable delays
                completedCount.incrementAndGet();
            });
            handles.add(handle);
        }

        // Wait for all to complete
        for (JCoroutineHandle<Void> handle : handles) {
            assertDoesNotThrow(handle::join);
            assertTrue(handle.isCompleted());
        }

        assertEquals(taskCount, completedCount.get());
    }
    @Test
    @Timeout(3)
        // Mass cancel: N tasks parked in yieldAndPause should all observe CancellationException after canceling all handles.
        // Race-avoidance: CountDownLatch 'allStarted' ensures tasks are parked before cancellation; double-cancel checks idempotence.
    void cancelMultipleOperations() throws InterruptedException {
        int taskCount = 5;
        AtomicInteger cancelledCount = new AtomicInteger(0);
        List<JCoroutineHandle<Void>> handles = new ArrayList<>();
        CountDownLatch allStarted = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            JCoroutineHandle<Void> h = dispatcher.launch(ctx -> {
                allStarted.countDown();
                try {
                    // Park at a cancellation-aware point: only returns if cancelled
                    ctx.yieldAndPause(Duration.ofMinutes(1), true);
                    fail("Should have been cancelled");
                } catch (CancellationException ce) {
                    cancelledCount.incrementAndGet();
                    throw ce;
                }
            });
            handles.add(h);
        }

        assertTrue(allStarted.await(1, TimeUnit.SECONDS), "not all tasks started");

        // Cancel everyone (idempotent)
        handles.forEach(JCoroutineHandle::cancel);
        handles.forEach(JCoroutineHandle::cancel);

        // Joining each handle guarantees completion before we assert the counter
        for (JCoroutineHandle<Void> h : handles) {
            CancellationAssertions.expectCancelled(h::join);
        }

        assertEquals(taskCount, cancelledCount.get(), "all tasks should observe cancellation");
    }

    @Test
        // Post-shutdown behavior: launching after dispatcher.close() should return a handle that cancels/throws on join().
        // No race: immediate close before launch; then assert join throws CancellationException.
    void launchAfterDispatcherShutdown() {
        // Create a separate dispatcher for this test (close-and-recreate pattern)
        Dispatcher testDispatcher = Dispatcher.create(
                Executors.newFixedThreadPool(1),
                "shutdown-test"
        );

        // Shutdown the dispatcher
        testDispatcher.close();

        // Launch should handle rejection gracefully
        JCoroutineHandle<Void> handle = testDispatcher.launch(ctx -> {
            // This should not execute
        });

        assertNotNull(handle);
        assertThrows(CancellationException.class, handle::join);
        assertTrue(handle.isCompleted());
    }

    @Test
        // Status probing: while body waits on a flag, handle.isActive==true and isCompleted==false; after flag set, join completes and flags flip.
        // Race-avoidance: small sleep gives the launched task time to start before probing flags.
    void handleStatusDuringExecution() throws InterruptedException {
        AtomicBoolean canProceed = new AtomicBoolean(false);

        JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
            // Wait for test to check status
            while (!canProceed.get()) {
                ctx.delay(10);
            }
        });

        // Check status while running
        Thread.sleep(50); // Give it time to start
        assertTrue(handle.isActive(), "Handle should be active while running");
        assertFalse(handle.isCompleted(), "Handle should not be completed while running");

        // Allow completion
        canProceed.set(true);

        // Wait for completion
        assertDoesNotThrow(handle::join);
        assertFalse(handle.isActive(), "Handle should not be active after completion");
        assertTrue(handle.isCompleted(), "Handle should be completed");
    }

    @Test
        // Pseudo-stress/memory scenario: many tasks loop with checkCancellation() + sleep; cancel all and expect most not to 'complete normally'.
        // Race-avoidance: cancel all before joins; assert completedNormally << total.
    void memoryTestScenario() {
        // Simulate your memory testing use case
        int operationCount = 100;
        List<JCoroutineHandle<Void>> handles = new ArrayList<>();
        AtomicInteger completedNormally = new AtomicInteger(0);

        // Launch many operations that check cancellation explicitly
        for (int i = 0; i < operationCount; i++) {
            JCoroutineHandle<Void> handle = dispatcher.launch(ctx -> {
                try {
                    // Instead of relying on ctx.delay(), check cancellation in a loop
                    for (int j = 0; j < 1000; j++) {
                        ctx.checkCancellation(); // This should throw if cancelled
                        Thread.sleep(1); // Regular Java sleep, not ctx.delay()
                    }
                    completedNormally.incrementAndGet();
                } catch (CancellationException e) {
                    // Expected for cancelled operations
                    throw e;
                } catch (InterruptedException e) {
                    // Convert to cancellation
                    throw new CancellationException("Interrupted");
                }
            });
            handles.add(handle);
        }

        // Verify all handles are created
        assertEquals(operationCount, handles.size());

        // Cancel all operations quickly
        handles.forEach(JCoroutineHandle::cancel);

        // Verify all were cancelled (should not complete normally)
        for (JCoroutineHandle<Void> handle : handles) {
            assertThrows(CancellationException.class, handle::join);
            assertTrue(handle.isCompleted());
            assertFalse(handle.isActive());
        }

        // Most should have been cancelled before completing normally
        assertTrue(completedNormally.get() < operationCount / 2,
                "Too many operations completed normally: " + completedNormally.get());

        DebugUtils.print("Memory test scenario: Created and cancelled " +
                operationCount + " operations successfully (normal completions: " +
                completedNormally.get() + ")");
    }

    @Test
        // Factory smoke: all Dispatcher factories can launch a trivial task; join all and close dispatchers to avoid thread leaks.
        // Race-avoidance: join before close; finally block closes each dispatcher.
    void testFactoryMethods() {
        // Test various factory methods work
        Dispatcher vt = Dispatcher.virtualThreads();
        Dispatcher cpu = Dispatcher.cpu();
        Dispatcher fixed = Dispatcher.fixedThreadPool(2);
        Dispatcher cached = Dispatcher.cachedThreadPool();
        Dispatcher single = Dispatcher.singleThread();

        try {
            // Quick test that each can launch and execute
            AtomicInteger counter = new AtomicInteger(0);

            List<JCoroutineHandle<Void>> handles = List.of(
                    vt.launch(ctx -> counter.incrementAndGet()),
                    cpu.launch(ctx -> counter.incrementAndGet()),
                    fixed.launch(ctx -> counter.incrementAndGet()),
                    cached.launch(ctx -> counter.incrementAndGet()),
                    single.launch(ctx -> counter.incrementAndGet())
            );

            // Wait for all to complete
            handles.forEach(handle -> {
                assertDoesNotThrow(handle::join);
            });

            assertEquals(5, counter.get());

        } finally {
            // Manual cleanup
            vt.close();
            cpu.close();
            fixed.close();
            cached.close();
            single.close();
        }
    }
}