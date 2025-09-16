/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/CoroutinesLaunchTest.java
 description: Integration tests for Coroutines.launch: VT execution, cancellation, exception propagation, timeouts, and global scope behavior.
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
import org.junit.jupiter.api.Timeout;
import tech.robd.jcoroutines.Coroutines;
import tech.robd.jcoroutines.tools.TestAwaitUtils;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.Probes;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.launch;

public class CoroutinesLaunchTest {

    @Test
        // VT execution: launch runs on a virtual thread; handle completes when body returns.
        // No race: single-step body completes immediately after starting.
    void launch_runsOnVirtualThread_andCompletes() {
        CompletableFuture<Probes.ThreadInfo> started = new CompletableFuture<>();

        JCoroutineHandle<Void> h = launch(c -> {
            started.complete(Probes.here());  // prove VT
        });

        Probes.ThreadInfo info = started.join();
        assertTrue(info.virtual(), "Coroutines.launch should run on a virtual thread by default");
        assertTrue(h.result().isDone(), "handle CF should complete");
    }



    @Test
    @Timeout(3) // seconds - guarantees the test can’t hang
    void launch_cancellation_viaHandle() {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean(false);

        JCoroutineHandle<Void> h = launch(c -> {
            started.countDown();                          // signal start
            try {
                // Long enough that it will only finish via cancellation
                c.yieldAndPause(Duration.ofSeconds(30), true);
                fail("Body should not complete normally when test cancels");
            } catch (CancellationException ce) {
                sawCancel.set(true);                      // prove we observed cancellation
                throw ce;                                 // propagate as cancellation
            }
        });

        assertTrue(TestAwaitUtils.await(started, 500), "task never started");

        // Idempotent cancel: should not throw
        assertDoesNotThrow(h::cancel);
        assertDoesNotThrow(h::cancel);

        CompletableFuture<Void> cf = h.result();

        Throwable t1 = assertThrows(Throwable.class, cf::join);
        assertTrue(
                (t1 instanceof CancellationException) ||
                        (t1 instanceof CompletionException && t1.getCause() instanceof CancellationException),
                "CF join() should reflect cancellation"
        );

        Throwable t2 = assertThrows(Throwable.class, h::join);
        assertTrue(
                (t2 instanceof CompletionException && t2.getCause() instanceof CancellationException) ||
                        (t2 instanceof CancellationException), // allow future change to “pure” cancellation
                "Handle join() should reflect cancellation"
        );

        assertTrue(sawCancel.get(), "coroutine body should observe CancellationException");
    }



    @Test
        // Exception propagation: errors thrown inside the launch body surface on both CF view and handle.join().
        // Accepts common wrapping shapes for CF join().
    void launch_exception_surfacesOnHandleResultJoin_andOnHandleJoin() {
        JCoroutineHandle<Void> h = launch(c -> {
            throw new IllegalStateException("boom");
        });

        // CF view: depending on impl, accept common shapes
        Throwable t = assertThrows(Throwable.class, () -> h.result().join());
        switch (t) {
            case CompletionException ce -> {
                Throwable cause = Objects.requireNonNull(ce.getCause(), "cause");
                assertInstanceOf(IllegalStateException.class, cause);
                assertEquals("boom", cause.getMessage());
            }
            case IllegalStateException ise -> assertEquals("boom", ise.getMessage());
            case RuntimeException re when re.getCause() instanceof IllegalStateException ise ->
                    assertEquals("boom", ise.getMessage());
            case null, default -> fail("Expected IllegalStateException (direct or wrapped), got: " + t);
        }

        // Handle join(): original throwable type
        Exception e2 = assertThrows(Exception.class, h::join);
        assertInstanceOf(IllegalStateException.class, e2);
        assertEquals("boom", e2.getMessage());
    }

    @Test
        // Timeout path: CF.orTimeout should time out a slower launch.
        // No race: delay(300ms) >> timeout(100ms).
    void launch_orTimeout_timesOut() {
        JCoroutineHandle<Void> h = launch(c -> {
            c.delay(300);
        });
        var cf = h.result();

        Throwable thrown = assertThrows(Throwable.class, () ->
                cf.orTimeout(100, TimeUnit.MILLISECONDS).join()
        );

        switch (thrown) {
            case CompletionException ce -> assertInstanceOf(TimeoutException.class, ce.getCause());
            case java.util.concurrent.TimeoutException timeoutException -> {
                // direct timeout path ok
            }
            case RuntimeException re when re.getCause() instanceof java.util.concurrent.TimeoutException -> {
                // unwrapped path ok
            }
            case null, default -> fail("Expected timeout; got: " + thrown);
        }
    }

    @Test
        // Global scope: a launch started inside runBlockingVT is not a structured child of that block and remains active after exit.
        // Race-avoidance: latch + polling confirm the child is active before leaving runBlockingVT.
    void globalLaunch_startedInsideRunBlocking_isNotStructuredChild_ofThatBlock() throws Exception {
        AtomicBoolean childActive = new AtomicBoolean(false);
        CountDownLatch childReady = new CountDownLatch(1);

        // Launch on the GLOBAL scope from inside runBlockingVT (i.e., not a structured child)
        JCoroutineHandle<Void> child = Coroutines.runBlockingVT(c -> {
            JCoroutineHandle<Void> h = launch(cc -> {
                childReady.countDown();   // signal "scheduled and running block"
                cc.delay(5_000);          // stay alive long enough for assertions
            });

            // Wait until the child task is actually observed active (or time out)
            assertTrue(childReady.await(2, TimeUnit.SECONDS), "child never started");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!h.isActive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            childActive.set(h.isActive());

            return h; // return the GLOBAL child handle
        });

        // The child was active while runBlockingVT was executing…
        assertTrue(childActive.get(), "Child should have been active during runBlockingVT");

        // …and because it's on GLOBAL scope, it should still be active after runBlockingVT exits.
        assertTrue(child.isActive(), "Global child should remain active after runBlockingVT exits");
        assertFalse(child.result().isCancelled(), "Global child should not be auto-cancelled");

        // Clean up
        child.cancel();
        assertThrows(CancellationException.class, child.result()::join);
    }

}

