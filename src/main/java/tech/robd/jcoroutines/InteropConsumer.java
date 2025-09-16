/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/InteropConsumer.java
 description: Functional interface bridging Kotlin nullability with Java Optional for channel iteration and callbacks.
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

import java.util.Optional;

/**
 * Consumer interface for interop with channels that represent {@code null} as {@link Optional#empty()}.
 * <p>
 * The {@code item} parameter is a non-null {@link Optional}{@code <T>} wrapper:
 * an empty optional corresponds to Kotlin {@code null}. The explicit
 * {@link SuspendContext} gives access to cooperative cancellation and timing primitives.
 * </p>
 *
 * <p>Typical usage with {@link InteropChannel#forEach(SuspendContext, InteropConsumer)}:</p>
 * <pre>{@code
 * InteropChannel<String> ch = InteropChannel.<String>buffered(8);
 * InteropConsumer<String> consumer = (suspend, opt) -> {
 *     suspend.checkCancellation();
 *     String v = opt.orElse("(null)");
 *     // ...handle v...
 * };
 * ch.forEach(ctx, consumer);
 * }</pre>
 *
 * @param <T> element type (nullable on the producer side; represented as Optional here)
 * @author Rob Deas
 * @since 0.1.0
 */
@FunctionalInterface
public interface InteropConsumer<T> {

    // 🧩 Section: api

    /**
     * Consume the next element wrapped in an {@link Optional}. Empty == Kotlin {@code null}.
     *
     * @param suspend suspending context (non-null)
     * @param item    non-null {@link Optional} containing a value or empty for {@code null}
     * @throws Exception if processing fails; implementations may throw
     *                   {@link java.util.concurrent.CancellationException} to signal cancellation
     */
    void accept(@NonNull SuspendContext suspend, @NonNull Optional<T> item) throws Exception;
    // [/🧩 Section: api]
}
