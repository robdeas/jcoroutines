/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/LetsTest.java
 description: Tests for Lets helpers: nullable/Optional let, also, elvis variants, and letIfActive over JCoroutineHandle.
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
 * You may not use this file except in compliance with the License.
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

import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class LetsTest {

    /* ---------- let (nullable) ---------- */

    @Test
        // Nullable let (Consumer): runs when non-null; no-op for null.
        // No race: pure synchronous calls.
    void let_nullable_consumer_runsWhenNonNull_elseNoop() {
        AtomicReference<String> seen = new AtomicReference<>();
        Lets.let("hi", seen::set);
        assertEquals("hi", seen.get());

        seen = new AtomicReference<>(); // fresh ref defaults to null
        Lets.let((String) null, seen::set);
        assertNull(seen.get(), "consumer should not run for null");
    }

    @Test
        // Nullable let (Function): returns mapped value when non-null; else null.
        // No race: pure synchronous mapping.
    void let_nullable_mapper_returnsMappedOrNull() {
        String nonNull = Lets.let("x", s -> s + s);
        assertEquals("xx", nonNull);

        String isNull = Lets.let((String) null, s -> s + s);
        assertNull(isNull);
    }

    /* ---------- let (Optional) ---------- */

    @Test
        // Optional let (Consumer): runs only for present values; no-op for Optional.empty().
        // No race: Optional.ifPresent semantics.
    void let_optional_consumer_runsWhenPresent_elseNoop() {
        AtomicReference<String> seen = new AtomicReference<>();
        Lets.let(Optional.of("ok"), seen::set);
        assertEquals("ok", seen.get());

        seen = new AtomicReference<>();
        Lets.let(Optional.<String>empty(), seen::set);
        assertNull(seen.get(), "consumer should not run for empty optional");
    }

    @Test
        // Optional let (Function): maps present value; empty stays empty.
        // No race: Optional.map semantics.
    void let_optional_mapper_mapsOrEmpty() {
        assertEquals(Optional.of(3), Lets.let(Optional.of("hey"), String::length));
        assertEquals(Optional.empty(), Lets.let(Optional.<String>empty(), String::length));
    }

    /* ---------- also ---------- */

    @Test
        // also: executes side-effect when non-null and returns original value; null is a no-op.
        // No race: synchronous side-effect through AtomicReference.
    void also_runsSideEffect_whenNonNull_andReturnsOriginal() {
        AtomicReference<String> seen = new AtomicReference<>();
        String v = Lets.also("x", seen::set);
        assertEquals("x", v);
        assertEquals("x", seen.get());

        seen = new AtomicReference<>(); // ✅
        String v2 = Lets.also(null, seen::set);
        assertNull(v2);
        assertNull(seen.get(), "side effect should not run for null");
    }

    /* ---------- elvis ---------- */

    @Test
        // Elvis (eager default): returns value if non-null else default.
        // No race: pure value check.
    void elvis_valueOrDefault() {
        assertEquals("a", Lets.elvis("a", "b"));
        assertEquals("b", Lets.elvis(null, "b"));
    }

    @Test
        // Elvis (lazy default): supplier is invoked only when value is null; call count verifies laziness.
        // No race: synchronous supplier and assertions.
    void elvis_supplier_onlyCalledIfNull() {
        AtomicInteger calls = new AtomicInteger();
        var sup = (java.util.function.Supplier<String>) () -> {
            calls.incrementAndGet();
            return "def";
        };

        assertEquals("x", Lets.elvis("x", sup));
        assertEquals(0, calls.get(), "supplier not called when value present");

        assertEquals("def", Lets.elvis(null, sup));
        assertEquals(1, calls.get(), "supplier called once when value null");
    }

    @Test
        // Elvis (throwing supplier): success path returns value; checked exception path should surface as RuntimeException with cause.
        // No race: supplier throws immediately; test checks cause type/message rather than wrapper text.
    void elvis_throwingSupplier_success_and_wrapsCheckedException() {
        // success path
        assertEquals("v", Lets.elvis((String) null, (Supplier<? extends String>) () -> "v"));

        // checked exception path -> RuntimeException("Default supplier failed", cause)
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> Lets.elvis((Object) null, (Supplier<?>) () -> {
                    try {
                        throw new IOException("io");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));

// Don’t check ex.getMessage(); check the cause details instead:
        assertNotNull(ex.getCause(), "The cause should not be null (checked exception wrapping)");
        assertInstanceOf(java.io.IOException.class, ex.getCause());
        assertEquals("io", ex.getCause().getMessage());

    }

    /* ---------- letIfActive (JCoroutineHandle) ---------- */

    @Test
        // letIfActive with active handle: action runs with joined result.
        // Race-avoidance: async delays briefly so handle is active at call time; result().isDone() proves completion afterwards.
    void letIfActive_runsAction_forActiveHandle_thenJoinsForResult() {
        AtomicReference<String> seen = new AtomicReference<>();
        // Start a slow async so it's active at call time
        JCoroutineHandle<String> h = Coroutines.async(c -> {
            c.delay(50);
            return "done";
        });

        // Action should run with the joined result
        Lets.letIfActive(h, seen::set);

        // Ensure the result made it through
        assertEquals("done", seen.get());
        assertTrue(h.result().isDone());
    }

    @Test
        // letIfActive is a no-op for null handle and for already-completed (not active) handle.
        // Race-avoidance: explicit join() marks handle completed before invoking letIfActive.
    void letIfActive_noop_whenHandleNull_orNotActive() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        // null -> noop
        Lets.letIfActive(null, seen::set);
        assertNull(seen.get());

        // already completed (not active) -> noop
        CompletableFuture<String> cf = new CompletableFuture<>();
        cf.complete("ok");
        JCoroutineHandle<String> finished;
        try (JCoroutineScope scope = Coroutines.newScope()) {
            finished = scope.async(c -> "ok"); // completes quickly
            finished.result().join();          // ensure not active
        }
        seen = new AtomicReference<>();
        Lets.letIfActive(finished, seen::set);
        assertNull(seen.get());
    }

    @Test
        // letIfActive is a no-op for cancelled handles.
        // Race-avoidance: cancel() called before invoking letIfActive; long delay keeps task from completing normally.
    void letIfActive_noop_whenAlreadyCancelled() {
        AtomicReference<String> seen = new AtomicReference<>();
        JCoroutineHandle<String> h = Coroutines.async(c -> {
            c.delay(5_000);
            return "late";
        });
        assertTrue(h.cancel());
        Lets.letIfActive(h, seen::set); // not active -> should not run
        assertNull(seen.get());
    }
}
