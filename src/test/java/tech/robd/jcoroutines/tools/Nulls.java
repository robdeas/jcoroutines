/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/tools/Nulls.java
 description: Typed null constants for tests and overload selection (e.g., pass a String-typed null without casting).
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

package tech.robd.jcoroutines.tools;

import org.jspecify.annotations.Nullable;

/**
 * Utility holder for <em>typed null constants</em>.
 * <p>
 * These constants exist to make tests and overload selection more expressive by
 * allowing you to pass a {@code null} of a specific type <em>without</em> an
 * inline cast. For example, when you want to call an overload that accepts
 * {@code String} rather than {@code Object}, you can write:
 * </p>
 *
 * <pre>{@code
 * myApi.accept(Nulls.NULL_STRING);   // selects the String overload unambiguously
 * }</pre>
 *
 * <p><strong>Intended use:</strong> test code, demos, and examples only. They are
 * deliberately {@code null} and will throw {@link NullPointerException} if dereferenced.
 * Avoid logging/concatenating them. Prefer {@code Optional} in production APIs.
 * </p>
 *
 * <p><strong>Nullness annotations:</strong> Each field is annotated with
 * {@link Nullable} so null-aware tooling understands the contract at call sites.
 * </p>
 *
 * <p><strong>Thread-safety:</strong> Stateless constants; safe to share.</p>
 * <p>
 * They allow places setting null variables in the code using these utils to be easily found (Usages of the constant).
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class Nulls {
    /**
     * Prevent instantiation.
     */
    private Nulls() {
    }

    /**
     * A typed-{@code null} for {@link String}.
     */
    @Nullable
    public static final String NULL_STRING = null;

    /**
     * A typed-{@code null} for {@link Number}.
     */
    @Nullable
    public static final Number NULL_NUMBER = null;

    /**
     * A typed-{@code null} for {@link Boolean}.
     */
    @Nullable
    public static final Boolean NULL_BOOLEAN = null;

    /**
     * A typed-{@code null} for {@link Character}.
     */
    @Nullable
    public static final Character NULL_CHARACTER = null;

    /**
     * A typed-{@code null} for {@link Object}.
     */
    @Nullable
    public static final Object NULL_OBJECT = null;

    /**
     * A typed-{@code null} for {@link Class}.
     */
    @Nullable
    public static final Class<?> NULL_CLASS = null;

    /**
     * A typed-{@code null} for {@link Thread}.
     */
    @Nullable
    public static final Thread NULL_THREAD = null;
}
