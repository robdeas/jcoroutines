/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/SuspendConsumer.java
 description: Functional interface for a suspending consumer of a non-null item with explicit SuspendContext.
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

/**
 * A consumer-like functional interface whose body executes with an explicit {@link SuspendContext}
 * and accepts a non-null item.
 * <p>
 * This is the Java interop shape for a Kotlin {@code suspend (T) -> Unit}-style consumer.
 * </p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * SuspendConsumer<Message> handler = (suspend, msg) -> {
 *     suspend.delay(5);
 *     // process msg (non-null)
 * };
 * }</pre>
 *
 * @param <T> the non-null item type consumed by this operation
 * @author Rob Deas
 * @since 0.1.0
 */
@FunctionalInterface
public interface SuspendConsumer<T> {

    // [🧩 Section: api]

    /**
     * Consumes {@code item} within the provided suspending {@code suspend} context.
     *
     * @param suspend the non-null {@link SuspendContext}
     * @param item    the non-null item to be consumed
     * @throws Exception if consumption fails for any reason. Implementations may
     *                   throw {@link java.util.concurrent.CancellationException} to signal cancellation.
     */
    void accept(@NonNull SuspendContext suspend, @NonNull T item) throws Exception;
    // [/🧩 Section: api]
}
