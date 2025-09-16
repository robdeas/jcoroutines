/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/JCoroutineScope.java
 description: Scope interface for structured concurrency on the JVM with explicit SuspendContext handoff and Java interop helpers.
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
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A scope for structured concurrency operations.
 * <p>
 * A {@code JCoroutineScope} owns an {@link Executor} and provides entry points to launch
 * suspending work expressed as {@link SuspendRunnable} / {@link SuspendFunction} lambdas.
 * All work launched through the scope inherits cancellation when the scope is {@link #close() closed}.
 * </p>
 *
 * <h2>Key principles</h2>
 * <ul>
 *   <li><strong>Structured:</strong> child operations are associated with the scope and can be
 *   cancelled together.</li>
 *   <li><strong>Explicit context:</strong> suspendable code receives a {@link SuspendContext}
 *   argument that exposes cancellation and timing primitives.</li>
 *   <li><strong>Interop:</strong> Java callers get {@link CompletableFuture}-based handles via
 *   {@link JCoroutineHandle#result()} and can use {@link #runBlocking(SuspendFunction)} to bridge
 *   into blocking code paths when needed.</li>
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> Implementations are expected to be thread-safe; cancellation
 * signals should propagate promptly to child operations.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public interface JCoroutineScope extends AutoCloseable {

    // 🧩 Section: api

    /**
     * Launch an async operation that returns a handle for cancellation control.
     *
     * @param block the suspend function to execute
     * @param <T>   result type
     * @return handle that can be used to cancel or await the operation
     */
    <T> JCoroutineHandle<T> async(SuspendFunction<T> block);

    /**
     * Launch a fire-and-forget operation that returns a handle for cancellation control.
     *
     * @param block the suspend runnable to execute
     * @return handle that can be used to cancel the operation
     */
    JCoroutineHandle<Void> launch(SuspendRunnable block);

    /**
     * Run a block and block the current thread for its result within this scope.
     * This is the main entry point from synchronous Java code.
     *
     * @param block the suspend function to execute
     * @param <T>   nullable result type
     * @return the result (possibly {@code null})
     * @throws java.util.concurrent.CancellationException if the scope or operation was cancelled
     */
    <T extends @Nullable Object> T runBlocking(SuspendFunction<T> block);

    /**
     * Convenience: run the block on a fresh Virtual-Thread-per-task executor,
     * but block the caller until completion.
     *
     * @param block the suspend function to execute
     * @param <T>   nullable result type
     * @return the result (possibly {@code null})
     * @see #runBlocking(Executor, SuspendFunction)
     */
    default <T extends @org.jspecify.annotations.Nullable Object> T runBlockingVT(
            @NonNull SuspendFunction<T> block
    ) {
        // default forwards to the executor variant using a fresh VT
        return runBlocking(Executors.newVirtualThreadPerTaskExecutor(), block);
    }

    /**
     * General form: run the block on the given {@code executor}, but block the caller for the result.
     *
     * @param executor target executor to use for the work
     * @param block    the suspend function to execute
     * @param <T>      nullable result type
     * @return the result (possibly {@code null})
     * @throws java.util.concurrent.CancellationException if the scope or operation was cancelled
     */
    <T extends @org.jspecify.annotations.Nullable Object> T runBlocking(
            @NonNull Executor executor,
            @NonNull SuspendFunction<T> block
    );

    /**
     * Convenience method for async operations when you don't need cancellation control.
     * Returns a {@link CompletableFuture} directly for compatibility with existing async code.
     *
     * @param block suspend function to run
     * @param <T>   result type
     * @return a future completing with the function's result
     */
    default <T> CompletableFuture<T> asyncAndForget(SuspendFunction<T> block) {
        return async(block).result();
    }

    /**
     * Convenience method for fire-and-forget launch when you don't need cancellation control.
     * Equivalent to calling {@link #launch(SuspendRunnable)} and ignoring the returned handle.
     *
     * @param block suspend runnable to run
     */
    default void launchAndForget(SuspendRunnable block) {
        launch(block);
    }

    /**
     * Get the underlying executor for this scope.
     * Useful for advanced use cases or bridging with other async libraries.
     *
     * @return the scope's executor
     */
    Executor executor();

    /**
     * Close the scope, cancelling all active operations and cleaning up resources.
     * Implementations may be no-op if they don't own resources.
     */
    @Override
    void close();

    // [/🧩 Section: api]
}
