/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/ChannelBasicsTest.java
 description: Basic channel tests: send/receive/close, receive-after-close failure, forEach draining, and buffered order guarantees.
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tech.robd.jcoroutines.ChannelUtils.*;
import static tech.robd.jcoroutines.Coroutines.runBlockingCpu;

final class ChannelBasicsTest {

    @Test
    @Timeout(2)
        // Happy-path: producer sends two items then close(); consumer receives both.
        // Race-avoidance: producer launched within same runBlockingCpu; finally block closes channel idempotently.
    void sendReceiveAndClose() {
        var ch = unlimited();
        try {
            runBlockingCpu(s -> {
                s.launch(ctx -> {
                    send(ctx, ch, "hello");
                    send(ctx, ch, "world");
                    close(ch);
                });
                assertEquals("hello", receive(s, ch));
                assertEquals("world", receive(s, ch));
                return null;
            });
        } finally {
            // idempotent close in case the producer failed early
            close(ch);
        }
    }

    @Test
    @Timeout(2)
        // Failure-path: receiving after close should throw Channel.ClosedReceiveException.
        // No race: channel is closed before runBlockingCpu; finally ensures idempotent close.
    void receiveAfterCloseThrows() {
        var ch = unlimited();
        try {
            close(ch);
            assertThrows(Channel.ClosedReceiveException.class, () ->
                    runBlockingCpu(s -> receive(s, ch))
            );
        } finally {
            close(ch);
        }
    }

    @Test
    @Timeout(2)
        // forEach drains all items until close().
        // Race-avoidance: producer sends 1..5 and then close within same structured block.
    void forEachConsumesAll() {
        var ch = unlimited();
        try {
            List<String> got = new ArrayList<>();
            runBlockingCpu(s -> {
                s.launch(ctx -> {
                    for (int i = 1; i <= 5; i++) send(ctx, ch, "Item" + i);
                    close(ch);
                });
                ch.forEach(s, (ctx, v) -> got.add((String) v));
                return null;
            });
            assertEquals(List.of("Item1", "Item2", "Item3", "Item4", "Item5"), got);
        } finally {
            close(ch);
        }
    }

    @Test
    @Timeout(2)
        // Ordering guarantee: buffered channel preserves send order (A,B,C).
        // Race-avoidance: capacity=2 so third send blocks until consumer drains one; forEach runs in same runBlockingCpu.
    void bufferedChannelOrderIsPreserved() {
        var ch = buffered(2);
        try {
            List<String> got = new ArrayList<>();
            runBlockingCpu(s -> {
                // producer
                s.launch(ctx -> {
                    send(ctx, ch, "A");
                    send(ctx, ch, "B"); // fits in buffer
                    send(ctx, ch, "C"); // may block until a receive happens
                    close(ch);
                });
                // consumer
                ch.forEach(s, (ctx, v) -> got.add((String) v));
                return null;
            });
            assertEquals(List.of("A", "B", "C"), got);
        } finally {
            close(ch);
        }
    }
}
