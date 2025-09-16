/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/memory/GcAsserts.java
 description: Test utility for asserting that objects become unreachable and are collected by the GC within a bounded time window.
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

package tech.robd.jcoroutines.memory;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

/**
 * GC assertion helpers for tests that verify object <em>non-retention</em>.
 * <p>
 * Unlike production code, GC-sensitive tests sometimes need to wait for the
 * collector to process unreachable objects. {@link GcAsserts} provides a small,
 * bounded wait with light allocation pressure to encourage a collection cycle,
 * while polling a {@link ReferenceQueue} to confirm that a {@link Reference}
 * has been <em>enqueued</em> (i.e., the referent was cleared by the GC).
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Prepare a weak/phantom ref + queue
 * ReferenceQueue<Object> q = new ReferenceQueue<>();
 * WeakReference<Object> wr = new WeakReference<>(new Object(), q);
 * // Drop all strong refs to the referent at this point
 *
 * // Wait up to 500 ms for the GC to clear/enqueue
 * GcAsserts.awaitGcCleared(wr, q, 500);
 * }</pre>
 *
 * <p><strong>Notes & caveats</strong></p>
 * <ul>
 *   <li>GC is nondeterministic; this helper applies small allocation pressure
 *   and sleeps briefly between polls to reduce flakiness.</li>
 *   <li>The method throws {@link AssertionError} if the reference is not
 *   cleared/enqueued within the timeout, which is appropriate for use in tests.</li>
 *   <li>Callers must ensure all strong references to the referent have been
 *   cleared <em>before</em> invoking this method.</li>
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> Stateless and thread-safe.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
final class GcAsserts {

    private GcAsserts() { /* utility class */ }

    /**
     * Await until {@code ref} is cleared/enqueued by the GC or fail after {@code timeoutMs}.
     * <p>
     * Implementation strategy:
     * </p>
     * <ol>
     *   <li>Loop until timeout while {@link Reference#get()} is still non-null.</li>
     *   <li>On each iteration, request a GC, allocate a small temporary buffer
     *   to create allocation pressure, poll {@code q} for an enqueued reference,
     *   and sleep for ~10ms to yield.</li>
     *   <li>Perform one final {@code q.poll()} after the loop.</li>
     * </ol>
     *
     * @param ref       the weak/phantom reference whose referent should be cleared
     * @param q         the reference queue associated with {@code ref}
     * @param timeoutMs maximum time to wait, in milliseconds
     * @throws InterruptedException if interrupted while sleeping between polls
     * @throws AssertionError if the referent is not GC-cleared within {@code timeoutMs}
     */
    static void awaitGcCleared(Reference<?> ref, ReferenceQueue<Object> q, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;

        // Hint GC a few times while applying light allocation pressure.
        // The small buffer helps trigger young-gen collections without heavy cost.
        while (ref.get() != null && System.nanoTime() < end) {
            System.gc();
            byte[] unused = new byte[512 * 1024]; // small pressure (~512 KiB)
            // If the reference has already been enqueued, we can stop early.
            Reference<?> polled = q.poll();
            if (polled != null) break;
            Thread.sleep(10);
        }

        // One last poll to be sure we didn't miss the enqueue between checks.
        Reference<?> polled = q.poll();
        if (polled == null && ref.get() != null) {
            throw new AssertionError("Object not GC'd within timeout");
        }
    }
}
