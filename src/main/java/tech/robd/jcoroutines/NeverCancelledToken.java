/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/NeverCancelledToken.java
 description: Singleton CancellationToken that is permanently not-cancelled; callbacks never fire and cancel() acknowledges without changing state.
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

/**
 * A singleton {@link CancellationToken} that is permanently <em>not cancelled</em>.
 * <p>
 * This token is useful for code paths that require a token but should always behave
 * as if cancellation will never occur (e.g., tests, no-op adapters, or long-lived
 * background services that don't support external cancellation).
 * </p>
 *
 * <p><strong>Semantics:</strong></p>
 * <ul>
 *   <li>{@link #isCancelled()} is always {@code false}.</li>
 *   <li>{@link #onCancel(Runnable)} returns a no-op registration and never invokes the callback.</li>
 *   <li>{@link #onCancelQuietly(Runnable)} is a no-op.</li>
 *   <li>{@link #child()} returns {@code this} (children are also never cancelled).</li>
 *   <li>{@link #cancel()} returns {@code true} to acknowledge the request, but does not change state.</li>
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> This instance is immutable and safe for reuse.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class NeverCancelledToken implements CancellationToken {

    /**
     * Singleton instance.
     * Package-private to encourage use through higher-level factories in the API.
     * Safe for reference equality checks if needed.
     */
    static final NeverCancelledToken INSTANCE = new NeverCancelledToken();

    private NeverCancelledToken() { }

    // 🧩 Section: api

    @Override
    // Always not-cancelled; callers can branch without synchronization.
    public boolean isCancelled() {
        return false;
    }

    @Override
    /**
     * Register a callback for cancellation. For this token, the callback is never called.
     *
     * @param callback the action that would be run on cancel (ignored)
     * @return a no-op {@link AutoCloseable} registration handle
     */
    public AutoCloseable onCancel(Runnable callback) { /* never called */
        return NonNulls.emptyCloseable();
    }

    @Override
    /**
     * Quiet registration that is ignored for this token.
     *
     * @param callback the action that would be run on cancel (ignored)
     */
    public void onCancelQuietly(Runnable callback) {
        // no-op
    }

    @Override
    /**
     * Returns {@code this}, as children of a never-cancelled token are also never cancelled.
     *
     * @return {@code this}
     */
    public CancellationToken child() {
        return this;
    }

    @Override
    /**
     * Acknowledge a cancellation request without changing state.
     * <p>
     * Note: This implementation returns {@code true}, meaning the request was accepted,
     * but the token remains not-cancelled.
     * </p>
     *
     * @return {@code true} (acknowledged) while remaining not-cancelled
     */
    public boolean cancel() {
        return true;
    }

    // [/🧩 Section: api]
}
