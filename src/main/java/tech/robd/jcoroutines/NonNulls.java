/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/NonNulls.java
 description: Nullness helpers bridging JSpecify/NullAway contracts with explicit runtime behaviour (guards, defaults, and exception-based flows).
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
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Nullness helpers for bridging JSpecify/NullAway contracts with clear runtime behaviour.
 *
 * <p>Under {@code @NullMarked}, parameters and return values are non-null by default.
 * This class provides:
 * <ul>
 *   <li>{@link #nonNull(Object)} – For values that should already be non-null.
 *       Defensive check only; fails fast with a clear message if violated.</li>
 *   <li>{@link #nonNull(Object, String)} – Same as above with a custom message.</li>
 *   <li>{@link #nonNullOr(Object, Object)} – Accepts {@code @Nullable}, returns
 *       {@code @NonNull} or a constant fallback (eagerly evaluated).</li>
 *   <li>{@link #nonNullOrGet(Object, java.util.function.Supplier)} – Accepts {@code @Nullable},
 *       returns {@code @NonNull} or a lazily supplied fallback (only created if needed).</li>
 *   <li>{@link #nonNullOrThrow(Object, java.util.function.Supplier)} – Accepts {@code @Nullable},
 *       returns {@code @NonNull} or throws a caller-supplied {@link RuntimeException}.</li>
 *   <li>{@link #nonNullOrThrow(Object, String)} – Convenience overload: throws
 *       {@link IllegalStateException} with a message.</li>
 * </ul>
 *
 * <h3>Usage examples</h3>
 *
 * <pre>{@code
 * // Value should not be null here – defensive guard
 * User u = NonNulls.nonNull(user);
 *
 * // Fallback constant (eagerly created)
 * String label = NonNulls.nonNullOr(possiblyNull, "default");
 *
 * // Fallback computed only if needed
 * Config cfg = NonNulls.nonNullOrGet(possiblyNullCfg, Config::loadDefault);
 *
 * // Throw custom exception if null
 * User u2 = NonNulls.nonNullOrThrow(possiblyNull,
 *                                   () -> new IllegalArgumentException("Missing user"));
 *
 * // Quick message -> IllegalStateException
 * Session s = NonNulls.nonNullOrThrow(possiblyNullSession,
 *                                     "Expected non-null session");
 * }</pre>
 *
 * <p>Note: {@link #requireNonNull(String, Object)} is deprecated; prefer {@link #nonNull(Object)}
 * or one of the {@code *Or*} variants.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class NonNulls {

    // 🧩 Section: guards

    private NonNulls() {
    }

    /**
     * Defensive guard: returns {@code value} if non-null; otherwise throws NPE with a clear message.
     */
    public static <T> @NonNull T nonNull(T value) {
        return Objects.requireNonNull(value, "Expected non-null value");
    }

    /**
     * Defensive guard with custom message.
     *
     * @param value   the value expected to be non-null
     * @param message message for the thrown {@link NullPointerException}
     */
    public static <T> @NonNull T nonNull(T value, String message) {
        return Objects.requireNonNull(value, message);
    }

    /**
     * Prefer {@link #nonNull(Object)} or the *Or* helpers; this is just a named variant.
     */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> @NonNull T requireNonNull(@Nullable String name, T value) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    // [/🧩 Section: guards]

    // 🧩 Section: defaults

    /**
     * Opt-in default (constant).
     * If {@code value} is {@code null}, returns {@code defaultValue}.
     */
    // Eager: allocates list even if value != null
    public static <T> @NonNull T nonNullOr(@Nullable T value, T defaultValue) {
        return Objects.requireNonNullElse(value, defaultValue);
    }

    /**
     * Opt-in default (lazy).
     * If {@code value} is {@code null}, evaluates {@code defaultSupplier} and returns the result.
     */
    // Lazy: builds list only when needed
    public static <T> @NonNull T nonNullOrGet(@Nullable T value, Supplier<? extends T> defaultSupplier) {
        return Objects.requireNonNullElseGet(value, defaultSupplier);
    }

    // [/🧩 Section: defaults]

    // 🧩 Section: exceptions
    // Nullable → throw custom exception
    public static <T> @NonNull T nonNullOrThrow(
            @Nullable T value, Supplier<? extends RuntimeException> exSupplier) {
        if (value != null) return value;
        throw exSupplier.get();
    }

    // (Optional) convenience: message → IllegalStateException
    public static <T> @NonNull T nonNullOrThrow(
            @Nullable T value, String message) {
        if (value != null) return value;
        throw new IllegalStateException(message);
    }

    // [/🧩 Section: exceptions]

    // 🧩 Section: utilities
    private static final AutoCloseable EMPTY_CLOSEABLE = () -> {
    };

    /**
     * Returns a shared no-op {@link AutoCloseable} that does nothing when closed.
     * Useful for APIs expecting a resource handle when no resource is actually acquired.
     */
    public static AutoCloseable emptyCloseable() {
        return EMPTY_CLOSEABLE;
    }

}