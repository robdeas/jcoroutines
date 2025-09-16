/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/YieldBasicsTest.java
 description: Yield behavior tests: interleaving, cancellation responsiveness, VT continuity, and many-task fairness.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class YieldBasicsTest {

    @Test
        // Interleaving: two siblings yield each iteration to encourage scheduler handoff;
        // result().join() on both ensures completion before asserting some A/B switching.
    void yield_interleaves_sibling_coroutines() throws Exception {
        // Thread-safe log
        var log = new java.util.concurrent.CopyOnWriteArrayList<String>();

        var result = Coroutines.runBlockingVT(c -> {
            JCoroutineHandle<Void> a = c.launch(cc -> {
                for (int i = 0; i < 100; i++) {
                    log.add("A" + i);
                    cc.yield(); // cooperative point
                }
            });
            JCoroutineHandle<Void> b = c.launch(cc -> {
                for (int i = 0; i < 100; i++) {
                    log.add("B" + i);
                    cc.yield();
                }
            });
            a.result().join();
            b.result().join();

            // return an immutable snapshot so we can iterate without extra sync
            return java.util.List.copyOf(log);
        });

        assertEquals(200, result.size(), "both producers should finish");

        boolean sawSwitch = false;
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i).charAt(0) != result.get(i - 1).charAt(0)) {
                sawSwitch = true;
                break;
            }
        }
        assertTrue(sawSwitch, "expected some interleaving between A and B with yield()");
    }
    @Test
        // Cancellation responsiveness: tight loop calls yield() so cancel() is observed promptly;
        // race-avoidance: spin until iterations > 0 to ensure the loop started before cancel;
        // assert both CF join() and handle.join() throw CancellationException.
    void yield_makes_cancellation_responsive() {
        AtomicInteger iterations = new AtomicInteger();

        try (JCoroutineScope scope = Coroutines.newScope()) {
            JCoroutineHandle<Void> h = scope.launch(c -> {
                while (true) {
                    iterations.incrementAndGet();
                    c.yield(); // cooperative check point
                }
            });

            // Ensure it started
            while (iterations.get() == 0) { /* spin briefly */ }

            assertTrue(h.cancel(), "first cancel should succeed");

            // CF view: join throws CancellationException
            assertThrows(CancellationException.class, () -> h.result().join());
            // Handle join: also throws (unchecked) CancellationException
            assertThrows(CancellationException.class, h::join);

            assertTrue(iterations.get() > 0, "loop should have executed at least once before cancellation");
        }
    }
    @Test
        // VT continuity: after a yield(), still running on a Virtual Thread.
        // No race: single-step check of Probes before/after.
    void yield_preserves_execution_health() throws Exception {
        Probes.ThreadInfo beforeAfter = Coroutines.runBlockingVT(c -> {
            Probes.ThreadInfo before = Probes.here();
            c.yield();
            // Return the after yield info to assert it's still a virtual thread
            return Probes.here();
        });

        assertTrue(beforeAfter.virtual(), "after-yield should still be running on a virtual thread");
    }
    @Test
        // Fairness smoke: spawn many async tasks that occasionally yield; join all inside runBlockingVT to keep structure;
        // assert all results present without relying on scheduling order.
    void yield_many_tasks_all_complete() throws Exception {
        int N = 64;

        // Join inside the same runBlocking so children aren't cancelled on exit
        List<Integer> results = Coroutines.runBlockingVT(c -> {
            var futures = new ArrayList<CompletableFuture<Integer>>(N);
            for (int i = 0; i < N; i++) {
                final int idx = i;
                CompletableFuture<Integer> cf = c.async(cc -> {
                    int sum = 0;
                    for (int k = 0; k < 200; k++) {
                        sum += (idx + k);
                        if ((k & 7) == 0) cc.yield(); // cooperative checkpoint
                    }
                    return sum;
                }).result();
                futures.add(cf);
            }
            // Await ALL before returning (keeps structure)
            return futures.stream().map(CompletableFuture::join).toList();
        });

        assertEquals(N, results.size());
        assertTrue(results.stream().allMatch(java.util.Objects::nonNull));
    }

}
