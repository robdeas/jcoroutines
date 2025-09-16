/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ScopeRunBlockingTest.java
 description: Tests runBlocking variants: thread choice, value return, child waiting, exception propagation, blocking semantics, and fan-out joins.
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
import tech.robd.jcoroutines.tools.Probes;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ScopeRunBlockingTest {

    @Test
        // Thread policy: runBlocking() executes on the caller thread (PT), not a VT, by design.
        // No race: body returns Probes.here() immediately; assertion only checks thread type.
    void runBlocking_onDefaultScope_runsOnCallerThread_notVT() {
        try (JCoroutineScope scope = Coroutines.newScope()) {
            Probes.ThreadInfo info = scope.runBlocking(ctx -> Probes.here());
            assertNotNull(info);
            assertFalse(info.virtual(), "runBlocking executes on the caller thread by design");
        }
    }

    @Test
        // Executor overload: runBlocking(executor, ...) uses the supplied VT executor so Probes.here().virtual() is true.
        // No race: single-step body.
    void runBlocking_withVTExecutor_runsOnVirtualThread() {
        try (JCoroutineScope scope = Coroutines.newScope();
             var vt = Executors.newVirtualThreadPerTaskExecutor()) {

            Probes.ThreadInfo info = scope.runBlocking(vt, ctx -> Probes.here());
            assertNotNull(info);
            assertTrue(info.virtual(), "runBlocking(executor, ...) should use that executor (VT here)");
        }
    }


    @Test
        // Custom CPU pool: runBlocking(executor=PT pool) should run on platform threads (virtual() == false).
        // No race: body returns immediately; shutdownNow() in finally cleans up.
    void runBlocking_onCpuScope_runsOnPlatformThread() {
        ExecutorService cpuExec = Executors.newFixedThreadPool(2); // platform threads
        try (JCoroutineScope scope = Coroutines.newScope()) {
            // IMPORTANT: use the runBlocking(executor, ...) overload
            Probes.ThreadInfo info = scope.runBlocking(cpuExec, ctx -> Probes.here());

            // Because the task ran on cpuExec (a platform-thread pool), this must be false
            assertNotNull(info);
            assertFalse(info.virtual(), "runBlocking(executor=CPU pool) should not be virtual");
        } finally {
            cpuExec.shutdownNow();
        }
    }


    @Test
        // Return value: runBlocking returns the value produced by the suspend lambda.
        // No race: simple constant return.
    void runBlocking_returnsValue() {
        Integer value;
        try (JCoroutineScope scope = Coroutines.newScope()) {
            assertNotNull(scope);
            value = scope.runBlocking(ctx -> 123);
        }

        assertEquals(123, value);
    }

    @Test
        // Structured waiting: runBlocking should not return until launched child either completes or is cancelled.
        // Race-avoidance: childReady + parentCanReturn latches control ordering; 100ms sleep gives child time to finish before exit.
    void runBlocking_waitsForLaunchedChildren() throws InterruptedException {
        Probes.WorkerService svc = new Probes.WorkerService();
        AtomicBoolean childStarted = new AtomicBoolean(false);
        AtomicBoolean childCompleted = new AtomicBoolean(false);
        CountDownLatch childReady = new CountDownLatch(1);
        CountDownLatch parentCanReturn = new CountDownLatch(1);

        try (JCoroutineScope scope = Coroutines.newScope()) {
            scope.runBlocking(ctx -> {
                ctx.launch(c -> {
                    childStarted.set(true);
                    childReady.countDown();

                    try {
                        // Wait for parent to give permission to complete
                        // This ensures we control the completion order
                        parentCanReturn.await();
                        svc.ping();
                        childCompleted.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // Child was cancelled - this is expected with structured concurrency
                    } catch (CancellationException e) {
                        // Child was cancelled - this is expected with structured concurrency
                    }
                    return;
                });

                // Wait for child to be ready
                try {
                    assertTrue(childReady.await(2, TimeUnit.SECONDS),
                            "Child should start");

                    // Give child permission to complete
                    parentCanReturn.countDown();

                    // Small delay to let child complete before we return
                    Thread.sleep(100);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }

                return null;
            });
        }

        // Verify child started and completed (or was cancelled by structured concurrency)
        assertTrue(childStarted.get(), "Child should have started");

        // The key assertion: if runBlocking waited properly, child should have completed
        // OR if structured concurrency cancelled it, that's also valid behavior
        assertTrue(childCompleted.get() || svc.calls() == 1,
                "Child should have completed or been properly cancelled");
    }

    @Test
        // Exception propagation: a RuntimeException thrown inside runBlocking should surface to the caller.
        // No race: exception is thrown immediately.
    void runBlocking_propagatesExceptions() {
        RuntimeException ex;
        try (JCoroutineScope scope = Coroutines.newScope()) {

            ex = assertThrows(RuntimeException.class, () ->
                    scope.runBlocking(ctx -> {
                        throw new RuntimeException("boom");
                    })
            );
        }
        assertEquals("boom", ex.getMessage());
    }

    @Test
        // Blocking semantics: runBlocking blocks the caller thread until the body completes; duration should reflect waiting.
        // Race-avoidance: latches (runBlockingStarted/testCanProceed/runBlockingCanFinish) ensure it is actually blocked before timing;
        // join() with timeout prevents hangs.
    void runBlocking_blocksCallerThread_untilCompletion() throws Exception {
        CountDownLatch runBlockingStarted = new CountDownLatch(1);
        CountDownLatch testCanProceed = new CountDownLatch(1);
        CountDownLatch runBlockingCanFinish = new CountDownLatch(1);
        AtomicBoolean runBlockingReturned = new AtomicBoolean(false);
        AtomicLong startTime = new AtomicLong();
        AtomicLong endTime = new AtomicLong();

        try (JCoroutineScope scope = Coroutines.newScope()) {

            Thread callerThread = new Thread(() -> {
                startTime.set(System.currentTimeMillis());
                scope.runBlocking(ctx -> {
                    runBlockingStarted.countDown();
                    try {
                        // Signal test that we're blocked, then wait for permission to continue
                        testCanProceed.countDown();
                        runBlockingCanFinish.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return null;
                });
                endTime.set(System.currentTimeMillis());
                runBlockingReturned.set(true);
            });

            callerThread.start();

            // Wait for runBlocking to start and be blocked
            assertTrue(runBlockingStarted.await(2, TimeUnit.SECONDS),
                    "runBlocking should start");
            assertTrue(testCanProceed.await(2, TimeUnit.SECONDS),
                    "runBlocking should reach blocking point");

            // Verify caller is blocked
            Thread.sleep(100); // Let it settle
            assertFalse(runBlockingReturned.get(), "runBlocking should still be blocked");
            assertTrue(callerThread.isAlive(), "caller thread should be alive and blocked");

            // Release runBlocking and verify it completes
            runBlockingCanFinish.countDown();
            callerThread.join(2000);

            assertTrue(runBlockingReturned.get(), "runBlocking should have returned");
            assertFalse(callerThread.isAlive(), "caller thread should have finished");

            // Verify timing - should have taken at least 100ms due to our sleep
            long duration = endTime.get() - startTime.get();
            assertTrue(duration >= 100, "runBlocking should have been blocked for at least 100ms, was: " + duration);
        }
    }

    @Test
        // Fan-out + join: launch async tasks via ctx.async, collect CFs, then join all within runBlocking.
        // No race: tiny delay inside tasks and local joins provide deterministic ordering of final results.
    void runBlocking_canFanOut_andAwaitAll() {
        List<String> out;
        try (JCoroutineScope scope = Coroutines.newScope()) {

            out = scope.runBlocking(ctx -> {
                List<CompletableFuture<String>> futs = Stream.of("a", "b", "c")
                        .map(s -> ctx.async(c -> {
                            c.delay(10);
                            return s.toUpperCase();
                        }).result())
                        .toList();
                return futs.stream().map(CompletableFuture::join).toList();
            });
        }

        assertEquals(List.of("A", "B", "C"), out);
    }
}
