/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/memory/Refs.java
 description: Simple GC-test POJO that groups weak references to a handle, a suspend context, and a marker object for retention checks.
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

package tech.robd.jcoroutines.memory;

import tech.robd.jcoroutines.SuspendContext;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.lang.ref.WeakReference;

/**
 * Groups weak references used by GC-oriented tests.
 * <p>
 * Many tests in the {@code memory} package verify that certain runtime objects
 * (e.g., coroutine handles, contexts, or ad-hoc markers) are not retained after
 * completion, cancellation, or rejection. {@code Refs} provides a tiny aggregate
 * of those {@link WeakReference} instances so a single object can be passed
 * around and asserted on succinctly.
 * </p>
 *
 * <p><strong>Design notes</strong></p>
 * <ul>
 *   <li><em>POJO rather than {@code record}:</em> kept intentionally simple to
 *   avoid generating {@code equals}/{@code hashCode} noise in diagnostics and to
 *   keep the canonical constructor explicit in source.</li>
 *   <li><em>Immutability:</em> fields are {@code final}; instances are thread-safe
 *   because they are effectively immutable.</li>
 *   <li><em>Ownership:</em> this class does not own or manage the lifetime of the
 *   referents; callers must ensure all strong references are cleared before GC assertions.</li>
 * </ul>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
// package-private by design (test-only utility)
final class Refs {
    /**
     * Weak ref to the last handle under observation.
     */
    final WeakReference<JCoroutineHandle<?>> handle;

    /**
     * Weak ref to the associated suspend context (if any).
     */
    final WeakReference<SuspendContext> ctx;

    /**
     * Weak ref to an arbitrary marker object captured during the test.
     */
    final WeakReference<Object> marker;

    /**
     * Creates a new container for weak references used in GC assertions.
     *
     * @param handle weak reference to a {@link JCoroutineHandle} under test (non-null)
     * @param ctx    weak reference to a related {@link SuspendContext} (may be {@code null} depending on the scenario)
     * @param marker weak reference to an arbitrary marker object used by the test (optional, may be {@code null})
     */
    Refs(WeakReference<JCoroutineHandle<?>> handle,
         WeakReference<SuspendContext> ctx,
         WeakReference<Object> marker) {
        this.handle = handle;
        this.ctx = ctx;
        this.marker = marker;
    }

    /**
     * @return the weak reference to the handle under test
     */
    public WeakReference<JCoroutineHandle<?>> handle() {
        return handle;
    }

    /**
     * @return the weak reference to a marker object captured by the test (may be {@code null})
     */
    public WeakReference<Object> marker() {
        return marker;
    }

    /**
     * @return the weak reference to the associated {@link SuspendContext} (may be {@code null})
     */
    public WeakReference<SuspendContext> ctx() {
        return ctx;
    }
}
