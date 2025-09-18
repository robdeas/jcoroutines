/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/Coroutines.java
 description: Static convenience entry points for coroutine-style usage in Java.
              Provides global scopes (VT and CPU), runBlocking variants, async/launch,
              and graceful shutdown of shared executors.
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

package tech.robd.jcoroutines;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.JCoroutineScopeImpl;

import java.util.concurrent.*;

/**
 * Static convenience API for running coroutine-style tasks from plain Java.
 *
 * <p>Provides:
 * <ul>
 *   <li>Global virtual-thread scope ({@link #GLOBAL_SCOPE}) for I/O-bound work.</li>
 *   <li>Global CPU scope ({@link #GLOBAL_CPU_SCOPE}) for CPU-bound work.</li>
 *   <li>{@code runBlocking*} helpers when you need to block the caller.</li>
 *   <li>{@code async}/{@code launch} helpers that return {@link JCoroutineHandle}.</li>
 *   <li>Graceful {@link #shutdown()} of shared executors/scopes.</li>
 * </ul>
 *
 * <p>Notes:
 * <ul>
 *   <li>This class is optional sugar over {@code JCoroutineScope}. For finer control,
 *       create and manage your own scopes.</li>
 *   <li>{@code GLOBAL_CPU_SCOPE} assumes a CPU-optimized scope implementation
 *       (e.g., {@code StandardCoroutineScope}) configured for a CPU pool.</li>
 * </ul>
 */
public final class Coroutines {

    // [🧩 Section: executors]
    /**
     * Shared virtual-thread-per-task executor (used by global VT scope, shutdown on {@link #shutdown()}).
     */
    private static final @NonNull ExecutorService SHARED_VT =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Shared CPU pool (used by CPU-oriented scope, shutdown on {@link #shutdown()}).
     */
    private static final @NonNull ExecutorService CPU_POOL =
            new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    // [/🧩 Section: executors]

    private Coroutines() {
    }

    // [🧩 Section: global-scopes]
    /**
     * Global VT scope for I/O-bound work.
     */
    private static final JCoroutineScope GLOBAL_SCOPE = new JCoroutineScopeImpl("global");

    /**
     * Global CPU scope for CPU-bound work (configured to use {@link #CPU_POOL}).
     */
    private static final JCoroutineScope GLOBAL_CPU_SCOPE = new StandardCoroutineScope(); // Configure with CPU executor
    // [/🧩 Section: global-scopes]

    // [🧩 Section: factories]

    /**
     * Create a new root scope owned by the caller.
     *
     * <p>Call {@link JCoroutineScope#close()} when done to cancel child tasks
     * and shut down any associated executors.</p>
     */
    public static JCoroutineScope newScope() {
        return new StandardCoroutineScope();
    }
    // [/🧩 Section: factories]

    // [🧩 Section: runBlocking]

    /**
     * Run a suspend block on a CPU-oriented scope and return its result, blocking the caller.
     *
     * <p>Main entry point from synchronous Java code when you want CPU-bound execution.</p>
     */
    public static <T extends @Nullable Object> T runBlockingCpu(@NonNull SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");
        try (var scope = new JCoroutineScopeImpl()) {
            return scope.runBlocking(block);
        }
    }

    /**
     * Run a suspend block on a virtual-thread-oriented scope and block until completion.
     */
    public static <T> T runBlockingVT(SuspendFunction<T> block) throws Exception {
        return async(block).join(); // Delegate to existing async + join
    }

    /**
     * Alias to {@link #runBlockingCpu(SuspendFunction)} for convenience.
     */
    public static <T> T runBlocking(SuspendFunction<T> block) throws Exception {
        return runBlockingCpu(block);
    }

    /**
     * Alias to {@link #runBlockingVT(SuspendFunction)} for convenience.
     */
    public static <T> T runBlockingIO(SuspendFunction<T> block) throws Exception {
        return runBlockingVT(block);
    }
    // [/🧩 Section: runBlocking]

    // [🧩 Section: async-launch (VT/global)]

    /**
     * Start an async operation on the global VT scope and return a handle for cancellation/await.
     *
     * <p>Good default for I/O-bound work.</p>
     */
    public static <T extends @Nullable Object> JCoroutineHandle<T> async(@NonNull SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");
        return GLOBAL_SCOPE.async(block);
    }

    /**
     * Launch a fire-and-forget task on the global VT scope (still returns a handle for control).
     */
    public static JCoroutineHandle<Void> launch(@NonNull SuspendRunnable block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");
        return GLOBAL_SCOPE.launch(block);
    }
    // [/🧩 Section: async-launch (VT/global)]

    // [🧩 Section: async-launch (CPU/global)]

    /**
     * Start a CPU-oriented async operation on the global CPU scope.
     */
    public static <T extends @Nullable Object> JCoroutineHandle<T> asyncCpu(@NonNull SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");
        return GLOBAL_CPU_SCOPE.async(block);
    }

    /**
     * Package-visible helper used by {@link #launchCpuAndForget(SuspendRunnable)}.
     */
    static JCoroutineHandle<Void> launchCpu(@NonNull SuspendRunnable block) {
        if (block == null) throw new IllegalArgumentException("Block cannot be null");
        return GLOBAL_CPU_SCOPE.launch(block);
    }
    // [/🧩 Section: async-launch (CPU/global)]

    // [🧩 Section: convenience (CompletableFuture + fire-and-forget)]

    /**
     * Async (VT) returning a {@link CompletableFuture} for compatibility.
     */
    public static <T extends @Nullable Object> CompletableFuture<T> asyncAndForget(@NonNull SuspendFunction<T> block) {
        return async(block).result();
    }

    /**
     * Fire-and-forget launch on VT scope, ignoring the handle.
     */
    public static void launchAndForget(@NonNull SuspendRunnable block) {
        launch(block);
    }

    /**
     * Async (CPU) returning a {@link CompletableFuture} for compatibility.
     */
    public static <T extends @Nullable Object> CompletableFuture<T> asyncCpuAndForget(@NonNull SuspendFunction<T> block) {
        return asyncCpu(block).result();
    }

    /**
     * Fire-and-forget launch on the CPU scope, ignoring the handle.
     */
    public static void launchCpuAndForget(@NonNull SuspendRunnable block) {
        launchCpu(block);
    }
    // [/🧩 Section: convenience (CompletableFuture + fire-and-forget)]

    // [🧩 Section: shutdown]

    /**
     * Gracefully shut down global scopes and shared executors.
     *
     * <p>Call this during application shutdown. The JVM will eventually clean up executors,
     * but explicit shutdown helps tests and long-running services exit promptly.</p>
     */
    public static void shutdown() {
        // Close scopes first (cancels tasks), then stop executors.
        GLOBAL_SCOPE.close();

        SHARED_VT.shutdown();
        CPU_POOL.shutdown();

        try {
            if (!SHARED_VT.awaitTermination(5, TimeUnit.SECONDS)) {
                SHARED_VT.shutdownNow();
            }
            if (!CPU_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                CPU_POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            SHARED_VT.shutdownNow();
            CPU_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    // [/🧩 Section: shutdown]
}
