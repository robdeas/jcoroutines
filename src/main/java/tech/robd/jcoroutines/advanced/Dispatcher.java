/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/advanced/Dispatcher.java
 description: Coroutine dispatcher abstraction wrapping an ExecutorService. Provides async and launch
              with cancellation; lifecycle control (close/kill); predefined executors (VT, CPU, fixed, cached, single).
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 [/File Info]
*/
/*
 * Copyright (c) 2025 Rob Deas Ltd.
 *
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

package tech.robd.jcoroutines.advanced;

import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.CancellationToken;
import tech.robd.jcoroutines.SuspendContext;
import tech.robd.jcoroutines.SuspendFunction;
import tech.robd.jcoroutines.SuspendRunnable;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.CancellationTokenImpl;
import tech.robd.jcoroutines.internal.JCoroutineHandleImpl;
import tech.robd.jcoroutines.internal.JCoroutineScopeImpl;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;

/**
 * A {@code Dispatcher} runs coroutines on a specific {@link ExecutorService}.
 *
 * <p>It allows you to:
 * <ul>
 *   <li>Run suspendable functions asynchronously with {@link #async(SuspendFunction)}.</li>
 *   <li>Launch fire-and-forget tasks with {@link #launch(SuspendRunnable)} (still cancellable via handle).</li>
 *   <li>Cancel or await tasks via {@link JCoroutineHandle}.</li>
 *   <li>Control lifecycle with {@link #close()} (graceful) or {@link #kill()} (forceful).</li>
 * </ul>
 *
 * <p>Predefined factory methods:
 * {@link #virtualThreads()}, {@link #cpu()}, {@link #fixedThreadPool(int)},
 * {@link #cachedThreadPool()}, {@link #singleThread()}.
 */
public final class Dispatcher {
    private final ExecutorService executor;
    private final String name;
    private final Set<WeakReference<JCoroutineHandle<?>>> activeHandles = ConcurrentHashMap.newKeySet();

    private Dispatcher(ExecutorService executor, String name) {
        this.executor = executor;
        this.name = name;
    }

    // 🧩 Section: execution

    /**
     * Run an asynchronous operation on this dispatcher.
     *
     * @param block a suspendable function to run
     * @param <T>   the result type (nullable)
     * @return a {@link JCoroutineHandle} representing the running task
     * @throws IllegalArgumentException if {@code block} is null
     */
    public <T extends @Nullable Object> JCoroutineHandle<T> async(SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");

        CompletableFuture<T> cf = new CompletableFuture<>();
        CancellationToken token = new CancellationTokenImpl();

        // 🧩 Point: execution/construct-task
        Runnable task = () -> {
            try (var scope = new JCoroutineScopeImpl("dispatcher-" + name)) {
                var suspend = SuspendContext.create(scope, token);
                T result = block.apply(suspend);   // T may be null
                cf.complete(result);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cf.completeExceptionally(new CancellationException("Interrupted"));
            } catch (Exception e) {
                cf.completeExceptionally(e);
            }
        };

        // 🧩 Point: execution/submit-task
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rex) {
            // Complete the future immediately on rejection - don't hold onto the task
            cf.completeExceptionally(new CancellationException(
                    "Dispatcher '" + name + "' rejected task (probably shut down)"
            ));
        }

        var handle = new JCoroutineHandleImpl<>(cf, token);
        WeakReference<JCoroutineHandle<?>> weakRef = new WeakReference<>(handle);
        activeHandles.add(weakRef);

        // 🧩 Point: execution/cleanup-handle
        handle.result().whenComplete((r, t) -> {
            activeHandles.removeIf(ref -> ref.get() == handle || ref.get() == null);
        });

