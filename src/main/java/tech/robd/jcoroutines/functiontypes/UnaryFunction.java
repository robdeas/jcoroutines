/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/functiontypes/UnaryFunction.java
 description: Project-local alias for Function. Allows safe Kotlin extensions without
              globally extending JDK types.
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

import java.util.function.Function;

/**
 * Scoped alias over {@link java.util.function.Function}.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Kotlin can add extension operators/methods, but doing so on JDK types like
 *       {@code Function} leaks globally and can surprise readers.</li>
 *   <li>By using this alias, Kotlin extensions remain opt-in and local to this
 *       artifact, while staying fully interoperable with Java’s {@code Function}.</li>
 * </ul>
 *
 * <p>Usage from Kotlin (with extension imported):
 * <pre>{@code
 * val square: UnaryFunction<Int, Int> = UnaryFunction { x -> x * x }
 * val nine = square(3) // operator invoke works on UnaryFunction
 * }</pre>
 *
 * <p><b>Do not</b> add global extensions to {@code java.util.function.Function}.
 * Prefer adding them to this alias instead.</p>
 *
 * @param <T> input type
 * @param <R> result type
 */
@FunctionalInterface
public interface UnaryFunction<T, R> extends Function<T, R> {
    // Inherits R apply(T t)
}
