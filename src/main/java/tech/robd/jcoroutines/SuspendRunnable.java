/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/SuspendRunnable.java
 description: Functional interface representing a suspending runnable that executes with a SuspendContext.
 license: Apache-2.0
 editable: yes
 structured: yes
 tags: [robokeytags,v1]
 author: Rob Deas
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

/**
 * A runnable-like functional interface whose body executes with an explicit {@link SuspendContext}.
 * <p>
 * This is the Java interop shape for a Kotlin {@code suspend () -> Unit}-style block:
 * the context is passed explicitly to enable cooperative cancellation, delay, and
 * dispatcher access from Java without compiler magic.
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * SuspendRunnable task = suspend -> {
 *     suspend.delay(50);
 *     // do work, throw checked exceptions if needed
 * };
 * }</pre>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
@FunctionalInterface
public interface SuspendRunnable {

    // [🧩 Section: api]

    /**
     * Executes this runnable within the provided suspending {@code suspend} context.
     *
     * @param suspend the non-null {@link SuspendContext} providing cancellation,
     *                delay, and dispatcher services
     * @throws Exception if the task fails for any reason. Implementations may
     *                   throw {@link java.util.concurrent.CancellationException} to signal cancellation.
     */
    void run(@NonNull SuspendContext suspend) throws Exception;
    // [/🧩 Section: api]
}
