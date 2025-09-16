/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/ChannelStressTest.java
 description: Stress tests for Channel with many producers/consumers and close() waking blocked receivers.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.async;

// TODO fix errors
final class ChannelStressTest {
    @Test
    @Timeout(4)
        // Stress test: N producers and M consumers on a buffered channel; counts must match exactly.
        // Race-avoidance: wait for ALL producers (join) before close(), then close to release consumers, then join consumers.
    void manyProducersConsumersBuffered() throws Exception {
        final int PRODUCERS = 5, CONSUMERS = 5, N = 100;
        Channel<Integer> ch = Channel.buffered(32);
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();

        List<JCoroutineHandle<Void>> consumerHandles = new ArrayList<>();
        List<JCoroutineHandle<Void>> producerHandles = new ArrayList<>();

        // consumers
        for (int i = 0; i < CONSUMERS; i++) {
            consumerHandles.add(async(s -> {
                try {
                    for (; ; ) {
                        int v = ChannelUtils.receive(s, ch);
                        assertNotNull(v);
                        consumed.incrementAndGet();
                    }
                } catch (Channel.ClosedReceiveException ignored) {
                    // drained
                }
                return null;
            }));
        }

        // producers
        for (int p = 0; p < PRODUCERS; p++) {
            final int id = p;
            producerHandles.add(async(s -> {
                for (int i = 0; i < N; i++) {
                    ChannelUtils.send(s, ch, id * N + i);
                    produced.incrementAndGet();
                }
                return null;
            }));
        }

        // wait for ALL producers to finish, then close to release consumers
        for (var h : producerHandles) h.join();
        ChannelUtils.close(ch);

        // now join consumers
        for (var h : consumerHandles) h.join();

        assertEquals(PRODUCERS * N, produced.get());
        assertEquals(PRODUCERS * N, consumed.get());
    }


    @Test
    @Timeout(2)
        // Close semantics: a blocked receiver should unblock with ClosedReceiveException when the channel is closed.
        // Race-avoidance: brief sleep ensures the receiver is parked before close().
    void closeWakesReceivers() throws Exception {
        Channel<String> ch = Channel.unlimited();
        var blocked = async(s -> {
            try {
                ChannelUtils.receive(s, ch);
                fail("should close");
            } catch (Channel.ClosedReceiveException ignored) {
            }
            return null;
        });
        Thread.sleep(20);
        ch.close();
        blocked.join();
    }
}
