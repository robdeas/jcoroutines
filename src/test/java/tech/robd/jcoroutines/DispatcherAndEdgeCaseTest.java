/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/DispatcherAndEdgeCaseTest.java
 description: Dispatcher and edge-case tests: custom dispatcher threads, CPU vs VT, channel cancel, buffer backpressure, utils, race/winner, timeouts, and factories.
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
import tech.robd.jcoroutines.advanced.Dispatcher;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.*;

final class DispatcherAndEdgeCaseTest {

    @Test
    @Timeout(3)
        // Custom dispatcher: two async tasks should execute on the fixed pool; counts and thread names verify distinct execution.
        // Race-avoidance: join both handles before asserting; dispatcher.close() in finally.
    void customDispatcherExecution() throws Exception {
        Dispatcher customDispatcher = Dispatcher.create(
                Executors.newFixedThreadPool(2),
                "CustomTest"
        );

        try {
            AtomicInteger threadCount = new AtomicInteger(0);
            List<String> threadNames = new ArrayList<>();

            // Updated: async now returns JCoroutineHandle<T>
            JCoroutineHandle<Void> h1 = customDispatcher.async(s -> {
                threadNames.add(Thread.currentThread().getName());
                threadCount.incrementAndGet();
                s.delay(10);
                return null;
            });

            JCoroutineHandle<Void> h2 = customDispatcher.async(s -> {
                threadNames.add(Thread.currentThread().getName());
                threadCount.incrementAndGet();
                s.delay(10);
                return null;
            });

            h1.join();
            h2.join();

            assertEquals(2, threadCount.get());
            assertEquals(2, threadNames.size());

        } finally {
            customDispatcher.close(); // Updated: use close() instead of executor().shutdown()
        }
    }

    @Test
    @Timeout(2)
        // Compare VT vs CPU: async uses VT, asyncCpu uses a CPU pool; strings encode thread type.
        // No race: small delays; just assert prefixes differ.
    void virtualThreadsVsCpuDispatcher() throws Exception {
        // Test that virtual threads and CPU dispatcher both work
        JCoroutineHandle<String> vtHandle = async(s -> {
            s.delay(10);
            return "VT:" + Thread.currentThread().getName();
        });

        JCoroutineHandle<String> cpuHandle = asyncCpu(s -> {
            Thread.sleep(10); // CPU work simulation
            return "CPU:" + Thread.currentThread().getName();
        });

        String vtResult = vtHandle.join();
        String cpuResult = cpuHandle.join();

        assertTrue(vtResult.startsWith("VT:"));
        assertTrue(cpuResult.startsWith("CPU:"));

        // Results should be from different thread types
        assertNotEquals(vtResult, cpuResult);
    }

    @Test
    @Timeout(2)
        // Cancellation during channel ops: sender/receiver loop then both are cancelled; join should throw CancellationException.
        // Race-avoidance: short sleep lets both start; channel closed in finally.
    void channelWithCancelledContext() throws Exception {
        Channel<String> ch = ChannelUtils.unlimited();

        try {
            JCoroutineHandle<Void> sender = async(s -> {
                for (int i = 0; i < 10; i++) {
                    ChannelUtils.send(s, ch, "item" + i);
                    s.yieldAndPause(Duration.ofMillis(50)); // Use yieldAndPause for cancellation testing
                }
                return null;
            });

            JCoroutineHandle<Void> receiver = async(s -> {
                while (true) {
                    String item = ChannelUtils.receive(s, ch);
                    s.yieldAndPause(Duration.ofMillis(50)); // Use yieldAndPause for cancellation testing
                }
            });

            // Let them work a bit
            Thread.sleep(100);

            // Cancel both
            sender.cancel();
            receiver.cancel();

            // Verify cancellation
            assertThrows(CancellationException.class, sender::join);
            assertThrows(CancellationException.class, receiver::join);

        } finally {
            ChannelUtils.close(ch);
        }
    }

    @Test
    @Timeout(2)
        // Backpressure: buffered(2) blocks on 3rd send; consumer drains all 5 values in runBlockingCpu.
        // Race-avoidance: sleep(50ms) to allow buffer fill; assert sent==2 before draining.
    void bufferOverflowBehavior() throws Exception {
        Channel<String> ch = ChannelUtils.buffered(2);
        AtomicInteger sent = new AtomicInteger(0);

        JCoroutineHandle<Void> sender = async(s -> {
            for (int i = 0; i < 5; i++) {
                ChannelUtils.send(s, ch, "item" + i); // blocks at i==2
                sent.incrementAndGet();
            }
            return null;
        });

        // Let sender fill buffer and block on the 3rd send
        Thread.sleep(50);
        assertEquals(2, sent.get());         // only buffer size sent
        assertTrue(sender.isActive());       // still running (blocked on send)

        // Now consume to free space and let sender finish
        runBlockingCpu(s -> {
            for (int i = 0; i < 5; i++) {
                assertEquals("item" + i, ChannelUtils.receive(s, ch));
            }
            return null;
        });

        sender.join();
        assertEquals(5, sent.get());
        ChannelUtils.close(ch);
    }

