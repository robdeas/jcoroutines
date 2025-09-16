/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/fn/JCoroutineHandle.java
 description: Public handle interface for Java-visible coroutines. Provides cancellation, state
              inspection, and access to result/completion futures.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: no
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

package tech.robd.jcoroutines.fn;

import java.util.concurrent.CompletableFuture;

/**
 * A handle to a running Java-visible coroutine.
 *
 * <p>Supports:
 * <ul>
 *   <li>Cancellation via {@link #cancel()}.</li>
 *   <li>State inspection with {@link #isActive()} and {@link #isCompleted()}.</li>
 *   <li>Result access via {@link #result()} (completes with value or error).</li>
 *   <li>Completion tracking via {@link #completion()} (always completes).</li>
 *   <li>Convenience blocking join via {@link #join()}.</li>
 * </ul>
 *
 * @param <T> the result type of the coroutine (may be {@code Void})
 */
public interface JCoroutineHandle<T> {
    /**
     * Attempt to cancel the coroutine.
     *
     * @return {@code true} if cancellation was requested, {@code false} otherwise
     */
    boolean cancel();

    /**
     * @return {@code true} if the coroutine is still running
     */
    boolean isActive();

    /**
     * @return {@code true} if the coroutine has completed (success, failure, or cancellation)
     */
    boolean isCompleted();

    /**
     * Returns a future representing the actual result of the coroutine.
     *
     * @return future that completes with the result or an exception
     */
    CompletableFuture<T> result();

    /**
     * Returns a future that completes when the coroutine finishes for any reason
     * (success, failure, or cancellation).
     *
     * @return completion future
     */
    CompletableFuture<Void> completion();

    /**
     * Block the calling thread until the coroutine completes, then return its result.
     *
     * @return the coroutine result
     * @throws Exception if the coroutine failed or was cancelled
     */
    default T join() throws Exception {
        return result().get();
    }
}
