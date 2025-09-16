/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/CancellationToken.java
 description: Public interface representing a cancellation signal. Supports tree-structured
              cascading cancellation, callback registration, and prebuilt none/cancelled tokens.
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

package tech.robd.jcoroutines;

/**
 * Represents a cancellation signal that can be observed and propagated.
 *
 * <p>Tokens form a tree structure where parent cancellation cascades to children,
 * but children may be cancelled independently without affecting the parent.</p>
 */
public interface CancellationToken {

    /**
     * @return {@code true} if this token has been cancelled
     * <p>Thread-safe and non-blocking.</p>
     */
    /* default */  boolean isCancelled();

    /**
     * Register a callback to be invoked when this token is cancelled.
     * <p>If already cancelled, the callback runs immediately on the calling thread.</p>
     *
     * @param callback action to run on cancellation (should be fast and non-blocking)
     * @return {@link AutoCloseable} that removes the callback when closed
     */
    /* default */  AutoCloseable onCancel(Runnable callback);

    /**
     * Register a cancellation callback that is automatically removed when the try-with-resources
     * block exits. Exceptions in callback setup are suppressed.
     *
     * @param callback action to run on cancellation
     */
    /* default */  void onCancelQuietly(Runnable callback);

    /**
     * Create a child token that will be cancelled when this token is cancelled.
     *
     * @return new child token (independent cancellation possible)
     */
    /* default */ CancellationToken child();

    /**
     * Cancel this token and all its children.
     * <p>Triggers all registered callbacks immediately. Safe to call multiple times.</p>
     *
     * @return {@code true} if this call performed the first cancellation, {@code false} if already cancelled
     */
    /* default */  boolean cancel();

    /**
     * @return a token that is never cancelled
     * <p>Useful for operations that should not be interrupted.</p>
     */
    /* default */
    static CancellationToken none() {
        return NeverCancelledToken.INSTANCE;
    }

    /**
     * @return a token that is already cancelled
     * <p>Useful for testing or immediate cancellation scenarios.</p>
     */
    /* default */
    static CancellationToken cancelled() {
        return PreCancelledToken.INSTANCE;
    }
}
