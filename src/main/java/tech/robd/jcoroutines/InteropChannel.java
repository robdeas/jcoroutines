/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/InteropChannel.java
 description: Interop-friendly channel that bridges Kotlin nullability with Java Optional, never storing raw null in the underlying queue.
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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.internal.BaseChannel;

import java.util.Optional;
import java.util.concurrent.*;

/**
 * An interop-friendly channel that never stores raw {@code null} in its internal {@link BlockingQueue}.
 * <p>
 * Values sent as {@code null} are encoded to a sentinel token ({@link #OPTIONAL_EMPTY}) so that:
 * </p>
 * <ul>
 *   <li>Kotlin producers/consumers can use nullable types naturally.</li>
 *   <li>Java consumers receive {@link Optional}{@code <T>} from {@link #receive(SuspendContext)} and
 *   {@link #tryReceive()}, where {@code Optional.empty()} corresponds to Kotlin {@code null}.</li>
 * </ul>
 *
 * <p><strong>Blocking behavior:</strong> The blocking variants cooperate with cancellation through
 * the explicit {@link SuspendContext}. When the channel is closed, blocking receives throw
 * {@link ClosedReceiveException} to signal normal termination.</p>
 *
 * @param <T> element type (nullable for send/receiveNullable)
 * @author Rob Deas
 * @since 0.1.0
 */
public final class InteropChannel<T> extends BaseChannel<T> {

    private InteropChannel(@NonNull BlockingQueue<@Nullable Object> q) {
        super(q);
    }

    // 🧩 Section: factories

    /**
     * Create an unbounded channel backed by a {@link LinkedBlockingQueue}.
     */
    public static <T> @NonNull InteropChannel<T> unlimited() {
        return new InteropChannel<>(new LinkedBlockingQueue<>());
    }

    /**
     * Create a fixed-capacity buffered channel.
     *
     * @param capacity positive capacity
     * @return a channel backed by an {@link ArrayBlockingQueue} of the given capacity
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public static <T> @NonNull InteropChannel<T> buffered(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        return new InteropChannel<>(new ArrayBlockingQueue<>(capacity));
    }

    /**
     * Create a rendezvous channel (capacity 0) backed by a {@link SynchronousQueue}.
     */
    public static <T> @NonNull InteropChannel<T> rendezvous() {
        return new InteropChannel<>(new SynchronousQueue<>());
    }
    // [/🧩 Section: factories]

    // 🧩 Section: constants
    /**
     * Public alias of the internal null sentinel (used to encode/decode {@code Optional.empty()} / Kotlin {@code null}).
     */
    public static final Object OPTIONAL_EMPTY = BaseChannel.OPTIONAL_EMPTY;
    // [/🧩 Section: constants]

    // 🧩 Section: send-receive

    /**
     * Send a value (nullable allowed). Raw {@code null} is encoded as {@link #OPTIONAL_EMPTY},
     * ensuring the underlying queue never stores {@code null}.
     *
     * @param s    suspending context; must be non-null
     * @param item value to send (may be {@code null})
     * @throws IllegalArgumentException                   if {@code s} is {@code null}
     * @throws java.util.concurrent.CancellationException if the context is cancelled while sending
     */
    public void send(@NonNull SuspendContext s, @Nullable T item) {
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        if (item == null) {
            sendOptionalEmpty(s);
        } else {
            sendInternal(s, item);
        }
    }

    /**
     * Send an explicit empty token (equivalent to sending {@code null}).
     *
     * @param s suspending context; must be non-null
     * @throws IllegalArgumentException                   if {@code s} is {@code null}
     * @throws java.util.concurrent.CancellationException if the context is cancelled while sending
     */
    public void sendEmpty(@NonNull SuspendContext s) {
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        sendOptionalEmpty(s);
    }

    /**
     * Blocking receive returning an {@link Optional}. {@code Optional.empty()} corresponds to Kotlin {@code null}.
     *
     * @param s suspending context; must be non-null
     * @return an {@link Optional} value (empty == Kotlin {@code null})
     * @throws IllegalArgumentException                   if {@code s} is {@code null}
     * @throws ClosedReceiveException                     when the channel is closed (normal termination signal)
     * @throws java.util.concurrent.CancellationException if the context is cancelled while receiving
     */
    public @NonNull Optional<T> receive(@NonNull SuspendContext s) {
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        return receiveOptional(s);
    }

    /**
     * Convenience receive variant that directly yields {@code null} to represent an empty {@link Optional}.
     *
     * @param s suspending context; must be non-null
     * @return the element or {@code null} (maps from {@code Optional.empty()})
     * @throws IllegalArgumentException                   if {@code s} is {@code null}
     * @throws ClosedReceiveException                     when the channel is closed
     * @throws java.util.concurrent.CancellationException if the context is cancelled while receiving
     */
    public @Nullable T receiveNullable(@NonNull SuspendContext s) {
        return receive(s).orElse(null);
    }
    // [/🧩 Section: send-receive]

    // 🧩 Section: nonblocking

    /**
     * Try a non-blocking send. {@code null} is encoded as {@link #OPTIONAL_EMPTY}.
     *
     * @param item value to try to send (may be {@code null})
     * @return {@code true} if enqueued; {@code false} if the channel is closed or the queue is full
     */
    public boolean trySend(@Nullable T item) {
        if (closed) return false;
        return queue.offer(item == null ? OPTIONAL_EMPTY : item);
    }

    /**
     * Try a non-blocking receive. Empty {@link Optional} corresponds to Kotlin {@code null}.
     *
     * @return an {@link Optional} that may be empty (Kotlin {@code null}) or contain a value
     */
    public @NonNull Optional<T> tryReceive() {
        return tryReceiveOptional();
    }
    // [/🧩 Section: nonblocking]

    // 🧩 Section: iteration

    /**
     * Iterates over channel values until closed, invoking {@code action} for each element.
     * <p>
     * The consumer receives an {@link Optional}{@code <T>} where empty corresponds to Kotlin {@code null}.
     * Any checked exception thrown by {@code action} is wrapped in a {@link CompletionException}.
     * Channel closure is signaled by {@link ClosedReceiveException} and is treated as normal termination.
     * </p>
     *
     * @param s      suspending context; must be non-null
     * @param action consumer receiving each element wrapped in {@link Optional}
     * @throws IllegalArgumentException if {@code s} or {@code action} is {@code null}
     * @throws CompletionException      if {@code action} throws a checked exception
     */
    public void forEach(@NonNull SuspendContext s, @NonNull InteropConsumer<T> action) {
        if (s == null || action == null) throw new IllegalArgumentException("Args cannot be null");
        try {
            for (; ; ) {
                Optional<T> opt = receive(s); // throws on close
                try {
                    action.accept(s, opt);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }
        } catch (ClosedReceiveException ignored) {
            // normal termination
        }
    }
    // [/🧩 Section: iteration]

    /**
     * Exception thrown by blocking receive operations when the channel is closed.
     */
    public static final class ClosedReceiveException extends BaseChannel.ClosedReceiveException {
    }
}
