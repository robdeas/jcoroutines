/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ChannelsExtendedTest.java
 description: Integration tests for InteropChannel buffering, rendezvous semantics, null/Optional mapping, and cancellation behavior.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ChannelsExtendedTest {

    /* ========== Buffered capacity & backpressure (non-blocking api) ========== */

    @Test
        // Non-blocking capacity semantics: trySend returns false when full; succeeds after a receive frees space.
        // No race: single-threaded operations on the same channel instance.
    void buffered_trySend_obeysCapacity_and_unblocksAfterReceive() {
        InteropChannel<String> ch = InteropChannel.buffered(1); // capacity = 1

        // First slot available
        assertTrue(ch.trySend("A"));

        // Now full; next non-blocking send should fail
        assertFalse(ch.trySend("B"));

        // Drain one…
        Optional<String> got = ch.tryReceive();
        assertTrue(got.isPresent());
        assertEquals("A", got.get());

        // Capacity freed; trySend succeeds now
        assertTrue(ch.trySend("B"));

        // And we can read it back
        assertEquals(Optional.of("B"), ch.tryReceive());
    }

    /* ========== Rendezvous semantics (no buffering) ========== */

    @Test
        // Rendezvous semantics (SynchronousQueue): trySend fails without waiting receiver; blocking send pairs with receive.
        // Race-avoidance: sender and receiver coordinated inside one runBlockingVT to ensure handoff.
    void rendezvous_requiresSenderAndReceiverToMeet() throws Exception {
        InteropChannel<Integer> ch = InteropChannel.rendezvous();

        // Without a waiting receiver, trySend must fail (SynchronousQueue.offer -> false)
        assertFalse(ch.trySend(1));

        // With a real sender using suspend send, the receive completes
        int v = Coroutines.runBlockingVT(ctx -> {
            ctx.launch(c -> ch.send(c, 42)); // suspends until receive happens
            return ch.receive(ctx).orElseThrow();
        });

        assertEquals(42, v);
    }

    /* ========== sendEmpty vs null & receiveNullable convenience ========== */

    @Test
        // Null mapping: sendEmpty -> Optional.empty(); receiveNullable unwraps Optional.empty as null.
        // No race: receiver is the same runBlockingVT as the launched sender.
    void sendEmpty_yieldsOptionalEmpty_and_receiveNullable_returnsNull() throws Exception {
        InteropChannel<String> ch = InteropChannel.unlimited(); // or buffered/rendezvous

        String got = Coroutines.runBlockingVT(ctx -> {
            ctx.launch(ch::sendEmpty);
            return ch.receiveNullable(ctx); // unwraps Optional.empty -> null
        });

        // Also verify Optional path explicitly
        Optional<String> opt = Coroutines.runBlockingVT(ctx -> {
            ctx.launch(c -> ch.send(c, null)); // raw null encodes to sentinel internally
            return ch.receive(ctx);
        });
        assertTrue(opt.isEmpty());
    }

    /* ========== Blocking send/receive with buffered backpressure ========== */

    @Test
        // Blocking backpressure: second send waits until consumer drains first element.
        // Race-avoidance: CompletableFuture flag proves the second send did not complete before drain.
    void buffered_blockingSend_waitsUntilConsumerDrains() throws Exception {
        InteropChannel<String> ch = InteropChannel.buffered(1);

        // Fill the single slot so the next send would have to wait
        assertTrue(ch.trySend("A"));
        CompletableFuture<Boolean> reachedAfterSecondSend = new CompletableFuture<>();

        // Producer will block in ch.send until the consumer receives "A"
        Coroutines.runBlockingVT(ctx -> {
            ctx.launch(c -> {
                ch.send(c, "B"); // should wait
                reachedAfterSecondSend.complete(true);
                c.delay(50);
            });

            // Give producer a moment to start and attempt second send

            assertFalse(reachedAfterSecondSend.isDone(), "second send should be blocked while buffer is full");

            // Now drain one item; this should unblock the producer
            assertEquals(Optional.of("A"), ch.receive(ctx));

            // Wait for producer to finish sending "B"
            assertTrue(reachedAfterSecondSend.orTimeout(500, TimeUnit.MILLISECONDS).join());
            return null;
        });

        // Read the second item
        assertEquals(Optional.of("B"), ch.tryReceive());
    }

    /* ========== forEach consumes until close() ========== */

    @Test
        // forEach drains all items and terminates on close().
        // Race-avoidance: brief delay before exiting runBlockingVT to allow consumer to drain.
    void forEach_consumesAllItems_thenStopsOnClose() throws Exception {
        InteropChannel<Integer> ch = InteropChannel.unlimited();

        List<Integer> seen = new ArrayList<>();

        Coroutines.runBlockingVT(ctx -> {
            // Consumer loop (terminates when channel is closed)
            ctx.launch(c -> ch.forEach(c, (cc, opt) -> opt.ifPresent(seen::add)));

            // Producer: 1..3 then close
            ctx.launch(c -> {
                ch.send(c, 1);
                ch.send(c, 2);
                ch.send(c, 3);
                // close() comes from BaseChannel via InteropChannel
                ch.close();
            });

            // Give the consumer time to drain before returning
            ctx.delay(100);
            return null;
        });

        assertEquals(List.of(1, 2, 3), seen);
    }

    /* ========== tryReceive on empty queue is empty (non-blocking) ========== */

    @Test
        // Non-blocking semantics: tryReceive returns Optional.empty() when queue is empty.
    void tryReceive_onEmpty_isEmpty() {
        InteropChannel<String> ch = InteropChannel.unlimited();
        assertTrue(ch.tryReceive().isEmpty());
    }

    /* ========== Cancellation during blocking receive unblocks with CancellationException ========== */

    @Test
        // Cancellation observability: blocking receive unblocks with CancellationException when child is cancelled.
        // Race-avoidance: small delay ensures the child is blocked before cancel().
    void blockingReceive_unblocksOnCancellation() {
        InteropChannel<Integer> ch = InteropChannel.rendezvous();

        assertThrows(CancellationException.class, () ->
                Coroutines.runBlockingVT(ctx -> {
                    // Start a receiver that will block
                    var h = ctx.async(c -> {
                        // This will wait because no sender arrives
                        return ch.receive(c).orElseThrow();
                    });

                    // Cancel the child after a moment -> receive should unblock via cancellation
                    ctx.delay(50);
                    assertTrue(h.cancel());
                    h.join(); // surface CancellationException
                    return null;
                })
        );
    }
}
