/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/functiontypes/QuadFunction.java
 description: Functional interface for four-argument functions. Used for interop when
              SuspendContext plus three business parameters are needed.
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

package tech.robd.jcoroutines.functiontypes;

import org.jspecify.annotations.Nullable;

/**
 * Generic function interface for four parameters.
 *
 * <p>Intended for interop cases where an extra context parameter is included
 * (e.g., {@code SuspendContext} + three business parameters). For new APIs,
 * fewer parameters are generally recommended; if you need more, consider
 * {@link QuinFunction} or a parameter object.</p>
 *
 * @param <T> first argument type
 * @param <U> second argument type
 * @param <V> third argument type
 * @param <W> fourth argument type
 * @param <R> result type (nullable if annotated with {@link Nullable})
 */
@FunctionalInterface
public interface QuadFunction<T, U, V, W, R> {
    /**
     * Apply this function to the given arguments.
     *
     * @param t first argument
     * @param u second argument
     * @param v third argument
     * @param w fourth argument
     * @return result of applying the function
     */
    R apply(T t, U u, V v, W w);
}
