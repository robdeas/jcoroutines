/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/SuspendFunction.java
 description: Functional interface for a suspending function that returns a (possibly null) value.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 tags: [robokeytags,v1]
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

/**
 * A function-like interface whose body executes with an explicit {@link SuspendContext} and
 * returns a (possibly {@code null}) value.
 * <p>
 * This is the Java interop shape for a Kotlin {@code suspend () -> T?}-style function.
 * </p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * SuspendFunction<String> load = suspend -> {
 *     suspend.delay(10);
 *     return "value";
 * };
 * }</pre>
 *
 * @param <T> the (nullable) result type produced by this function
 * @author Rob Deas
 * @since 0.1.0
 */
@FunctionalInterface
public interface SuspendFunction<T extends @Nullable Object> {

    // 🧩 Section: api

    /**
     * Applies this function within the provided suspending {@code suspend} context.
     *
     * @param suspend the non-null {@link SuspendContext} providing cancellation,
     *                delay, and dispatcher services
     * @return the computed result, which may be {@code null}
     * @throws Exception if the function fails for any reason. Implementations may
     *                   throw {@link java.util.concurrent.CancellationException} to signal cancellation.
     */
    @Nullable
    T apply(@NonNull SuspendContext suspend) throws Exception;
    // [/🧩 Section: api]
}
