/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/CoroutinesBasicsTest.java
 description: Basic coroutine API smoke tests: runBlockingCpu, async/launch handles, CF bridge, CPU variants, and cancellation/state.
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.*;

final class CoroutinesBasicsTest {

    @Test
    @Timeout(2)
        // runBlockingCpu returns the value from the suspend lambda.
        // No race: small delay inside lambda avoids pre-start assumptions.
    void runBlockingCpuReturnsValue() {
        String res = runBlockingCpu(s -> {
            s.delay(10);
            return "ok";
        });
        assertEquals("ok", res);
    }

    // Test the new handle-based API
    @Test
    @Timeout(2)
    // async returns a handle; join() yields the computed value and flips state to completed.
    // No race: short delay gives scheduler time to start; assertions check value and flags.
    void asyncProducesHandle() throws Exception {
        JCoroutineHandle<Integer> handle = async(s -> {
            s.delay(5);
            return 42;
        });
        assertEquals(42, handle.join());
        assertTrue(handle.isCompleted());
        assertFalse(handle.isActive());
    }

    @Test
    @Timeout(2)
        // launch returns a handle; body runs side-effect and join() completes successfully.
        // Race-avoidance: AtomicReference + join prove completion without relying on sleeps.
    void launchProducesHandle() {
        AtomicReference<String> ref = new AtomicReference<>();
        JCoroutineHandle<Void> handle = launch(s -> {
            s.delay(5);
            ref.set("done");
        });

        // Wait for completion
        assertDoesNotThrow(handle::join);
        assertEquals("done", ref.get());
        assertTrue(handle.isCompleted());
    }

    // Test the convenience methods for backward compatibility
    @Test
    @Timeout(2)
    // asyncAndForget bridges to CompletableFuture; join() yields the computed value.
    // No race: small delay ensures scheduling occurred before join.
    void asyncAndForgetProducesFuture() {
        CompletableFuture<Integer> f = asyncAndForget(s -> {
            s.delay(5);
            return 42;
        });
        assertEquals(42, f.join());
    }

    @Test
    @Timeout(2)
        // launchAndForget runs a side-effect only (no handle).
        // Race-avoidance: brief Thread.sleep ensures the launched task has time to run before asserting.
    void launchAndForgetRunsSideEffect() {
        AtomicReference<String> ref = new AtomicReference<>();
        launchAndForget(s -> {
            s.delay(5);
            ref.set("done");
        });
        // Give the launched task a brief moment
        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) {
        }
        assertEquals("done", ref.get());
    }

    // Test getting futures from handles
    @Test
    @Timeout(2)
    // handle.result() exposes a CompletableFuture view; both should report completion after join().
    // No race: short delay; assertion checks both handle and CF states.
    void handleToFutureConversion() {
        JCoroutineHandle<String> handle = async(s -> {
            s.delay(5);
            return "converted";
        });

        CompletableFuture<String> future = handle.result();
        assertEquals("converted", future.join());

        // Both should report as completed
        assertTrue(handle.isCompleted());
        assertTrue(future.isDone());
    }

    @Test
    @Timeout(2)
        // asyncCpu demonstrates CPU-bound work on the CPU dispatcher; join returns a positive sum.
        // No race: purely computational loop.
    void asyncCpuWorksToo() throws Exception {
        JCoroutineHandle<Long> handle = asyncCpu(s -> {
            long sum = 0;
            for (int i = 0; i < 200_000; i++) sum += i;
            return sum;
        });
        assertTrue(handle.join() > 0);
    }

    @Test
    @Timeout(2)
        // launchCpu runs CPU-bound work; completion is observed via handle.join().
        // No race: atomic flag updated before join returns.
    void launchCpuWorksToo() {
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        JCoroutineHandle<Void> handle = launchCpu(s -> {
            // Some CPU work
            long sum = 0;
            for (int i = 0; i < 100_000; i++) sum += i;
            completed.set(true);
        });

        assertDoesNotThrow(handle::join);
        assertTrue(completed.get());
    }

    // Test cancellation functionality
    @Test
    @Timeout(2)
    // Cancelling a handle should cancel the CF view (result()) as well.
    // Race-avoidance: long delay keeps task in-flight; cancel() called immediately; assertion checks CF isCancelled.
    void handleCancellation() {
        JCoroutineHandle<String> handle = async(s -> {
            s.delay(100); // Long enough to cancel
            return "should not complete";
        });

        // Cancel immediately
        assertTrue(handle.cancel());

        // Should be cancelled
        assertTrue(handle.result().isCancelled());
    }

    @Test
    @Timeout(2)
        // Handle lifecycle flags: isActive before, isCompleted/!isActive after join().
        // No race: short delay then join to observe transition.
    void handleStatus() throws Exception {
        JCoroutineHandle<Integer> handle = async(s -> {
            s.delay(10);
            return 123;
        });

        // Initially active
        assertTrue(handle.isActive());
        assertFalse(handle.isCompleted());

        // Wait for completion
        assertEquals(123, handle.join());

        // Now completed
        assertFalse(handle.isActive());
        assertTrue(handle.isCompleted());
    }
}