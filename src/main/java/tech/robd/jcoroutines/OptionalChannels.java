/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/OptionalChannels.java
 description: Optional-friendly helpers over InteropChannel. Java uses Optional<T>; Kotlin uses nullable T. No raw null is stored internally.
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

import java.util.Optional;

/**
 * Optional-friendly helpers over {@link InteropChannel}.
 * <p>
 * Java callers use {@link Optional}{@code <T>} while Kotlin callers can use {@code T?}.
 * Internally, {@code null} is encoded so the underlying queue never stores raw {@code null}.
 * </p>
 *
 * <h2>Cancellation &amp; closure</h2>
 * <ul>
 *   <li>Blocking operations require an explicit {@link SuspendContext} and may throw
 *   {@link java.util.concurrent.CancellationException} if the context is cancelled.</li>
 *   <li>Channel closure is surfaced by {@link InteropChannel.ClosedReceiveException} from
 *   {@link #receive(SuspendContext, InteropChannel)} and {@link #receiveNullable(SuspendContext, InteropChannel)}.</li>
 * </ul>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class OptionalChannels {
    private OptionalChannels() {
    }

    // [🧩 Section: factories]

    /**
     * Create an unbounded channel backed by a {@link java.util.concurrent.LinkedBlockingQueue}.
     */
    public static <T> @NonNull InteropChannel<T> unlimited() {
        return InteropChannel.unlimited();
    }

    /**
     * Create a fixed-capacity buffered channel.
     *
     * @param capacity positive capacity
     * @return a channel with the given capacity
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public static <T> @NonNull InteropChannel<T> buffered(int capacity) {
        return InteropChannel.buffered(capacity);
    }

    /**
     * Create a rendezvous (capacity 0) channel.
     */
    public static <T> @NonNull InteropChannel<T> rendezvous() {
        return InteropChannel.rendezvous();
    }
    // [/🧩 Section: factories]

    // [🧩 Section: java-optional]

    /**
     * Send an {@link Optional} payload: present ⟶ value, empty ⟶ {@code null} (encoded internally).
     *
     * @param s     suspending context (non-null)
     * @param ch    target channel (non-null)
     * @param value non-null {@link Optional}; empty maps to {@code null}
     * @throws IllegalArgumentException                   if any argument is {@code null}
     * @throws java.util.concurrent.CancellationException if the context is cancelled while sending
     */
    public static <T> void send(@NonNull SuspendContext s,
                                @NonNull InteropChannel<T> ch,
                                @NonNull Optional<T> value) {
        if (s == null || ch == null || value == null) {
            throw new IllegalArgumentException("Args cannot be null");
        }
        if (value.isPresent()) ch.send(s, value.get());
        else ch.sendEmpty(s); // identical to send(s, null)
    }

    /**
     * Receive as {@link Optional} (empty if a {@code null} payload was sent).
     *
     * @param s  suspending context (non-null)
     * @param ch channel (non-null)
     * @return an {@link Optional} that is empty if a {@code null} payload was sent
     * @throws IllegalArgumentException                   if any argument is {@code null}
     * @throws InteropChannel.ClosedReceiveException      when the channel is closed (normal termination)
     * @throws java.util.concurrent.CancellationException if the context is cancelled while receiving
     */
    public static <T> @NonNull Optional<T> receive(@NonNull SuspendContext s,
                                                   @NonNull InteropChannel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Args cannot be null");
        return ch.receive(s);
    }
    // [/🧩 Section: java-optional]

    // [🧩 Section: kotlin-null]

    /**
     * Send a nullable value directly. {@code null} is mapped to {@link Optional#empty()} for Java receivers.
     *
     * @param s     suspending context (non-null)
     * @param ch    channel (non-null)
     * @param value nullable value
     * @throws IllegalArgumentException                   if {@code s} or {@code ch} is {@code null}
     * @throws java.util.concurrent.CancellationException if the context is cancelled while sending
     */
    public static <T> void sendNullable(@NonNull SuspendContext s,
                                        @NonNull InteropChannel<T> ch,
                                        @Nullable T value) {
        if (s == null || ch == null) throw new IllegalArgumentException("Args cannot be null");
        if (value == null) ch.sendEmpty(s);
        else ch.send(s, value);
    }

    /**
     * Receive as a nullable value. {@code null} corresponds to {@link Optional#empty()} on the Java side.
     *
     * @param s  suspending context (non-null)
     * @param ch channel (non-null)
     * @return the value or {@code null} if an empty payload was sent
     * @throws IllegalArgumentException                   if {@code s} or {@code ch} is {@code null}
     * @throws InteropChannel.ClosedReceiveException      when the channel is closed
     * @throws java.util.concurrent.CancellationException if the context is cancelled while receiving
     */
    public static <T> @Nullable T receiveNullable(@NonNull SuspendContext s,
                                                  @NonNull InteropChannel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Args cannot be null");
        return ch.receiveNullable(s);
    }
    // [/🧩 Section: kotlin-null]

    // [🧩 Section: lifecycle]

    /**
     * Close the channel. Pending receivers will see {@link InteropChannel.ClosedReceiveException}.
     *
     * @param ch channel to close (non-null)
     * @throws IllegalArgumentException if {@code ch} is {@code null}
     */
    public static void close(@NonNull InteropChannel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        ch.close();
    }
    // [/🧩 Section: lifecycle]
}
