/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ChannelsYieldTest.java
 description: Integration tests for yielding producer/consumer patterns, rendezvous interleaving, and cancellation responsiveness.
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
import tech.robd.jcoroutines.InteropChannel;
import tech.robd.jcoroutines.JCoroutineScope;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

public class ChannelsYieldTest {


    @Test
        // Producer backpressure: trySend spins with yield() while buffer is full; consumer polls via tryReceive + yield().
        // Avoids busy-wait with cooperative yield; producer close + prod.join() ensures no stray tasks; asserts ordered delivery.
    void buffered_producer_usesYield_whenFull_trySendSpin() throws Exception {
        final int N = 200;
        InteropChannel<Integer> ch = InteropChannel.buffered(1);

        List<Integer> got = Coroutines.runBlockingVT(c -> {
            // Producer: trySend until success; yield while buffer is full; then close.
            JCoroutineHandle<Void> prod = c.launch(cc -> {
                for (int i = 0; i < N; i++) {
                    while (!ch.trySend(i)) {
                        cc.yield(); // cooperate under backpressure
                    }
                }
                ch.close();
            });

            // Consumer: non-blocking poll + yield until we’ve collected N items
            List<Integer> out = new ArrayList<>(N);
            while (out.size() < N) {
                var opt = ch.tryReceive();
                if (opt.isPresent()) {
                    out.add(opt.get());
                } else {
                    c.yield(); // no item yet — cooperate
                }
            }

            // Make sure producer finished (and no stray tasks remain)
            prod.result().join();
            return out;
        });

        assertEquals(N, got.size());
        for (int i = 0; i < N; i++) assertEquals(i, got.get(i));
    }


    @Test
        // Consumer emptiness: tryReceive spins with yield() until items appear; producer blocks on send and occasionally yields.
        // Avoids busy-wait; prod.join() ensures producer completion before assertion; asserts ordered delivery.
    void buffered_consumer_usesYield_whenEmpty_tryReceiveSpin() throws Exception {
        final int N = 100;
        InteropChannel<Integer> ch = InteropChannel.buffered(2);

        List<Integer> received = Coroutines.runBlockingVT(c -> {
            // Producer: send at its own pace; close when done
            JCoroutineHandle<Void> prod = c.launch(cc -> {
                for (int i = 0; i < N; i++) {
                    ch.send(cc, i);                 // blocking send is fine on VT
                    if ((i & 7) == 0) cc.yield();   // sprinkle yields
                }
                ch.close();
            });

            // Consumer: non-blocking poll + yield until we've collected N items
            List<Integer> out = new ArrayList<>(N);
            while (out.size() < N) {
                var opt = ch.tryReceive();          // Optional<T>
                if (opt.isPresent()) {
                    out.add(opt.get());
                } else {
                    c.yield();                      // cooperate instead of busy waiting
                }
            }

            prod.result().join(); // ensure producer finished
            return out;
        });

        assertEquals(N, received.size());
        for (int i = 0; i < N; i++) assertEquals(i, received.get(i));
    }



    @Test
        // Rendezvous fairness: two producers yield between sends to encourage alternation; consumer receives 2*N items.
        // Asserts per-producer counts and that early interleaving occurs; does not require strict alternation (scheduler-dependent).
    void rendezvous_twoProducers_yield_between_sends_fair_enough() throws Exception {
        final int N = 200;
        InteropChannel<String> ch = InteropChannel.rendezvous();

        List<String> got = Coroutines.runBlockingVT(c -> {
            // Producer A
            JCoroutineHandle<Void> a = c.launch(cc -> {
                for (int i = 0; i < N; i++) {
                    ch.send(cc, "A" + i);
                    cc.yield(); // encourage alternation
                }
            });
            // Producer B
            JCoroutineHandle<Void> b = c.launch(cc -> {
                for (int i = 0; i < N; i++) {
                    ch.send(cc, "B" + i);
                    cc.yield();
                }
            });
            // Consumer
            List<String> out = new ArrayList<>(2 * N);
            for (int i = 0; i < 2 * N; i++) {
                out.add(ch.receive(c).orElseThrow());
            }
            a.result().join();
            b.result().join();
            return out;
        });

        long aCount = got.stream().filter(s -> s.startsWith("A")).count();
        long bCount = got.stream().filter(s -> s.startsWith("B")).count();
        assertEquals(N, aCount);
        assertEquals(N, bCount);

        // Not asserting strict alternation (scheduler-dependent), but we should see some interleaving.
        boolean sawSwitch = false;
        for (int i = 1; i < Math.min(50, got.size()); i++) {
            if (got.get(i).charAt(0) != got.get(i - 1).charAt(0)) {
                sawSwitch = true;
                break;
            }
        }
        assertTrue(sawSwitch, "expected some early interleaving with yield()");
    }


    @Test
        // Unlimited channel: consumer uses tryReceive + yield() until expected count to avoid busy-wait.
        // Producer sends 1..5 then close; asserts exact order.
    void unlimited_yieldingConsumer_drains_until_count() throws Exception {
        InteropChannel<Integer> ch = InteropChannel.unlimited();

        List<Integer> values = Coroutines.runBlockingVT(c -> {
            // Producer: 1..5 then close
            c.launch(cc -> {
                for (int i = 1; i <= 5; i++) ch.send(cc, i);
                ch.close();
            });

            List<Integer> acc = new ArrayList<>(5);
            while (acc.size() < 5) {
                var opt = ch.tryReceive();
                if (opt.isPresent()) {
                    acc.add(opt.get());
                } else {
                    c.yield(); // cooperate instead of busy-waiting
                }
            }
            return acc;
        });

        assertEquals(List.of(1, 2, 3, 4, 5), values);
    }


    @Test
        // Cancellation responsiveness: receiver loop uses tryReceive + yield() and should cancel promptly on handle.cancel().
        // Race-avoidance: loop starts spinning before cancel(); verifies both result().join() and join() throw CancellationException.
    void receiverLoop_withYield_isPromptlyCancellable() {
        InteropChannel<Integer> ch = InteropChannel.rendezvous();

        try (JCoroutineScope scope = Coroutines.newScope()) {
            // Receiver that waits for data by tryReceive+yield
            JCoroutineHandle<Void> recv = scope.launch(c -> {
                while (true) {
                    if (ch.tryReceive().isPresent()) {
                        // consume and continue
                    } else {
                        c.yield(); // cooperative checkpoint
                    }
                }
            });

            // Let it start spinning
            while (!recv.result().isDone() && recv.result().isCompletedExceptionally()) { /* no-op */ }

            // Cancel and verify both CF view and handle view surface cancellation
            assertTrue(recv.cancel());
            assertThrows(CancellationException.class, () -> recv.result().join());
            assertThrows(CancellationException.class, recv::join);
        }
    }
}

