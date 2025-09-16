/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/PatternHelpers.java
 description: Helper methods for common structured-concurrency patterns (pipeline, fan-out/fan-in) built on SuspendContext and Channel APIs.
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

package tech.robd.jcoroutines;

import org.jspecify.annotations.NonNull;
import tech.robd.jcoroutines.diagnostics.Diagnostics;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Pattern helpers showcasing structured-concurrency usage with {@link SuspendContext},
 * {@link Channel}, and {@link ChannelUtils}. These examples favour clarity over
 * micro-optimisation and are intended for learning, tests, and small utilities.
 *
 * <p><strong>Concurrency rules:</strong></p>
 * <ul>
 *   <li>All blocking operations use an explicit {@link SuspendContext} and are
 *   cooperative (cancellable via {@link java.util.concurrent.CancellationException}).</li>
 *   <li>Channels are closed explicitly; receivers detect closure via
 *   {@link tech.robd.jcoroutines.internal.BaseChannel.ClosedReceiveException}.</li>
 *   <li>Child tasks are launched via {@link SuspendContext#launch(SuspendRunnable)} or
 *   {@link SuspendContext#async(SuspendFunction)} and inherit cancellation.</li>
 * </ul>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class PatternHelpers {

    private static final Diagnostics DIAG = Diagnostics.of(PatternHelpers.class);

    private PatternHelpers() {
    }

    // 🧩 Section: workers

    /**
     * Process a single task — simulates variable work time to demonstrate scheduling and cancellation.
     *
     * @param s          suspending context
     * @param workerName name to include in the result
     * @param task       task payload
     * @return formatted result string
     */
    public static @NonNull String processTask(@NonNull SuspendContext s,
                                              @NonNull String workerName,
                                              @NonNull String task) {
        long processingTime = 50 + (long) (Math.random() * 200);
        s.delay(processingTime);
        return "[" + workerName + "] completed: " + task;
    }

    /**
     * Simple consumer that prints items until the channel is closed.
     *
     * @param s          suspending context
     * @param input      channel of tasks
     * @param workerName diagnostic label
     */
    public static void processData(@NonNull SuspendContext s,
                                   @NonNull Channel<String> input,
                                   @NonNull String workerName) {
        input.forEach(s, (ctx, task) -> {
            ctx.delay(100);
            DIAG.info("[{}] Processing: {}", workerName, task);
        });
        // normal termination when the channel is closed
    }

    /**
     * Worker for fan-out/fan-in: consumes from workChannel, emits to resultChannel.
     *
     * @param s             suspending context
     * @param name          worker name
     * @param workChannel   source of tasks
     * @param resultChannel sink for results
     */
    public static void worker(@NonNull SuspendContext s,
                              @NonNull String name,
                              @NonNull Channel<String> workChannel,
                              @NonNull Channel<String> resultChannel) {
        workChannel.forEach(s, (ctx, task) -> {
            try {
                String result = processTask(ctx, name, task);
                ChannelUtils.send(ctx, resultChannel, result);
            } catch (Throwable t) {
                DIAG.error("[{}] Error processing {}: {}", name, task, t.getMessage(), t);
            }
        });
        // loop ends when workChannel is closed
    }
    // [/🧩 Section: workers]

    // 🧩 Section: patterns

    /**
     * Producer–workers–collector pipeline.
     * Assumes 1:1 mapping: {@code #results == inputData.size()}.
     *
     * @param s          suspending context
     * @param inputData  items to process
     * @param numWorkers number of workers (must be &gt; 0)
     * @return ordered list of results matching {@code inputData} size
     * @throws IllegalArgumentException if {@code numWorkers <= 0}
     */
    public static List<String> runPipeline(@NonNull SuspendContext s,
                                           @NonNull List<String> inputData,
                                           int numWorkers) {
        if (numWorkers <= 0) throw new IllegalArgumentException("numWorkers must be > 0");

        Channel<String> work = ChannelUtils.unlimited();
        Channel<String> out = ChannelUtils.unlimited();

        return runWorkerPipeline(s, inputData, numWorkers, work, out);
    }

    @NonNull
    private static List<String> runWorkerPipeline(@NonNull SuspendContext s,
                                                  @NonNull List<String> inputData,
                                                  int numWorkers,
                                                  Channel<String> work,
                                                  Channel<String> out) {
        try {
            // Producer
            s.launch(ctx -> {
                for (String item : inputData) ChannelUtils.send(ctx, work, item);
                ChannelUtils.close(work);
            });

            // Workers - collect handles for proper cancellation
            List<JCoroutineHandle<Void>> workerHandles = new ArrayList<>(numWorkers);
            for (int i = 0; i < numWorkers; i++) {
                String workerName = "worker-" + (i + 1);
                JCoroutineHandle<Void> handle = s.async(ctx -> {
                    worker(ctx, workerName, work, out);
                    return null;
                });
                workerHandles.add(handle);
            }

            // Collector (expects exactly inputData.size() results)
            List<String> results = new ArrayList<>(inputData.size());
            JCoroutineHandle<List<String>> collectorHandle = s.async(ctx -> {
                try {
                    for (int i = 0; i < inputData.size(); i++) {
                        results.add(ChannelUtils.receive(ctx, out));
                    }
                    return results;
                } finally {
                    ChannelUtils.close(out);
                }
            });

            // Convert handles to futures for awaitAll
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] workerFutures = workerHandles.stream()
                    .map(JCoroutineHandle::result)
                    .toArray(CompletableFuture[]::new);

            s.awaitAll(workerFutures);
            return Objects.requireNonNull(s.await(collectorHandle.result()));
        } finally {
            safeClose(work);
            safeClose(out);
        }
    }

    /**
     * Fan-out tasks to multiple workers, then fan-in the results.
     * Uses a bounded work queue sized to the task list.
     *
     * @param s          suspending context
     * @param tasks      items to process
     * @param numWorkers number of workers (&gt; 0)
     * @return results in arbitrary worker-completion order, but collected deterministically
     * @throws IllegalArgumentException if {@code numWorkers <= 0}
     */
    public static List<String> fanOutFanIn(@NonNull SuspendContext s,
                                           @NonNull List<String> tasks,
                                           int numWorkers) {
        if (numWorkers <= 0) throw new IllegalArgumentException("numWorkers must be > 0");

        Channel<String> work = ChannelUtils.buffered(Math.max(1, tasks.size()));
        Channel<String> out = ChannelUtils.unlimited();

        return runWorkerPipeline(s, tasks, numWorkers, work, out);
    }

    /**
     * Three-stage pipeline – each stage transforms 1:1.
     * Stage loops use {@link Channel#forEach(SuspendContext, SuspendConsumer)} and
     * terminate when the previous stage closes.
     *
     * @param s       suspending context
     * @param rawData input items
     * @return final processed results
     */
    public static List<String> multiStagePipeline(@NonNull SuspendContext s,
                                                  @NonNull List<String> rawData) {
        Channel<String> stage1 = ChannelUtils.unlimited();
        Channel<String> stage2 = ChannelUtils.unlimited();
        Channel<String> stage3 = ChannelUtils.unlimited();

        try {
            // Stage 1: ingest -> clean
            s.launch(ctx -> {
                for (String raw : rawData) {
                    ChannelUtils.send(ctx, stage1, cleanData(ctx, raw));
                }
                ChannelUtils.close(stage1);
            });

            // Stage 2: transform
            s.launch(ctx -> {
                stage1.forEach(ctx, (c, data) -> {
                    ChannelUtils.send(c, stage2, transformData(c, data));
                });
                ChannelUtils.close(stage2);
            });

            // Stage 3: final process
            s.launch(ctx -> {
                stage2.forEach(ctx, (c, data) -> {
                    ChannelUtils.send(c, stage3, finalProcessData(c, data));
                });
                ChannelUtils.close(stage3);
            });

            // Collect final results (we know the count is rawData.size())
            List<String> results = new ArrayList<>(rawData.size());
            for (int i = 0; i < rawData.size(); i++) {
                results.add(ChannelUtils.receive(s, stage3));
            }
            return results;
        } finally {
            safeClose(stage1);
            safeClose(stage2);
            safeClose(stage3);
        }
    }
    // [/🧩 Section: patterns]

    // 🧩 Section: handles

    /**
     * Cancellable version of {@link #runPipeline(SuspendContext, List, int)} that returns handles
     * for fine-grained control.
     *
     * @param s          suspending context
     * @param inputData  items to process
     * @param numWorkers number of workers (&gt; 0)
     * @return a {@link PipelineResult} bundle with handles and channels
     * @throws IllegalArgumentException if {@code numWorkers <= 0}
     */
    public static PipelineResult runPipelineWithHandles(@NonNull SuspendContext s,
                                                        @NonNull List<String> inputData,
                                                        int numWorkers) {
        if (numWorkers <= 0) throw new IllegalArgumentException("numWorkers must be > 0");

        Channel<String> work = ChannelUtils.unlimited();
        Channel<String> out = ChannelUtils.unlimited();

        // Producer
        JCoroutineHandle<Void> producerHandle = s.async(ctx -> {
            for (String item : inputData) ChannelUtils.send(ctx, work, item);
            ChannelUtils.close(work);
            return null;
        });

        // Workers
        List<JCoroutineHandle<Void>> workerHandles = new ArrayList<>(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            String workerName = "worker-" + (i + 1);
            JCoroutineHandle<Void> handle = s.async(ctx -> {
                worker(ctx, workerName, work, out);
                return null;
            });
            workerHandles.add(handle);
        }

        // Collector
        List<String> results = new ArrayList<>(inputData.size());
        JCoroutineHandle<List<String>> collectorHandle = s.async(ctx -> {
            try {
                for (int i = 0; i < inputData.size(); i++) {
                    results.add(ChannelUtils.receive(ctx, out));
                }
                return results;
            } finally {
                ChannelUtils.close(out);
                safeClose(work);
            }
        });

        return new PipelineResult(producerHandle, workerHandles, collectorHandle, work, out);
    }

    /**
     * Result holder for pipeline with handles for cancellation control.
     */
    public static final class PipelineResult {
        /**
         * Producer handle.
         */
        public final JCoroutineHandle<Void> producer;
        /**
         * Worker handles (immutable view).
         */
        public final List<JCoroutineHandle<Void>> workers;
        /**
         * Collector handle.
         */
        public final JCoroutineHandle<List<String>> collector;
        private final Channel<String> workChannel;
        private final Channel<String> resultChannel;

        private PipelineResult(JCoroutineHandle<Void> producer,
                               List<JCoroutineHandle<Void>> workers,
                               JCoroutineHandle<List<String>> collector,
                               Channel<String> workChannel,
                               Channel<String> resultChannel) {
            this.producer = producer;
            this.workers = List.copyOf(workers); // immutable view
            this.collector = collector;
            this.workChannel = workChannel;
            this.resultChannel = resultChannel;
        }

        /**
         * Await the collector and return the results.
         *
         * @return collected results
         * @throws Exception if awaiting the collector fails
         */
        public List<String> awaitResults() throws Exception {
            return collector.join();
        }

        /**
         * Cancel producer, workers, and collector, and then {@link #cleanup()} channels.
         */
        public void cancelAll() {
            producer.cancel();
            workers.forEach(JCoroutineHandle::cancel);
            collector.cancel();
            cleanup();
        }

        /**
         * Close any remaining open channels associated with the pipeline.
         */
        public void cleanup() {
            safeClose(workChannel);
            safeClose(resultChannel);
        }
    }
    // [/🧩 Section: handles]

    // 🧩 Section: utilities
    private static String cleanData(@NonNull SuspendContext s, @NonNull String raw) {
        s.delay(10);
        return raw.trim().toLowerCase();
    }

    private static String transformData(@NonNull SuspendContext s, @NonNull String data) {
        s.delay(20);
        return "transformed[" + data + "]";
    }

    private static String finalProcessData(@NonNull SuspendContext s, @NonNull String data) {
        s.delay(15);
        return "processed[" + data + "]";
    }

    private static void safeClose(Channel<?> ch) {
        try {
            ChannelUtils.close(ch);
        } catch (Throwable ignored) {
        }
    }
    // [/🧩 Section: utilities]
}
