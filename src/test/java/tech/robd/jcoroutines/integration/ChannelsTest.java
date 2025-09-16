/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ChannelsTest.java
 description: Basic channel tests: send/receive on unlimited channels, multiple producers, and null round-trips.
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
 */
package tech.robd.jcoroutines.integration;

import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.Coroutines;
import tech.robd.jcoroutines.InteropChannel;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ChannelsTest {

    @Test
        // Basic send/receive on an unbounded channel under runBlockingVT.
        // No race: producer launched inside the same structured block; single consumer receives once.
    void sendReceive_unlimitedChannel() throws Exception {
        InteropChannel<Integer> ch = InteropChannel.unlimited();

        int got = Coroutines.runBlockingVT(ctx -> {
            // Producer
            ctx.launch(c -> {
                ch.send(c, 42);
            });

            // Consumer (must pass ctx)
            return ch.receive(ctx).orElseThrow();
        });

        assertEquals(42, got);
    }

    @Test
        // Two producers send concurrently; consumer receives twice within same structured block.
        // Race-avoidance: assertion uses Set equality (order-agnostic) to avoid relying on arrival order.
    void multipleSends_multipleReceives_structured() throws Exception {
        InteropChannel<String> ch = InteropChannel.unlimited();

        List<String> received = Coroutines.runBlockingVT(ctx -> {
            // Two producers on VT
            ctx.launch(c -> {
                ch.send(c, "a");
            });
            ctx.launch(c -> {
                ch.send(c, "b");
            });

            // Consume both within the same structured block
            String r1 = ch.receive(ctx).orElseThrow();
            String r2 = ch.receive(ctx).orElseThrow();
            return List.of(r1, r2);
        });

        assertEquals(Set.of("a", "b"), Set.copyOf(received));
    }

    @Test
        // Null round-trip: send(null) encodes to sentinel; receiveNullable unwraps as null.
        // No race: producer and consumer share the same runBlockingVT scope.
    void nullRoundTrip_withReceiveNullable() throws Exception {
        InteropChannel<String> ch = InteropChannel.unlimited();

        String got = Coroutines.runBlockingVT(ctx -> {
            ctx.launch(c -> {
                ch.send(c, null);
            }); // null encodes as OPTIONAL_EMPTY internally
            return ch.receiveNullable(ctx);         // convenience unwrap → null
        });

        assertNull(got, "receiveNullable should yield null when sender sent null");
    }
}
