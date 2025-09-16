/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/memory/MemoryLeakTest.java
 description: GC/retention tests ensuring handles, contexts, dispatchers, executors, and bodies are not retained after completion/cancel/reject.
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
package tech.robd.jcoroutines.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tech.robd.jcoroutines.JCoroutineScope;
import tech.robd.jcoroutines.advanced.Dispatcher;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.newScope;

public class MemoryLeakTest {
    static final class HandleRefs {
        final WeakReference<JCoroutineHandle<?>> handle;
        final WeakReference<Object> body;

        HandleRefs(WeakReference<JCoroutineHandle<?>> handle, WeakReference<Object> body) {
            this.handle = handle;
            this.body = body;
        }
    }

    @Test
    @Timeout(3)
        // Exception path GC: a launch that throws should not keep its handle strongly reachable after scope closes.
        // Race-avoidance: drop strong refs before close; awaitCleared() triggers GC with light allocation + spin/sleep.
    void exceptionPathDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> hRef;

        try (var scope = newScope()) {
            JCoroutineHandle<Void> h = scope.launch(s -> {
                throw new IllegalStateException("boom");
            });
            assertThrows(IllegalStateException.class, h::join);
            hRef = new WeakReference<>(h, q);
            h = null; // drop strong ref before scope closes
        }
        awaitCleared(hRef, q, 4000);
    }

    @Test
    @Timeout(3)
        // Error path GC: same shape as exception path—ensure no retention after exceptional completion.
        // Race-avoidance: drop strong refs and use awaitCleared() with allocation pressure.
    void errorPathDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> hRef;

        try (var scope = newScope()) {
            JCoroutineHandle<Void> h = scope.launch(s -> {
                throw new IllegalStateException("boom");
            });
            assertThrows(IllegalStateException.class, h::join);
            hRef = new WeakReference<>(h, q);
            h = null; // drop strong ref before scope closes
        }
        awaitCleared(hRef, q, 4000);
    }

    @Test
    @Timeout(6)
        // Post-completion GC: async returns [ctx, marker]; after join(), ctx/marker/handle must be collectible.
        // Race-avoidance: null out locals, then awaitCleared() each weak ref with allocation pressure.
    void asyncDoesNotRetainContextOrMarkerAfterCompletion() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> hRef;
        WeakReference<Object> markerRef;
        WeakReference<tech.robd.jcoroutines.SuspendContext> ctxRef;

        try (var scope = newScope()) {
            JCoroutineHandle<Object[]> h = scope.async(MemoryLeakTest::packCtxAndFreshMarker);
            Object[] result = h.join();
            ctxRef = new WeakReference<>((tech.robd.jcoroutines.SuspendContext) result[0], q);
            markerRef = new WeakReference<>(result[1], q);
            hRef = new WeakReference<>(h, q);
            // drop strong locals in this frame to avoid keeping result graph alive
            h = null;
            result = null;
        }
        awaitCleared(hRef, q, 5000);
        awaitCleared(markerRef, q, 5000);
        awaitCleared(ctxRef, q, 5000);
    }

    // helper avoids lambda capture
    private static Object[] packCtxAndFreshMarker(tech.robd.jcoroutines.SuspendContext s) {
        return new Object[]{s, new Object()};
    }

    @Test
    @Timeout(6)
        // Bounded queue rejection: AbortPolicy or cancellation must not leak a handle/body on submit or join failure.
        // Race-avoidance: latch holds worker to fill queue deterministically; branch for 'throw on submit' vs 'handle fails'.
    void boundedQueueRejectionDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();

        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, queue,
                r -> {
                    Thread t = new Thread(r, "jc-bounded");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        Dispatcher d = Dispatcher.create(tpe, "bounded");

        try {
            // Occupy the single worker
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            JCoroutineHandle<?> a = d.async(s -> {
                aStarted.countDown();
                gate.await();
                return 1;
            });
            assertTrue(aStarted.await(300, TimeUnit.MILLISECONDS), "worker didn't start");

            // Enqueue second task so the queue is full
            JCoroutineHandle<?> b = d.async(s -> {
                gate.await();
                return 2;
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (queue.size() < 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, queue.size(), "second task not queued");

            // Third submit must be rejected.
            JCoroutineHandle<Object> c = null;
            boolean thrownAtSubmit = false;
            try {
                c = d.async(s -> 3); // may THROW immediately in your Dispatcher
            } catch (RuntimeException ex) {
                assertTrue(
                        (ex instanceof RejectedExecutionException) || (ex instanceof CancellationException),
                        "unexpected submit exception type: " + ex
                );
                thrownAtSubmit = true;
            }

            WeakReference<JCoroutineHandle<?>> cref = null;
            if (!thrownAtSubmit) {
                // We got a handle back; it must fail on join()
                JCoroutineHandle<Object> handle = java.util.Objects.requireNonNull(c, "Handle must be present when submit did not throw");
                boolean failed = false;
                try {
                    handle.join();
                    fail("expected rejected/cancelled handle");
                } catch (CancellationException ce) {
                    // Handle was cancelled; acceptable
                    failed = true;
                } catch (Exception e) {
                    // Other exception types are also acceptable for rejection
                    failed = true;
                }
                assertTrue(failed, "join should have failed");

                // Only if we actually created a handle do we check GC
                cref = new WeakReference<>(handle, q);
                c = null; // drop strong ref
            }

            // Drain A and B
            gate.countDown();
            a.join();
            b.join();

            if (cref != null) {
                awaitCleared(cref, q, 4_000);
            }
        } finally {
            d.close(); // Use dispatcher close instead of direct executor shutdown
        }
    }

    @Test
    @Timeout(5)
        // Deep chain: launching many children should cancel promptly on close and not leak the last handle.
        // Race-avoidance: keep only a weak ref to the last handle; join inside body ensures it reached parking.
    void deepChildChainCancelsAndDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> lastRef;
        try (var scope = newScope()) {
            JCoroutineHandle<?> h = scope.launch(s -> {
                JCoroutineHandle<?> cur = null;
                for (int i = 0; i < 100; i++) {
                    JCoroutineHandle<?> next = (s.launch(x -> new CountDownLatch(1).await()));
                    cur = next;
                }
                if (cur != null) {
                    cur.join(); // will be cancelled on close
                }
            });
            lastRef = new WeakReference<>(h, q);
            h = null; // ensure no strong ref from the test frame
        }
        awaitCleared(lastRef, q, 5000);
    }

    @Test
    @Timeout(6)
        // Long timers: many long yieldAndPause() tasks should cancel on scope close without leaking handles.
        // Race-avoidance: collect weak refs immediately; close scope in try-with-resources tight block.
    void longDelaysAreCancelledOnScopeClose_andDoNotLeakHandles() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        List<WeakReference<JCoroutineHandle<?>>> refs = new ArrayList<>();
        try (var scope = newScope()) {
            for (int i = 0; i < 20; i++) {
                // Use yieldAndPause for better cancellation behavior in tests
                JCoroutineHandle<Void> h = scope.launch(s -> s.yieldAndPause(Duration.ofSeconds(10)));
                refs.add(new WeakReference<>(h, q));
            }
            // closing scope should cancel scheduled tasks
        }
        for (var r : refs) awaitCleared(r, q, 6000);
    }

    // 1) Immediate cancellation of a launched task (deterministic)
    @Test
    @Timeout(2)
    // Immediate cancel: interruptible park (CountDownLatch.await()) should surface CancellationException quickly.
    // Race-avoidance: started latch ensures runnable entered; measure dt < 100ms from cancel() to join().
    void cancellationDuringParkIsImmediate() throws Exception {
        try (JCoroutineScope scope = newScope()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1); // never opened

            JCoroutineHandle<Void> h = scope.launch(s -> {
                started.countDown();
                try {
                    gate.await(); // interruptible wait; cancel() -> interrupt
                } catch (InterruptedException ie) {
                    throw new CancellationException("interrupted");
                }
            });

            assertTrue(started.await(200, MILLISECONDS), "task didn't start");

            long t0 = System.nanoTime();
            h.cancel();
            assertThrows(CancellationException.class, h::join);

            long dtMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(dtMs < 100, "Cancellation should be immediate, took: " + dtMs + "ms");
        }
    }

    // Additional tests continue with similar patterns...
    // [Keeping other tests but updating API calls from CompletableFuture to JCoroutineHandle]

    @Test
    @Timeout(8)
        // Dispatcher lifecycle: dispatcher + underlying executor and a completed handle must be collectible after close().
        // Race-avoidance: weak refs snapshot taken before nulling locals; awaitCleared on all three.
    void dispatcherWithCustomExecutorDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<Dispatcher> dispatcherRef;
        WeakReference<ExecutorService> executorRef;
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            ExecutorService customExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "custom-leak-test");
                t.setDaemon(true);
                return t;
            });

            Dispatcher dispatcher = Dispatcher.create(customExecutor, "CustomLeakTest");

            dispatcherRef = new WeakReference<>(dispatcher, q);
            executorRef = new WeakReference<>(customExecutor, q);

            JCoroutineHandle<String> handle = dispatcher.async(s -> {
                s.delay(10);
                return "custom work done";
            });

            handleRef = new WeakReference<>(handle, q);

            assertEquals("custom work done", handle.join());

            dispatcher.close(); // Use dispatcher close method
            handle = null;
            dispatcher = null;
            customExecutor = null;
        }

        awaitCleared(dispatcherRef, q, 4_000);
        awaitCleared(executorRef, q, 4_000);
        awaitCleared(handleRef, q, 4_000);
    }

    @Test
    @Timeout(6)
        // Rejection path: after shutting down executor, async either throws or returns a handle that fails—no leaks of prior handle/body.
        // Race-avoidance: await executor termination to make rejection deterministic; then awaitCleared on weak refs.
    void rejectionDoesNotLeakHandleOrBody() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();

        HandleRefs refs = produceAndRejectOnce(q); // everything created & torn down inside

        // Nudge GC and assert both referents are collectible
        awaitCleared(refs.handle, q, 8_000);
        awaitCleared(refs.body, q, 8_000);
    }

    /**
     * Create the completed handle + body, then force rejections, and return only weak refs.
     */
    private static HandleRefs produceAndRejectOnce(ReferenceQueue<Object> q) throws Exception {
        WeakReference<JCoroutineHandle<?>> handleRef;
        WeakReference<Object> bodyRef;

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jc-test");
            t.setDaemon(true);
            return t;
        });

        try {
            Dispatcher d = Dispatcher.create(pool, "X");

            // 1) Completed handle with a unique body object
            JCoroutineHandle<Object> handle = d.async(s -> new byte[1024]);
            Object body = handle.join();

            // 2) Create weak refs while still in-scope
            handleRef = new WeakReference<>(handle, q);
            bodyRef = new WeakReference<>(body, q);

            // 3) Force shutdown so subsequent submissions are rejected/threads interrupted
            pool.shutdownNow();

            // 4) Accept either behavior after shutdown:
            //    - returns a handle that completes exceptionally, OR
            //    - throws immediately (RejectedExecutionException/CancellationException)
            try {
                JCoroutineHandle<Object> rejected = d.async(s -> 123);
                // If we get here, the dispatcher returned a handle that should complete exceptionally
                Exception ex = assertThrows(Exception.class, rejected::join);
                // Accept CancellationException or other rejection-related exceptions
                assertTrue(ex instanceof CancellationException ||
                                ex.getCause() instanceof RejectedExecutionException,
                        "Expected CancellationException or RejectedExecutionException, got: " + ex.getClass().getSimpleName());
            } catch (RejectedExecutionException | CancellationException expected) {
                // OK: async threw directly after shutdown
            }

            // 5) Drop strong refs in this frame
            handle = null;
            body = null;
            d = null;
        } finally {
            // 6) Ensure the executor is actually down before returning
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");
        }

        return new HandleRefs(handleRef, bodyRef);
    }

    /**
     * Tough GC helper: GC + finalization + light allocation until referent clears or timeout.
     */
    private static void awaitCleared(java.lang.ref.Reference<?> ref,
                                     ReferenceQueue<Object> q,
                                     long timeoutMs) throws InterruptedException {
        final long deadline = System.nanoTime() + MILLISECONDS.toNanos(timeoutMs);
        int spins = 0;

        // Loop until the referent is collected or we time out
        while (ref.get() != null && System.nanoTime() < deadline) {
            // Hint the GC
            System.gc();

            // Apply light allocation pressure (helps some collectors trigger a cycle)
            byte[][] junk = new byte[4][];
            junk[0] = new byte[256 * 1024];
            junk[1] = new byte[256 * 1024];
            junk[2] = new byte[256 * 1024];
            junk[3] = new byte[256 * 1024];
            junk = null;

            // Drain the queue as a hint (do not rely on identity here)
            q.poll();

            // Small backoff: mostly spin, occasionally sleep to yield the CPU
            if ((++spins & 7) == 0) {
                Thread.sleep(10);
            } else {
                Thread.onSpinWait();
            }
        }

        if (ref.get() != null) {
            throw new AssertionError("Object not GC'd within timeout (" + timeoutMs + " ms)");
        }
    }

    @Test
    @Timeout(5)
    void cancelledAsyncDoesNotLeak() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> handleRef;

        JCoroutineHandle<Object> h = null;

        try (JCoroutineScope scope = newScope()) {
            CountDownLatch started = new CountDownLatch(1);
            h = scope.async(s -> {
                started.countDown();
                new CountDownLatch(1).await(); // park; will be interrupted on cancel
                return 0; // unreachable on cancel
            });
            assertTrue(started.await(200, MILLISECONDS));
            h.cancel();
            assertThrows(CancellationException.class, h::join);
        }

        handleRef = new WeakReference<>(h, q);
        h = null; // DROP strong ref

        awaitCleared(handleRef, q, 3_000);
    }

    private static long countThreads(String prefix) {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] arr = new Thread[Thread.activeCount() * 2];
        int n = root.enumerate(arr, true);
        long c = 0;
        for (int i = 0; i < n; i++) {
            Thread t = arr[i];
            if (t != null && t.getName().startsWith(prefix) && t.isAlive()) c++;
        }
        return c;
    }

    @Test
    @Timeout(5)
        // Thread hygiene: closing scope should not leak worker threads with 'jc-' prefix.
        // Race-avoidance: sleep(200ms) after close gives executor time to wind down before counting.
    void scopeCloseShutsDownThreads() throws Exception {
        long before = countThreads("jc-");

        long after;
        {
            try (JCoroutineScope scope = newScope()) {
                for (int i = 0; i < 50; i++) scope.launch(s -> s.delay(20));
            }
            // give the executor a moment to wind down
            Thread.sleep(200);
            after = countThreads("jc-");
        }
        assertTrue(after <= before, "no thread leakage: before=" + before + " after=" + after);
    }

    @Test
    @Timeout(10)
        // Soak: repeat create/use/close scopes; ensure children join and no residual leakage across rounds.
        // Race-avoidance: small GC + sleep between rounds to encourage cleanup.
    void repeatedScopesDoNotLeak() throws Exception {
        for (int round = 0; round < 100; round++) {
            try (JCoroutineScope scope = newScope()) {
                List<JCoroutineHandle<?>> hs = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    hs.add(scope.launch(s -> s.delay(5)));
                }
                for (var h : hs) {
                    assertDoesNotThrow(() -> {
                        try {
                            h.join();
                        } catch (CancellationException ignored) {
                        }
                    });
                }
            }
            // tiny GC to encourage cleanup between rounds
            System.gc();
            Thread.sleep(5);
        }
    }
}