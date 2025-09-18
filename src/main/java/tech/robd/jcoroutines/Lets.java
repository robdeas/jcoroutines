/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/Lets.java
 description: Kotlin-like helper methods for conditional execution and defaults over nullable values and Optional, plus small coroutine-handle utilities.
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
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Kotlin-like helper methods for conditional execution over nullable values and {@link Optional},
 * plus a small utility for conditionally acting on active coroutine handles.
 * <p>
 * These helpers aim to make Java code read closer to idiomatic Kotlin:
 * {@code let}, {@code also}, and an {@code elvis}-style operator with eager/lazy defaults.
 * </p>
 *
 * <p><strong>Null-safety:</strong> Methods accept {@code @Nullable} inputs where appropriate and
 * never store raw {@code null} into {@link Optional}. No synchronization is performed; these
 * utilities are thread-safe by virtue of being stateless.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class Lets {
    private Lets() { /* utility class */ }

    // [🧩 Section: nullable-helpers]

    /**
     * If {@code value} is non-null, invokes {@code action} with it.
     * Similar to Kotlin's {@code let}.
     *
     * @param value  possibly-null value
     * @param action consumer invoked if {@code value} is non-null (must be non-null)
     * @param <T>    value type
     */
    public static <T> void let(@Nullable T value, @NonNull Consumer<? super T> action) {
        if (value != null) action.accept(value);
    }

    /**
     * If {@code value} is non-null, applies {@code mapper} and returns the result; otherwise returns {@code null}.
     * Similar to Kotlin's {@code let}.
     *
     * @param value  possibly-null value
     * @param mapper mapping function (must be non-null)
     * @param <T>    input type
     * @param <R>    (nullable) result type
     * @return mapped result or {@code null} if {@code value} was {@code null}
     */
    public static <T, R extends @Nullable Object> @Nullable R let(@Nullable T value, @NonNull Function<? super T, R> mapper) {
        return (value != null) ? mapper.apply(value) : null;
    }

    /**
     * Executes {@code action} with {@code value} (if non-null) and returns the original {@code value}.
     * Similar to Kotlin's {@code also}.
     *
     * @param value  the original value (may be {@code null})
     * @param action side-effect action to execute when {@code value} is non-null
     * @param <T>    type of {@code value}
     * @return the original {@code value} (may be {@code null})
     */
    public static <T> T also(@Nullable T value, @NonNull Consumer<? super T> action) {
        if (value != null) action.accept(value);
        return value;
    }
    // [/🧩 Section: nullable-helpers]

    // [🧩 Section: optional-helpers]

    /**
     * If {@code opt} is present, invokes {@code action} with its value.
     *
     * @param opt    non-null {@link Optional}
     * @param action consumer to execute if a value is present
     * @param <T>    contained value type
     * @throws NullPointerException if {@code opt} is {@code null}
     */
    public static <T> void let(@NonNull Optional<T> opt, @NonNull Consumer<? super T> action) {
        opt.ifPresent(action);
    }

    /**
     * If {@code opt} is present, applies {@code mapper} and returns the mapped result as an {@link Optional};
     * otherwise returns {@link Optional#empty()}.
     *
     * @param opt    non-null {@link Optional}
     * @param mapper mapping function for present values
     * @param <T>    input type
     * @param <R>    result type
     * @return {@code Optional.of(mapper.apply(value))} if present, else {@code Optional.empty()}
     */
    public static <T, R> @NonNull Optional<R> let(@NonNull Optional<T> opt, @NonNull Function<? super T, R> mapper) {
        return opt.map(mapper);
    }
    // [/🧩 Section: optional-helpers]

    // [🧩 Section: elvis]

    /**
     * Returns {@code value} if non-null; otherwise returns {@code defaultValue}.
     * Equivalent to Kotlin's {@code value ?: defaultValue}.
     *
     * @param value        possibly-null value
     * @param defaultValue non-null default to use when {@code value} is {@code null}
     * @param <T>          value type
     * @return {@code value} or {@code defaultValue} if {@code value} is {@code null}
     */
    public static <T> T elvis(@Nullable T value, @NonNull T defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * Returns {@code value} if non-null; otherwise computes the default via {@code defaultSupplier}.
     * Default is evaluated lazily (only when needed).
     *
     * @param value            possibly-null value
     * @param defaultSupplier  supplier that provides a default when {@code value} is {@code null}
     * @param <T>              value type
     * @return {@code value} or the computed default
     */
    public static <T> T elvis(@Nullable T value, java.util.function.Supplier<? extends T> defaultSupplier) {
        return value != null ? value : defaultSupplier.get();
    }

    /**
     * Returns {@code value} if non-null; otherwise computes the default via a supplier that may throw.
     * Checked exceptions are wrapped as {@link RuntimeException} with a stable message.
     *
     * @param value                     possibly-null value
     * @param defaultThrowingSupplier   supplier invoked only when {@code value} is {@code null}
     * @param <T>                       value type (non-null default)
     * @return {@code value} or the computed default
     * @throws RuntimeException wrapping any checked exception thrown by the supplier
     */
    public static <T> T elvis(@Nullable T value, @NonNull ThrowingSupplier<@NonNull T> defaultThrowingSupplier) {
        try {
            return value != null ? value : defaultThrowingSupplier.get();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Default supplier failed", e);
        }
    }
    // [/🧩 Section: elvis]

    // [🧩 Section: handles]

    /**
     * If {@code handle} is non-null and still active, join it and pass the result to {@code action}.
     * Exceptions from {@link JCoroutineHandle#join()} are propagated as {@link RuntimeException}
     * if they are checked.
     *
     * @param handle potentially-null coroutine handle
     * @param action consumer invoked with the joined result if the handle is active
     * @param <T>    result type
     * @throws RuntimeException if joining fails with a checked exception
     */
    public static <T> void letIfActive(@Nullable JCoroutineHandle<T> handle, @NonNull Consumer<? super T> action) {
        if (handle != null && handle.isActive()) {
            try {
                T result = handle.join();
                action.accept(result);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException("Handle execution failed", e);
            }
        }
    }
    // [/🧩 Section: handles]
}
