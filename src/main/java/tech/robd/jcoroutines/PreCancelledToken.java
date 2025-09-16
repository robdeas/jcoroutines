/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/PreCancelledToken.java
 description: Singleton implementation of CancellationToken that is permanently in the cancelled state.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 tags: [robokeytags,v1]
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
package tech.robd.jcoroutines;

// This class implements a permanently-cancelled CancellationToken.
// It is a lightweight singleton used anywhere a token is required, but work must be treated as already canceled.

/**
 * A singleton {@link CancellationToken} that is permanently cancelled.
 * <p>
 * Useful for APIs that require a token, when the operation should be treated as
 * already cancelled. Callbacks registered via {@link #onCancel(Runnable)} are
 * invoked immediately. {@link #cancel()} is a no-op and returns {@code false}.
 * Child tokens created via {@link #child()} are also pre-cancelled (returns {@code this}).
 * </p>
 *
 * <p><strong>Thread-safety:</strong> This instance is immutable and safe to reuse.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class PreCancelledToken implements CancellationToken {

    // 🧩 Section: api
    /**
     * Singleton instance of the pre-cancelled token.
     */
    static final PreCancelledToken INSTANCE = new PreCancelledToken();

    private PreCancelledToken() {
    }

    /**
     * Always returns {@code true}.
     */
    @Override
    public boolean isCancelled() {
        return true;
    }

    /**
     * Invokes {@code callback} immediately because the token is already cancelled.
     *
     * @param callback the action to run
     * @return a no-op {@link AutoCloseable}
     */
    @Override
    public AutoCloseable onCancel(Runnable callback) {
        callback.run();
        return NonNulls.emptyCloseable();
    }

    /**
     * Quietly acknowledges registration; since the token is cancelled, this is a no-op.
     * Implementations may choose to run quietly; here we do nothing.
     *
     * @param callback the action that would be run on cancel (ignored)
     */
    @Override
    public void onCancelQuietly(Runnable callback) {
    }


    /**
     * Returns {@code this}, as children of a pre-cancelled token are also cancelled.
     */
    @Override
    public CancellationToken child() {
        return this;
    }


    /**
     * No-op; the token is already cancelled.
     *
     * @return {@code false} to indicate no state change occurred
     */
    @Override
    public boolean cancel() {
        return false;
    }
    // [/🧩 Section: api]
}