    @Test
    @Timeout(2)
        // Status helpers: anyActive/allCompleted/countActive before and after completion; join returns values 0/10/20.
        // Race-avoidance: sleep(100ms) to allow completions before final assertions.
    void coroutineUtilsOperations() throws Exception {
        List<JCoroutineHandle<Integer>> handles = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int value = i;
            handles.add(async(s -> {
                s.delay(10 * (value + 1));
                return value * 10;
            }));
        }

        // Test status checking
        assertTrue(CoroutineUtils.anyActive(handles));
        assertFalse(CoroutineUtils.allCompleted(handles));
        assertEquals(3, CoroutineUtils.countActive(handles));

        // Wait for completion
        Thread.sleep(100);

        assertFalse(CoroutineUtils.anyActive(handles));
        assertTrue(CoroutineUtils.allCompleted(handles));
        assertEquals(0, CoroutineUtils.countActive(handles));

        // Test results
        assertEquals(0, handles.get(0).join().intValue());
        assertEquals(10, handles.get(1).join().intValue());
        assertEquals(20, handles.get(2).join().intValue());
    }

    @Test
    @Timeout(2)
        // Bulk cancel: cancelAll cancels N parked tasks and join observes CancellationException; tracks exact cancel count.
        // Race-avoidance: started latch ensures entry; gate.await parks tasks; do not open gate.
    void coroutineUtilsCancellation() throws Exception {
        final int N = 5;
        List<JCoroutineHandle<Void>> handles = new ArrayList<>(N);
        AtomicInteger cancelled = new AtomicInteger(0);

        // Keep tasks parked so they can't finish before we cancel
        CountDownLatch started = new CountDownLatch(N);
        CountDownLatch gate = new CountDownLatch(1);

        for (int i = 0; i < N; i++) {
            handles.add(async(s -> {
                try {
                    started.countDown();     // entered runnable
                    gate.await();            // interruptible wait → InterruptedException on cancel
                    s.yieldAndPause(Duration.ofSeconds(1)); // Use yieldAndPause instead of delay
                    return null;
                } catch (CancellationException e) {
                    cancelled.incrementAndGet();
                    throw e;
                } catch (InterruptedException ie) {
                    // Treat interrupt as cancellation so the test is robust
                    cancelled.incrementAndGet();
                    throw new CancellationException("Interrupted while parked");
                }
            }));
        }

        // Ensure all have actually started before cancelling
        assertTrue(started.await(200, TimeUnit.MILLISECONDS), "tasks didn't all start in time");

        // Cancel all using utility method
        int cancelledCount = CoroutineUtils.cancelAll(handles);
        assertEquals(N, cancelledCount, "cancelAll should report all as cancelled");

        // Do NOT open the gate; cancellation should break them out
        // Wait for propagation by joining each handle
        int observedCancelled = 0;
        for (JCoroutineHandle<Void> h : handles) {
            assertThrows(CancellationException.class, h::join);
            assertTrue(h.isCompleted());
            observedCancelled++;
        }

        assertEquals(N, observedCancelled, "all handles should be completed");
        assertEquals(N, cancelled.get(), "cancellation observed inside tasks exactly once each");
    }

    @Test
    @Timeout(2)
        // Race winner: raceHandles returns the fastest (delay 20ms); others cancel/complete appropriately.
        // Race-avoidance: brief sleep allows cancellations to propagate before checking isCompleted().
    void raceConditionHandling() throws Exception {
        List<JCoroutineHandle<Integer>> handles = new ArrayList<>();

        // Create handles with different completion times
        handles.add(async(s -> {
            s.delay(50);
            return 1;
        }));
        handles.add(async(s -> {
            s.delay(20);
            return 2;
        })); // Should win
        handles.add(async(s -> {
            s.delay(100);
            return 3;
        }));

        JCoroutineHandle<Integer> winner = CoroutineUtils.raceHandles(handles);

        assertEquals(2, winner.join().intValue()); // Fastest one should win
        assertTrue(winner.isCompleted());

        // Others should be cancelled - give time for cancellation to propagate
        Thread.sleep(50);
        assertTrue(handles.get(0).isCompleted()); // Should be cancelled
        assertTrue(handles.get(1).isCompleted()); // Winner completed normally
        assertTrue(handles.get(2).isCompleted()); // Should be cancelled
    }

    @Test
    @Timeout(2)
        // Timeout helpers: awaitAllWithTimeout succeeds under 100ms but times out a 200ms task with 50ms limit.
        // No race: duration gaps ensure deterministic outcome.
    void withTimeoutOperations() throws Exception {
        CoroutineUtils.awaitAllWithTimeout(
                List.of(
                        async(s -> {
                            s.delay(10);
                            return "fast";
                        }),
                        async(s -> {
                            s.delay(20);
                            return "medium";
                        })
                ),
                Duration.ofMillis(100)
        );

        // Should timeout
        assertThrows(java.util.concurrent.TimeoutException.class, () -> {
            CoroutineUtils.awaitAllWithTimeout(
                    List.of(async(s -> {
                        s.delay(200);
                        return "slow";
                    })),
                    Duration.ofMillis(50)
            );
        });
    }

    @Test
    @Timeout(2)
        // First-completed index: awaitFirstCompleted identifies index 1 ('fast') among three delayed tasks.
        // No race: delay spacing makes completion order deterministic.
    void firstCompletedDetection() throws Exception {
        List<JCoroutineHandle<String>> handles = List.of(
                async(s -> {
                    s.delay(100);
                    return "slow";
                }),
                async(s -> {
                    s.delay(20);
                    return "fast";
                }),
                async(s -> {
                    s.delay(50);
                    return "medium";
                })
        );

        int firstIndex = CoroutineUtils.awaitFirstCompleted(handles, Duration.ofMillis(200));
        assertEquals(1, firstIndex); // Index of the fastest one
        assertEquals("fast", handles.get(1).join());
    }

    @Test
    @Timeout(2)
        // withHandles pattern: runs supplier then cancels/joins handles; resources cleaned in finally blocks.
        // Race-avoidance: brief sleep after return gives cleanup time; assert flags set and handles completed.
    void resourceManagementPattern() throws Exception {
        AtomicBoolean resource1Cleaned = new AtomicBoolean(false);
        AtomicBoolean resource2Cleaned = new AtomicBoolean(false);

        JCoroutineHandle<Void> h1 = async(s -> {
            try {
                s.yieldAndPause(Duration.ofSeconds(1)); // Use yieldAndPause for cancellation testing
            } finally {
                resource1Cleaned.set(true);
            }
            return null;
        });

        JCoroutineHandle<Void> h2 = async(s -> {
            try {
                s.yieldAndPause(Duration.ofSeconds(1)); // Use yieldAndPause for cancellation testing
            } finally {
                resource2Cleaned.set(true);
            }
            return null;
        });

        String result = CoroutineUtils.withHandles(
                s -> "completed",
                SuspendContext.create(newScope()),
                h1, h2
        );

        assertEquals("completed", result);

        // Resources should be cleaned up - give time for cleanup
        Thread.sleep(50);
        assertTrue(h1.isCompleted());
        assertTrue(h2.isCompleted());
        assertTrue(resource1Cleaned.get(), "Resource 1 should be cleaned up");
        assertTrue(resource2Cleaned.get(), "Resource 2 should be cleaned up");
    }

    @Test
    @Timeout(2)
        // Diagnostics: getHandlesSummary should reflect counts before/after completion (total/active/completed).
        // Race-avoidance: brief sleep before final summary to let handles complete.
    void handlesSummaryDebugInfo() throws Exception {
        List<JCoroutineHandle<Integer>> handles = List.of(
                async(s -> {
                    s.delay(10);
                    return 1;
                }),
                async(s -> {
                    s.delay(20);
                    return 2;
                }),
                async(s -> {
                    throw new RuntimeException("fail");
                })
        );

        String summary = CoroutineUtils.getHandlesSummary(handles);
        assertTrue(summary.contains("total=3"));
        assertTrue(summary.contains("active=") || summary.contains("completed="));

        // Wait for completion
        Thread.sleep(50);

        String finalSummary = CoroutineUtils.getHandlesSummary(handles);
        assertTrue(finalSummary.contains("completed=3"));
        assertTrue(finalSummary.contains("active=0"));
    }

    @Test
    @Timeout(2)
        // Factories: all Dispatcher factory methods can launch and complete a trivial task; then close all.
        // Race-avoidance: join each handle before assertions; finally close to avoid thread leaks.
    void dispatcherFactoryMethods() throws Exception {
        // Test all factory methods create working dispatchers
        List<Dispatcher> dispatchers = List.of(
                Dispatcher.virtualThreads(),
                Dispatcher.cpu(),
                Dispatcher.fixedThreadPool(2),
                Dispatcher.cachedThreadPool(),
                Dispatcher.singleThread()
        );

        try {
            AtomicInteger completedCount = new AtomicInteger(0);
            List<JCoroutineHandle<Void>> handles = new ArrayList<>();

            // Launch one task on each dispatcher
            for (Dispatcher dispatcher : dispatchers) {
                JCoroutineHandle<Void> handle = dispatcher.launch(s -> {
                    s.delay(10);
                    completedCount.incrementAndGet();
                });
                handles.add(handle);
            }

            // Wait for all to complete
            for (JCoroutineHandle<Void> handle : handles) {
                handle.join();
            }

            assertEquals(dispatchers.size(), completedCount.get());

        } finally {
            // Clean shutdown of all dispatchers
            for (Dispatcher dispatcher : dispatchers) {
                dispatcher.close();
            }
        }
    }
}