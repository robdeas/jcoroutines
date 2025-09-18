/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/NullPayloadException.java
 description: Runtime exception indicating a null payload was received on a channel that does not permit null values.
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
 * Thrown when a {@code null} payload is observed on a channel that does not allow {@code null} values.
 * <p>
 * For mixed Kotlin/Java scenarios, prefer {@link InteropChannel} which represents nullable elements
 * via {@link java.util.Optional#empty()} at the API boundary, or use helpers that skip {@code null}
 * values when appropriate (e.g., {@code receiveIgnoreNulls()} where provided).
 * </p>
 *
 * <p><strong>When you might see this:</strong> If a producer accidentally enqueues {@code null} into a
 * non-null {@code Channel<T>}, the consumer may fail fast with this exception to highlight a
 * contract violation. Interop channels use a dedicated sentinel and never store raw {@code null}.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class NullPayloadException extends RuntimeException {

    // [🧩 Section: api]

    /**
     * Creates an exception with the default message.
     */
    public NullPayloadException() {
        super("Null payload received on a non-null channel");
    }

    /**
     * Creates an exception with a custom message.
     */
    public NullPayloadException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a custom message and root cause.
     */
    public NullPayloadException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Factory for the common channel/interop mismatch case: a consumer of {@code Channel<T>} received
     * a {@code null} value. Suggests using {@link InteropChannel} or a null-skipping receive helper.
     *
     * @return a {@link NullPayloadException} with a helpful guidance message
     */
    public static NullPayloadException forChannelMismatch() {
        return new NullPayloadException(
                "Received null on Channel<T>. Use InteropChannel<T> for mixed Kotlin/Java, " +
                        "or receiveIgnoreNulls() to skip null values."
        );
    }
    // [/🧩 Section: api]
}
