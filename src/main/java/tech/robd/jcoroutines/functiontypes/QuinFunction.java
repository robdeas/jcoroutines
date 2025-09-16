/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/functiontypes/QuinFunction.java
 description: Functional interface for five-argument functions. Mainly for internal interop
              cases where SuspendContext plus four parameters are needed.
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

/**
 * Generic function interface for five parameters.
 *
 * <p><strong>Note:</strong> Intended for internal use where an extra context parameter
 * is needed (e.g., {@code SuspendContext} + four business parameters).
 * For new APIs, prefer {@link QuadFunction} or fewer parameters.
 * If you need more than four business parameters, consider a parameter object
 * or builder pattern instead.</p>
 *
 * @param <T> first argument type
 * @param <U> second argument type
 * @param <V> third argument type
 * @param <W> fourth argument type
 * @param <X> fifth argument type
 * @param <R> result type
 */
@FunctionalInterface
public interface QuinFunction<T, U, V, W, X, R> {
    /**
     * Apply this function to the given arguments.
     *
     * @param t first argument
     * @param u second argument
     * @param v third argument
     * @param w fourth argument
     * @param x fifth argument
     * @return function result
     */
    R apply(T t, U u, V v, W w, X x);
}
