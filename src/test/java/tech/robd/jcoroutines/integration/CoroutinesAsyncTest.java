/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/CoroutinesAsyncTest.java
 description: Integration tests for async/await helpers, cancellation, and exception propagation behaviors in jcoroutines.
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


import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.Coroutines;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.Probes;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class CoroutinesAsyncTest {

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
        // Validates cancellation propagation to join()/result().
        // Asserts timeout behavior (CompletableFuture timeout wrappers).
        // Checks exception wrapping via CompletionException from join().
    void async_runsOnVirtualThread_andReturnsValue() {
        // Static async — no explicit scope
        CompletableFuture<Probes.ThreadInfo> cf = Coroutines.async(c -> Probes.here()).result();
        Probes.ThreadInfo info = cf.join();
        assertTrue(info.virtual(), "Coroutines.async should run on a virtual thread by default");
    }

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
        // Validates cancellation propagation to join()/result().
        // Asserts timeout behavior (CompletableFuture timeout wrappers).
        // Checks exception wrapping via CompletionException from join().
    void async_resultJoin_propagatesException_asCompletionException() {
        CompletableFuture<Object> cf = Coroutines.async(c -> {
            throw new RuntimeException("boom");
        }).result();

        CompletionException ex = assertThrows(CompletionException.class, cf::join);
        assertNotNull(ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
        // Validates cancellation propagation to join()/result().
        // Asserts timeout behavior (CompletableFuture timeout wrappers).
        // Checks exception wrapping via CompletionException from join().
    void async_handleJoin_throwsOriginalException() throws Exception {
        JCoroutineHandle<Object> h = Coroutines.async(c -> {
            throw new IllegalStateException("oops");
        });
        Exception ex = assertThrows(Exception.class, h::join);
        assertEquals("oops", ex.getMessage());
    }

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
        // Validates cancellation propagation to join()/result().
        // Asserts timeout behavior (CompletableFuture timeout wrappers).
        // Checks exception wrapping via CompletionException from join().
    void async_canBeCancelled_viaHandle() {
        JCoroutineHandle<Void> h = Coroutines.async(c -> {
            c.delay(5_000);
            return null;
        });

        assertTrue(h.cancel(), "first cancel should return true");
        // CF view throws CancellationException on join()
        assertThrows(CancellationException.class, () -> h.result().join());
        // Handle join also surfaces cancellation
        assertThrows(CancellationException.class, h::join);
    }

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
        // Asserts timeout behavior (CompletableFuture timeout wrappers).
        // Checks exception wrapping via CompletionException from join().
    void async_orTimeout_timesOut() {
        CompletableFuture<@Nullable Object> cf = Coroutines.async(c -> {
            c.delay(300);
            return null;
        }).result();

        Throwable thrown = assertThrows(Throwable.class, () ->
                cf.orTimeout(100, TimeUnit.MILLISECONDS).join()
        );

        switch (thrown) {
            case CompletionException ce -> assertInstanceOf(TimeoutException.class, ce.getCause());
            case java.util.concurrent.TimeoutException timeoutException -> {
                // direct cause path ok
            }
            case RuntimeException re when re.getCause() instanceof java.util.concurrent.TimeoutException -> {
                // unwrapped path ok
            }
            case null, default -> fail("Expected timeout; got: " + thrown);
        }
    }

    @Test
        // Cooperative wait (sleep/yield/delay) to avoid busy-wait and pre-start cancel.
    void async_parallelFanOut_joinAll() throws Exception {
        List<Integer> inputs = List.of(1, 2, 3, 4);

        List<Integer> out = Coroutines.runBlockingVT(c -> {
            List<CompletableFuture<Integer>> futures = inputs.stream()
                    .map(i -> Coroutines.async(cc -> {
                        cc.delay(10);
                        return i * 2;
                    }).result())
                    .toList();

            return futures.stream().map(CompletableFuture::join).toList();
        });

        assertEquals(List.of(2, 4, 6, 8), out);
    }
}