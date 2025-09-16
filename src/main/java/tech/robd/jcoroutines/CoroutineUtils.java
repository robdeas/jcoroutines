/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/CoroutineUtils.java
 description: Utility methods for coroutine lifecycle management, cancellation,
              batch operations, racing, and debug summaries.
 license: Apache-2.0
 author: Rob Deas
 generator: none
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

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Utility methods for working with {@link JCoroutineHandle} collections:
 * <ul>
 *   <li>Cancellation of single or multiple handles</li>
 *   <li>Status checks (all completed, any active, counts)</li>
 *   <li>Waiting with timeouts, racing handles</li>
 *   <li>Scoped resource cleanup helpers</li>
 *   <li>Debug summaries</li>
 * </ul>
 */
public final class CoroutineUtils {
    private CoroutineUtils() {
    }

    // 🧩 Section: cancellation

    /**
     * Cancel handle if not null. @return true if cancellation requested.
     */
    public static boolean cancelIfPresent(@Nullable JCoroutineHandle<?> handle) {
        return handle != null && handle.cancel();
    }

    /**
     * Cancel all handles in a collection.
     *
     * <p><b>Example</b> — best-effort shutdown of a batch:</p>
     * <pre>{@code
     * List<JCoroutineHandle<?>> batch = List.of(h1, h2, h3);
     * int cancelled = CoroutineUtils.cancelAll(batch);
     * System.out.println("Cancelled " + cancelled + " tasks");
     * }</pre>
     */
    public static int cancelAll(@NonNull Collection<? extends JCoroutineHandle<?>> handles) {
        if (handles == null) throw new IllegalArgumentException("handles == null");
        int cancelled = 0;
        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null && coroutineHandle.cancel()) cancelled++;
        }
        return cancelled;
    }

    /**
     * Cancel all handles in varargs form.
     */
    @SafeVarargs
    public static int cancelAll(@NonNull JCoroutineHandle<?>... handles) {
        if (handles == null) throw new IllegalArgumentException("handles == null");
        int cancelled = 0;
        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null && coroutineHandle.cancel()) cancelled++;
        }
        return cancelled;
    }
    // [/🧩 Section: cancellation]

    // 🧩 Section: status

    /**
     * @return true if all handles are completed.
     */
    public static boolean allCompleted(@NonNull Collection<? extends JCoroutineHandle<?>> handles) {
        if (handles == null) throw new IllegalArgumentException("handles == null");
        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null && !coroutineHandle.isCompleted()) return false;
        }
        return true;
    }

    /**
     * @return true if any handle is still active.
     */
    public static boolean anyActive(@NonNull Collection<? extends JCoroutineHandle<?>> handles) {
        if (handles == null) throw new IllegalArgumentException("handles == null");
        for (JCoroutineHandle<?> h : handles) {
            if (h != null && h.isActive()) return true;
        }
        return false;
    }

    /**
     * @return count of active handles.
     */
    public static int countActive(@NonNull Collection<? extends JCoroutineHandle<?>> handles) {
        if (handles == null) throw new IllegalArgumentException("handles == null");
        int active = 0;
        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null && coroutineHandle.isActive()) active++;
        }
        return active;
    }
    // [/🧩 Section: status]

    // 🧩 Section: waiting

    /**
     * Block until all handles complete or timeout expires.
     *
     * <p><b>Example</b> — wait up to 2s for a batch to finish:</p>
     * <pre>{@code
     * try (var scope = new StandardCoroutineScope()) {
     *     var h1 = scope.async(s -> fetchA(s));
     *     var h2 = scope.async(s -> fetchB(s));
     *     CoroutineUtils.awaitAllWithTimeout(List.of(h1, h2), Duration.ofSeconds(2));
     * } catch (TimeoutException te) {
     *     // cancel remaining and move on
     * }
     * }</pre>
     *
     * @throws TimeoutException if any handle does not complete in time
     */
    public static void awaitAllWithTimeout(@NonNull Collection<? extends JCoroutineHandle<?>> handles,
                                           @NonNull Duration timeout) throws Exception {
        if (handles == null || timeout == null) throw new IllegalArgumentException("args == null");

        long timeoutMillis = timeout.toMillis();
        long start = System.currentTimeMillis();

        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null) {
                long elapsed = System.currentTimeMillis() - start;
                long remaining = timeoutMillis - elapsed;
                if (remaining <= 0) throw new TimeoutException("Timeout waiting for handles");
                coroutineHandle.result().get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Wait for the first handle to complete within timeout.
     *
     * <p><b>Example</b> — race two alternatives and see who finishes first:</p>
     * <pre>{@code
     * var i = CoroutineUtils.awaitFirstCompleted(List.of(hFast, hAccurate), Duration.ofSeconds(1));
     * if (i >= 0) {
     *     System.out.println("First completed index: " + i);
     * } else {
     *     System.out.println("No completion within timeout");
     * }
     * }</pre>
     *
     * @return index of completed handle, or -1 if none within timeout
     */
    public static int awaitFirstCompleted(@NonNull List<? extends JCoroutineHandle<?>> handles,
                                          @NonNull Duration timeout) {
        if (handles == null || timeout == null) throw new IllegalArgumentException("args == null");

        CompletableFuture<?>[] futures = handles.stream()
                .map(coroutineHandle -> coroutineHandle != null ? coroutineHandle.result() : CompletableFuture.completedFuture(null))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.anyOf(futures).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (int i = 0; i < handles.size(); i++) {
                JCoroutineHandle<?> handle = handles.get(i);
                if (handle != null && handle.isCompleted()) return i;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
    // [/🧩 Section: waiting]

    // 🧩 Section: resource-management

    /**
     * Execute a block, then cancel all handles afterwards (even on exception).
     *
     * <p><b>Example</b> — ensure cleanup after producing work:</p>
     * <pre>{@code
     * try (var scope = new StandardCoroutineScope()) {
     *     var a = scope.async(s -> taskA(s));
     *     var b = scope.async(s -> taskB(s));
     *     String result = CoroutineUtils.withHandles(ctx -> {
     *         // do something while A/B run
     *         return "done";
     *     }, SuspendContext.create(scope, CancellationToken.none()), a, b);
     *     // a/b cancelled here regardless of success/failure
     * }
     * }</pre>
     */
    public static <T> T withHandles(@NonNull SuspendFunction<T> block,
                                    @NonNull SuspendContext context,
                                    @NonNull JCoroutineHandle<?>... handles) throws Exception {
        if (block == null || context == null || handles == null) throw new IllegalArgumentException("args == null");
        try {
            return block.apply(context);
        } finally {
            cancelAll(handles);
        }
    }

    /**
     * Race multiple handles: return the first to complete, cancel the rest.
     *
     * <p><b>Example</b> — speculative execution:</p>
     * <pre>{@code
     * var h1 = scope.async(s -> doWorkA(s));
     * var h2 = scope.async(s -> doWorkB(s));
     * var winner = CoroutineUtils.raceHandles(List.of(h1, h2));
     * System.out.println("Winner: " + winner.result().join());
     * }</pre>
     */
    public static <T> JCoroutineHandle<T> raceHandles(@NonNull List<JCoroutineHandle<T>> handles) {
        if (handles == null || handles.isEmpty()) throw new IllegalArgumentException("handles empty");

        CompletableFuture<?>[] futures = handles.stream()
                .map(JCoroutineHandle::result)
                .toArray(CompletableFuture[]::new);

        CompletableFuture.anyOf(futures).join();

        JCoroutineHandle<T> winner = null;
        for (JCoroutineHandle<T> coroutineHandle : handles) {
            if (coroutineHandle.isCompleted() && winner == null) {
                winner = coroutineHandle;
            } else if (!coroutineHandle.isCompleted()) {
                coroutineHandle.cancel();
            }
        }
        if (winner == null) throw new IllegalStateException("No handle completed");
        return winner;
    }
    // [/🧩 Section: resource-management]

    // 🧩 Section: debug

    /**
     * Return a human-readable summary of handle states.
     */
    public static String getHandlesSummary(@NonNull Collection<? extends JCoroutineHandle<?>> handles) {
        if (handles == null) return "null";
        int total = 0, active = 0, completed = 0;
        for (JCoroutineHandle<?> coroutineHandle : handles) {
            if (coroutineHandle != null) {
                total++;
                if (coroutineHandle.isActive()) active++;
                if (coroutineHandle.isCompleted()) completed++;
            }
        }
        return String.format("Handles[total=%d, active=%d, completed=%d]", total, active, completed);
    }
    // [/🧩 Section: debug]
}
