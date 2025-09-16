/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/functiontypes/BinaryFunction.java
 description: Project-local alias for BiFunction. Allows safe Kotlin extension methods
              without globally extending JDK types.
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

import java.util.function.BiFunction;

/**
 * Scoped alias over {@link java.util.function.BiFunction}.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Kotlin can add extension operators/methods at call sites, but adding them directly
 *       to JDK types like {@code BiFunction} leaks globally and can surprise readers.</li>
 *   <li>By using this alias, Kotlin extensions remain opt-in and local to this artifact,
 *       while remaining fully interoperable with Java’s {@code BiFunction}.</li>
 * </ul>
 *
 * <p>Usage from Kotlin (with extension imported):
 * <pre>{@code
 * val add: BinaryFunction<Int, Int, Int> = BinaryFunction { a, b -> a + b }
 * val sum = add(2, 3) // operator invoke works on BinaryFunction, not BiFunction
 * }</pre>
 *
 * <p><b>Do not</b> add global extensions to {@code java.util.function.BiFunction}.
 * Prefer adding them to this alias instead.</p>
 *
 * @param <T> first argument type
 * @param <U> second argument type
 * @param <R> return type
 */
@FunctionalInterface
public interface BinaryFunction<T, U, R> extends BiFunction<T, U, R> {
}
