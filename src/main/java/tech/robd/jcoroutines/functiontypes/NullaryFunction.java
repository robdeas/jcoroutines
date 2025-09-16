/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/functiontypes/NullaryFunction.java
 description: Project-local alias for Supplier. Allows safe Kotlin extensions without
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

import java.util.function.Supplier;

/**
 * Scoped alias over {@link Supplier}.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Kotlin can add extension operators/methods, but adding them directly to
 *       {@code Supplier} affects global semantics across modules.</li>
 *   <li>By using this alias, extensions remain local to this project while still
 *       interoperating perfectly with Java’s {@code Supplier}.</li>
 * </ul>
 *
 * <p>Usage from Kotlin (with extension imported):
 * <pre>{@code
 * val supplier: NullaryFunction<String> = NullaryFunction { "hello" }
 * val value = supplier() // operator invoke works on NullaryFunction
 * }</pre>
 *
 * <p><b>Do not</b> add global extensions to {@code java.util.function.Supplier}.
 * Prefer adding them to this alias instead.</p>
 *
 * @param <R> result type
 */
@FunctionalInterface
public interface NullaryFunction<R> extends Supplier<R> {
}