        return handle;
    }

    /**
     * Launch a fire-and-forget operation on this dispatcher, returning a handle so it can be cancelled or observed.
     *
     * @param block a suspendable runnable to run
     * @return a {@link JCoroutineHandle} representing the running task
     * @throws IllegalArgumentException if {@code block} is null
     */
    public JCoroutineHandle<Void> launch(SuspendRunnable block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");

        CompletableFuture<Void> cf = new CompletableFuture<>();
        CancellationToken token = new CancellationTokenImpl();

        // 🧩 Point: execution/construct-launch-task
        Runnable task = () -> {
            try (var scope = new JCoroutineScopeImpl("dispatcher-" + name)) {
                var suspend = SuspendContext.create(scope, token);
                block.run(suspend);
                @SuppressWarnings("nullness")
                Void result = null;
                cf.complete(result);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cf.completeExceptionally(new CancellationException("Interrupted"));
            } catch (Exception e) {
                cf.completeExceptionally(e);
            }
        };

        // 🧩 Point: execution/submit-launch-task
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rex) {
            cf.completeExceptionally(new CancellationException(
                    "Dispatcher '" + name + "' rejected task (probably shut down)"
            ));
        }

        var handle = new JCoroutineHandleImpl<>(cf, token);
        WeakReference<JCoroutineHandle<?>> weakRef = new WeakReference<>(handle);
        activeHandles.add(weakRef);

        // 🧩 Point: execution/cleanup-launch-handle
        handle.result().whenComplete((r, t) -> {
            activeHandles.removeIf(ref -> ref.get() == handle || ref.get() == null);
        });

        return handle;
    }
    // [/🧩 Section: execution]

    // 🧩 Section: lifecycle

    /**
     * Gracefully shuts down the underlying executor.
     * <p>Waits up to 5 seconds for tasks to complete, then forces shutdown if needed.</p>
     */
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    /**
     * Immediately shuts down this dispatcher and cancels all active tasks.
     * <p>Cancels all {@link JCoroutineHandle}s, clears internal tracking, and shuts down the executor now.</p>
     */
    public void kill() {
        // 🧩 Point: lifecycle/cancel-handles
        Iterator<WeakReference<JCoroutineHandle<?>>> it = activeHandles.iterator();
        while (it.hasNext()) {
            WeakReference<JCoroutineHandle<?>> ref = it.next();
            JCoroutineHandle<?> handle = ref.get();
            if (handle != null) {
                handle.cancel();
            } else {
                it.remove(); // Clean up dead references
            }
        }
        activeHandles.clear();

        // 🧩 Point: lifecycle/shutdown-now
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
    // [/🧩 Section: lifecycle]

    // 🧩 Section: info

    /**
     * Returns the underlying {@link ExecutorService}.
     *
     * @return the executor service backing this dispatcher
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * Returns the name of this dispatcher.
     *
     * @return the dispatcher name
     */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Dispatcher(" + name + ")";
    }
    // [/🧩 Section: info]

    // 🧩 Section: factories

    /**
     * Creates a new dispatcher wrapping a given executor.
     *
     * @param executor the executor service to wrap (non-null)
     * @param name     a human-readable name for this dispatcher (non-null)
     * @return a new dispatcher instance
     * @throws IllegalArgumentException if {@code executor} or {@code name} is null
     */
    public static Dispatcher create(ExecutorService executor, String name) {
        if (executor == null || name == null) {
            throw new IllegalArgumentException("Executor and name cannot be null");
        }
        return new Dispatcher(executor, name);
    }

    /**
     * Dispatcher using virtual threads (good for I/O-bound work).
     *
     * @return a dispatcher backed by {@link Executors#newVirtualThreadPerTaskExecutor()}
     */
    public static Dispatcher virtualThreads() {
        return create(
                Executors.newVirtualThreadPerTaskExecutor(),
                "VirtualThreads"
        );
    }

    /**
     * Dispatcher using the common {@link ForkJoinPool} (CPU-bound work).
     *
     * @return a dispatcher backed by {@link ForkJoinPool#commonPool()}
     */
    public static Dispatcher cpu() {
        return create(
                ForkJoinPool.commonPool(),
                "CPU"
        );
    }

    /**
     * Dispatcher using a fixed-size thread pool.
     *
     * @param nThreads number of threads in the pool
     * @return a dispatcher backed by {@link Executors#newFixedThreadPool(int)}
     */
    public static Dispatcher fixedThreadPool(int nThreads) {
        return create(
                Executors.newFixedThreadPool(nThreads),
                "FixedPool-" + nThreads
        );
    }

    /**
     * Dispatcher using a cached thread pool.
     *
     * @return a dispatcher backed by {@link Executors#newCachedThreadPool()}
     */
    public static Dispatcher cachedThreadPool() {
        return create(
                Executors.newCachedThreadPool(),
                "CachedPool"
        );
    }

    /**
     * Single-threaded dispatcher (useful for serializing operations).
     *
     * @return a dispatcher backed by {@link Executors#newSingleThreadExecutor()}
     */
    public static Dispatcher singleThread() {
        return create(
                Executors.newSingleThreadExecutor(),
                "SingleThread"
        );
    }
    // [/🧩 Section: factories]
}
