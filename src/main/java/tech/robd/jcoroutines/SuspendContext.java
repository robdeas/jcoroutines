// SuspendContext.java - Complete implementation with handle-based API
/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/SuspendContext.java
 description: Explicit suspending execution context providing cancellation, delay/yield, and async helpers.
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
import tech.robd.jcoroutines.diagnostics.Diagnostics;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.CancellationTokenImpl;
import tech.robd.jcoroutines.internal.JCoroutineHandleImpl;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Explicit context passed to suspend-style functions.
 * <p>
 * A {@code SuspendContext} exposes cooperative-cancellation via a {@link CancellationToken},
 * light-weight timing primitives ({@link #delay(long)}, {@link #yield()}, {@link #yieldAndPause(Duration, boolean)}),
 * and helpers for awaiting/combining {@link java.util.concurrent.CompletableFuture}s as well as
 * launching child operations that inherit cancellation from their parent.
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * JCoroutineScope scope = ...;
 * SuspendContext root = SuspendContext.create(scope);
 * String v = root.withTimeout(Duration.ofSeconds(1), s -> {
 *     s.delay(10);
 *     return "value";
 * });
 * }</pre>
 */
public final class SuspendContext {

    private static final Diagnostics DIAG = Diagnostics.of(SuspendContext.class);
    public static final String COROUTINE_WAS_CANCELLED = "Coroutine was cancelled";
    private final @NonNull CancellationToken cancellationToken;
    private final @NonNull JCoroutineScope scope;

    private SuspendContext(@NonNull JCoroutineScope scope, CancellationToken token) {
        this.scope = scope;
        this.cancellationToken = token;
    }

    /**
     * Create a new {@code SuspendContext} with its own root {@link CancellationToken}.
     * Use this when starting a new logical coroutine scope.
     *
     * @param scope the owning {@link JCoroutineScope}
     * @return a new context with a fresh root token
     */
    public static @NonNull SuspendContext create(@NonNull JCoroutineScope scope) {
        return new SuspendContext(scope, new CancellationTokenImpl());
    }

    /**
     * Create a {@code SuspendContext} that reuses an existing {@link CancellationToken}.
     * Establishes a parent→child relationship so that cancellation in the parent propagates.
     *
     * @param scope the owning {@link JCoroutineScope}
     * @param token the existing token to attach
     * @return a context sharing the provided token
     */
    public static @NonNull SuspendContext create(@NonNull JCoroutineScope scope, CancellationToken token) {
        return new SuspendContext(scope, token);
    }

    /**
     * Throws {@link java.util.concurrent.CancellationException} if this context has been cancelled.
     * <p>
     * <p>
     * Call at cooperative suspension points.
     *
     * @throws java.util.concurrent.CancellationException if cancelled
     */


    public void checkCancellation() {
        if (cancellationToken.isCancelled()) {
            throw new CancellationException(COROUTINE_WAS_CANCELLED);
        }
    }

    /**
     * Returns the {@link CancellationToken} backing this context.
     */


    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * Creates a child context that inherits cancellation from this context.
     * <p>
     * <p>
     * Equivalent to {@code new SuspendContext(scope, cancellationToken.child())}.
     *
     * @return a child context linked to this context's token
     */


    public SuspendContext child() {
        return new SuspendContext(scope, cancellationToken.child());
    }

    @Override


    /** @return a diagnostic string including thread and cancellation state. */


    public String toString() {
        return "SuspendContext{" +
                "thread=" + Thread.currentThread().getName() +
                ", cancelled=" + cancellationToken.isCancelled() +
                ", tokenId=" + cancellationToken.hashCode() +
                '}';
    }


    /**
     * Delay by millis; reacts to interruption as cancellation.
     * Fixed to handle spurious wakeups properly.
     */
    public void delay(long millis) {
        checkCancellation();
        // Interrupt the sleep on cancel, then remove the hook
        Thread current = Thread.currentThread();
        try (AutoCloseable reg = cancellationToken.onCancel(current::interrupt)) {
            long remaining = Math.max(0L, millis);
            final long SLICE_MS = 10L;
            while (remaining > 0L) {
                checkCancellation();
                long sleep = Math.min(SLICE_MS, remaining);

                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted");
                }

                remaining -= sleep;
            }
        } catch (Exception ignored) {
            // Handle reg.close() exceptions
        }
    }

    /**
     * Yield execution to allow other coroutines to run.
     * This is a cooperative scheduling point that checks for cancellation
     * and gives other coroutines a chance to execute.
     * <p>
     * Use this between operations in long-running functions to ensure
     * fair scheduling and responsive cancellation.
     */
    public void yield() {
        // Always check cancellation when yielding
        if (cancellationToken.isCancelled()) {
            throw new CancellationException(COROUTINE_WAS_CANCELLED);
        }
        if (Thread.currentThread().isVirtual()) {
            // Use a very short delay to yield the thread
            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted during yield");
            }
        }
    }

    /**
     * Convenience overload of {@link #yieldAndPause(Duration, boolean)} using 1s duration.
     */
    public void yieldAndPause(boolean checkChannelsFrequently) {
        yieldAndPause(Duration.ofSeconds(1), checkChannelsFrequently);
    }

    /**
     * Convenience overload of {@link #yieldAndPause(Duration, boolean)} using 1s duration and infrequent checks.
     */
    public void yieldAndPause() {
        yieldAndPause(Duration.ofSeconds(1), false);
    }

    /**
     * Convenience overload delegating to {@link #yieldAndPause(Duration, boolean)}.
     */
    public void yieldAndPause(Duration duration) {
        yieldAndPause(duration, false);
    }

    public void yieldAndPause(Duration duration, boolean checkChannelsFrequently) {
        // Input validation
        if (duration == null) {
            throw new IllegalArgumentException("Duration cannot be null");
        }
        if (duration.isNegative()) {
            DIAG.warn("yieldAndPause: negative duration provided, treating as zero");
            return;
        }
        if (duration.isZero()) {
            DIAG.debug("yieldAndPause: zero duration, returning immediately");
            return;
        }

        DIAG.debug("yieldAndPause: entry - cancelled={}, duration={}ms, thread={}",
                cancellationToken.isCancelled(), duration.toMillis(), Thread.currentThread().getName());

        if (cancellationToken.isCancelled()) {
            DIAG.debug("yieldAndPause: already cancelled on entry");
            throw new CancellationException(COROUTINE_WAS_CANCELLED);
        }

        if (!Thread.currentThread().isVirtual()) {
            DIAG.debug("yieldAndPause: not virtual thread, returning immediately");
            return;
        }

        Thread current = Thread.currentThread();
        AutoCloseable reg = null;
        boolean registrationSucceeded = false;

        try {
            DIAG.debug("yieldAndPause: attempting to register cancellation callback");
            reg = cancellationToken.onCancel(current::interrupt);
            registrationSucceeded = true;
            DIAG.debug("yieldAndPause: cancellation callback registered successfully");

            long totalMillis = duration.toMillis();
            long sliceSize = checkChannelsFrequently ? 10 : Math.min(200, totalMillis);
            long remaining = totalMillis;
            long startTime = System.currentTimeMillis();

            // Safety: prevent infinite loops with very large durations
            if (totalMillis > 300_000) { // 5 minutes
                DIAG.warn("yieldAndPause: very long duration {}ms, consider using shorter intervals", totalMillis);
            }

            DIAG.debug("yieldAndPause: entering sleep loop - total={}ms, slice={}ms", totalMillis, sliceSize);

            while (remaining > 0) {
                // Check for cancellation before each sleep
                if (cancellationToken.isCancelled()) {
                    DIAG.debug("yieldAndPause: cancellation detected in loop, remaining={}ms", remaining);
                    throw new CancellationException(COROUTINE_WAS_CANCELLED);
                }

                long sleepTime = Math.min(remaining, sliceSize);

                try {
                    Thread.sleep(sleepTime);
                    remaining -= sleepTime;

                    // Safety: detect if we're taking much longer than expected (clock issues, GC pauses, etc.)
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed > totalMillis * 2) {
                        DIAG.warn("yieldAndPause: operation taking much longer than expected - elapsed={}ms, expected={}ms",
                                elapsed, totalMillis);
                    }

                    DIAG.debug("yieldAndPause: slept {}ms, remaining={}ms", sleepTime, remaining);

                } catch (InterruptedException ie) {
                    DIAG.debug("yieldAndPause: sleep interrupted, remaining={}ms", remaining);
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted during pause");
                }
            }

            DIAG.debug("yieldAndPause: sleep loop completed normally after {}ms", totalMillis);

        } catch (CancellationException ce) {
            DIAG.debug("yieldAndPause: cancellation exception - {}", ce.getMessage());
            throw ce; // Always re-throw cancellation
        } catch (RuntimeException re) {
            DIAG.error("yieldAndPause: unexpected runtime exception - {}: {}",
                    re.getClass().getSimpleName(), re.getMessage());
            throw re; // Re-throw runtime exceptions as they indicate programming errors
        } catch (Exception e) {
            // Only checked exceptions from registration/cleanup should reach here
            if (registrationSucceeded) {
                DIAG.warn("yieldAndPause: exception during resource cleanup - {}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            } else {
                DIAG.error("yieldAndPause: failed to register cancellation callback - {}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
                // If we can't register for cancellation, still do the sleep but warn about it
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted during pause");
                }
            }
        } finally {
            // Ensure registration is always cleaned up
            if (reg != null) {
                try {
                    reg.close();
                    DIAG.debug("yieldAndPause: cancellation registration cleaned up");
                } catch (Exception e) {
                    DIAG.warn("yieldAndPause: failed to clean up cancellation registration - {}: {}",
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }

        DIAG.debug("yieldAndPause: exit");
    }

    /**
     * Delay by Duration.
     */
    public void delay(@NonNull Duration duration) {
        if (duration == null) throw new IllegalArgumentException("Duration cannot be null");
        delay(duration.toMillis());
    }

    /**
     * Await a CompletableFuture with uniform exception semantics.
     */
    public <T extends @Nullable Object> T await(@NonNull CompletableFuture<T> future) {
        if (future == null) {
            throw new IllegalArgumentException("Future cannot be null");
        }

        try {
            return future.join();
        } catch (CompletionException ce) {
            if (ce.getCause() instanceof CancellationException c) throw c;
            throw ce;
        }
    }

    /**
     * Launch async operation as CHILD of this context.
     * This is critical - it must inherit the parent's cancellation.
     */

    /**
     * Wait for all futures to complete and return their results.
     */
    @SafeVarargs
    public final <T extends @Nullable Object> @NonNull List<T> awaitAll(@NonNull CompletableFuture<T>... futures) {
        if (futures == null) throw new IllegalArgumentException("Futures cannot be null");
        if (futures.length == 0) return List.of();

        checkCancellation();

        try {
            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures);
            await(allOf);

            return Arrays.stream(futures)
                    .map(this::await)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Cancel remaining futures on failure
            for (CompletableFuture<T> future : futures) {
                future.cancel(true);
            }
            throw e;
        }
    }

    /**
     * Wait for any future to complete and return its result.
     * Cancels remaining futures.
     */
    @SafeVarargs
    public final <T extends @Nullable Object> T awaitAny(@NonNull CompletableFuture<T>... futures) {
        if (futures == null) throw new IllegalArgumentException("Futures cannot be null");
        if (futures.length == 0) throw new IllegalArgumentException("At least one future required");

        checkCancellation();

        CompletableFuture<Object> anyOf = CompletableFuture.anyOf(futures);

        try {
            await(anyOf);

            // Find the completed future and return its result
            for (CompletableFuture<T> future : futures) {
                if (future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) {
                    T result = await(future);

                    // Cancel remaining futures
                    for (CompletableFuture<T> other : futures) {
                        if (other != future) {
                            other.cancel(true);
                        }
                    }

                    return result;
                }
            }

            throw new IllegalStateException("No future completed successfully");
        } catch (Exception e) {
            // Cancel all futures on failure
            for (CompletableFuture<T> future : futures) {
                future.cancel(true);
            }
            throw e;
        }
    }

    /**
     * Run multiple operations in parallel and collect their results.
     */
    @SafeVarargs
    public final <T extends @Nullable Object> @NonNull List<T> parallel(@NonNull SuspendFunction<T>... blocks) {
        if (blocks == null) throw new IllegalArgumentException("Blocks cannot be null");
        if (blocks.length == 0) return List.of();

        checkCancellation();

        @SuppressWarnings("unchecked")
        CompletableFuture<T>[] futures = new CompletableFuture[blocks.length];

        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null) throw new IllegalArgumentException("Block " + i + " cannot be null");
            futures[i] = async(blocks[i]).result();
        }

        return awaitAll(futures);
    }

    /**
     * Combine two futures with a combiner function.
     */
    public <T extends @Nullable Object, U extends @Nullable Object, R extends @Nullable Object>
    R zip(@NonNull CompletableFuture<T> f1,
          @NonNull CompletableFuture<U> f2,
          @NonNull BiFunction<T, U, R> combiner) {
        if (f1 == null || f2 == null || combiner == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        checkCancellation();

        T result1 = await(f1);
        U result2 = await(f2);
        return combiner.apply(result1, result2);
    }

    /**
     * Execute operation with timeout. Throws CancellationException on timeout.
     */
    public <T extends @Nullable Object> T withTimeout(@NonNull Duration timeout,
                                                      @NonNull SuspendFunction<T> operation) {
        if (timeout == null || operation == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        checkCancellation();

        CompletableFuture<T> future = async(operation).result();

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CancellationException("Operation timed out after " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new CancellationException("Interrupted while waiting");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Execute operation with timeout. Returns null on timeout.
     */
    public <T extends @Nullable Object> @Nullable T withTimeoutOrNull(@NonNull Duration timeout,
                                                                      @NonNull SuspendFunction<T> operation) {
        try {
            return withTimeout(timeout, operation);
        } catch (CancellationException e) {
            return null;
        }
    }

    /**
     * Retry operation with exponential backoff.
     */
    public <T extends @Nullable Object> T retry(int maxAttempts,
                                                @NonNull Duration initialDelay,
                                                double backoffMultiplier,
                                                @NonNull SuspendFunction<T> operation) {
        if (maxAttempts <= 0) throw new IllegalArgumentException("Max attempts must be positive");
        if (initialDelay == null || operation == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        if (backoffMultiplier <= 0) throw new IllegalArgumentException("Backoff multiplier must be positive");

        checkCancellation();

        RuntimeException lastException = null;
        long delayMillis = initialDelay.toMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.apply(this);
            } catch (CancellationException e) {
                throw e; // Don't retry cancellation
            } catch (RuntimeException e) {
                lastException = e;

                if (attempt == maxAttempts) {
                    break; // Don't delay after last attempt
                }

                delay(delayMillis);
                delayMillis = (long) (delayMillis * backoffMultiplier);
            } catch (Exception e) {
                lastException = new RuntimeException("Retry failed", e);

                if (attempt == maxAttempts) {
                    break;
                }

                delay(delayMillis);
                delayMillis = (long) (delayMillis * backoffMultiplier);
            }
        }

        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts", lastException);
    }

    /** Launch async operation returning handle for cancellation control. */
    /**
     * Launch async operation as CHILD of this context.
     * This is critical - it must inherit the parent's cancellation.
     */
    public <T extends @Nullable Object> JCoroutineHandle<T> async(@NonNull SuspendFunction<T> block) {
        if (block == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }

        // Create child token - this ALREADY sets up parent->child cancellation
        CancellationToken childToken = this.cancellationToken.child();

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                SuspendContext childContext = SuspendContext.create(scope, childToken);
                return block.apply(childContext);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, (Executor) scope.executor());

        return new JCoroutineHandleImpl<>(future, childToken);
    }

    /**
     * Launch fire-and-forget operation as CHILD of this context.
     */
    public JCoroutineHandle<Void> launch(@NonNull SuspendRunnable block) {
        if (block == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }

        // Create child token - this ALREADY sets up parent->child cancellation
        CancellationToken childToken = this.cancellationToken.child();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                SuspendContext childContext = SuspendContext.create(scope, childToken);
                block.run(childContext);
            } catch (CancellationException ce) {
                // Expected for cancellation
            } catch (Exception e) {
                System.err.println("Uncaught exception in launched coroutine: " + e.getMessage());
                e.printStackTrace();
            }
        }, (Executor) scope.executor());

        return new JCoroutineHandleImpl<>(future, childToken);
    }

    // [/🧩 Section: api-core]
}