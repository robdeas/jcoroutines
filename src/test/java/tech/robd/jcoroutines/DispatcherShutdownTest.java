/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/DispatcherShutdownTest.java
 description: Dispatcher shutdown semantics: shutdownNow interrupt/cancel, kill() behavior, independence of multiple dispatchers, and factory dispatcher closure.
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
import tech.robd.jcoroutines.advanced.Dispatcher;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.TestAwaitUtils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class DispatcherShutdownTest {

    @Test
    @Timeout(3)
        // shutdownNow semantics: interrupt a blocked task; join should cancel/exception, not complete normally.
        // Race-avoidance: started latch ensures task entered await(); awaitDone(handle, 500) waits for propagation.
    void customDispatcherShutdownCancelsTasks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Dispatcher d = Dispatcher.create(pool, "X");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1); // never opened
        AtomicBoolean sawCancelPath = new AtomicBoolean(false);

        // Updated: async now returns JCoroutineHandle<T>
        JCoroutineHandle<String> handle = d.async(s -> {
            try {
                started.countDown();       // runnable entered
                gate.await();              // will be interrupted by shutdownNow()
                return "should-not-complete";
            } catch (CancellationException ce) {
                sawCancelPath.set(true);
                throw ce;
            } catch (InterruptedException ie) {
                sawCancelPath.set(true);
                throw new CancellationException("Interrupted by shutdown");
            }
        });

        // ensure the task actually started before we shut the pool down
        assertTrue(started.await(250, TimeUnit.MILLISECONDS), "task didn't start in time");

        // Force shutdown: interrupts running tasks and rejects new submissions
        pool.shutdownNow();

        // Wait (briefly) for completion to propagate
        TestAwaitUtils.awaitDone(handle, 500);

        // It must not complete normally
        try {
            handle.join();
            fail("Expected exceptional or cancelled completion after shutdownNow()");
        } catch (CancellationException e) {
            // OK: cancelled
        } catch (Exception e) {
            // OK: exceptional completion; validate the cause is shutdown-related
            assertTrue(
                    e instanceof InterruptedException || e.getCause() instanceof RejectedExecutionException,
                    "unexpected exception: " + e
            );
        }

        // Handle state should reflect completion
        assertTrue(handle.isCompleted(),
                "handle should be completed after shutdown");

        // We should have observed the cancellation path inside the task
        assertTrue(sawCancelPath.get(), "task body should have observed cancellation/interrupt");

        // Pool should terminate promptly
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS), "executor did not terminate");
    }

    @Test
    @Timeout(3)
        // kill(): closing dispatcher interrupts a blocked task; subsequent launches are rejected/cancelled.
        // Race-avoidance: spin-wait on taskStarted to ensure entry before kill(); assert join throws after kill().
    void dispatcherKillMethodWorks() throws Exception {
        Dispatcher dispatcher = Dispatcher.create(
                Executors.newFixedThreadPool(2),
                "KillTest"
        );

        AtomicBoolean taskStarted = new AtomicBoolean(false);
        CountDownLatch gate = new CountDownLatch(1);

        // Launch a task that will be interrupted by close
        JCoroutineHandle<Void> handle = dispatcher.launch(s -> {
            taskStarted.set(true);
            try {
                gate.await(); // Will be interrupted
            } catch (InterruptedException e) {
                throw new CancellationException("Interrupted by kill");
            }
        });

        // Wait for task to start
        while (!taskStarted.get()) {
            Thread.sleep(10);
        }

        // Close the dispatcher
        dispatcher.kill();

        // Task should be cancelled/completed

        assertTrue(handle.isCompleted());

        // New operations should be rejected
        JCoroutineHandle<Void> rejectedHandle = dispatcher.launch(s -> {
            // Should not execute
        });

        assertThrows(CancellationException.class, rejectedHandle::join);
        assertTrue(rejectedHandle.isCompleted());
    }

    @Test
    @Timeout(3)
        // Independence: closing d1 should not affect d2; d1 handle completes (cancelled), d2 task runs to completion.
        // Race-avoidance: awaitDone(h1, 200) verifies d1 propagation before asserting d2 completion.
    void multipleDispatchersIndependentShutdown() throws Exception {
        Dispatcher d1 = Dispatcher.create(Executors.newFixedThreadPool(1), "D1");
        Dispatcher d2 = Dispatcher.create(Executors.newFixedThreadPool(1), "D2");

        AtomicBoolean d1TaskCompleted = new AtomicBoolean(false);
        AtomicBoolean d2TaskCompleted = new AtomicBoolean(false);

        // Launch tasks on both dispatchers
        JCoroutineHandle<Void> h1 = d1.launch(s -> {
            s.delay(50);
            d1TaskCompleted.set(true);
        });

        JCoroutineHandle<Void> h2 = d2.launch(s -> {
            s.delay(50);
            d2TaskCompleted.set(true);
        });

        // Close only d1
        d1.close();

        // d1 task should be interrupted/cancelled
        TestAwaitUtils.awaitDone(h1, 200);
        assertTrue(h1.isCompleted());

        // d2 should continue working
        h2.join();
        assertTrue(d2TaskCompleted.get());

        // Clean up d2
        d2.close();
    }

    @Test
    @Timeout(3)
        // Factory instances: virtualThreads()/cpu() dispatchers can run and be closed cleanly; tasks join successfully.
        // Race-avoidance: joins before finally-close avoid thread leaks and flakiness.
    void factoryDispatchersCanBeShutdown() throws Exception {
        // Test that factory-created dispatchers can be properly shut down
        Dispatcher vt = Dispatcher.virtualThreads();
        Dispatcher cpu = Dispatcher.cpu();

        try {
            AtomicBoolean vtCompleted = new AtomicBoolean(false);
            AtomicBoolean cpuCompleted = new AtomicBoolean(false);

            JCoroutineHandle<Void> vtHandle = vt.launch(s -> {
                s.delay(10);
                vtCompleted.set(true);
            });

            JCoroutineHandle<Void> cpuHandle = cpu.launch(s -> {
                Thread.sleep(10); // CPU work
                cpuCompleted.set(true);
            });

            // Let them complete
            vtHandle.join();
            cpuHandle.join();

            assertTrue(vtCompleted.get());
            assertTrue(cpuCompleted.get());

        } finally {
            vt.close();
            cpu.close();
        }
    }
}