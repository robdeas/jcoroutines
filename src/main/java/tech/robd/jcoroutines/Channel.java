/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/Channel.java
 description: Public coroutine channel built on BaseChannel. Provides unlimited, buffered,
              and rendezvous factories plus send/receive/forEach operations.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
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
import tech.robd.jcoroutines.diagnostics.Diagnostics;
import tech.robd.jcoroutines.internal.BaseChannel;

import java.util.Optional;
import java.util.concurrent.*;

/**
 * Public coroutine channel implementation backed by {@link BaseChannel}.
 *
 * <p>Provides factory methods:
 * <ul>
 *   <li>{@link #unlimited()} – backed by {@link LinkedBlockingQueue}</li>
 *   <li>{@link #buffered(int)} – backed by {@link ArrayBlockingQueue}</li>
 *   <li>{@link #rendezvous()} – backed by {@link SynchronousQueue}</li>
 * </ul>
 *
 * <p>Operations:
 * <ul>
 *   <li>{@link #send(SuspendContext, Object)} / {@link #trySend(Object)}</li>
 *   <li>{@link #receive(SuspendContext)} / {@link #tryReceive()}</li>
 *   <li>{@link #forEach(SuspendContext, SuspendConsumer)} with termination on close</li>
 *   <li>{@link #forEachIgnoreNulls(SuspendContext, SuspendConsumer)} for interop null tolerance</li>
 * </ul>
 *
 * @param <T> channel element type
 */
public final class Channel<T> extends BaseChannel<T> {
    private static final Diagnostics DIAG = Diagnostics.of(Channel.class);

    // 🧩 Section: construction
    private Channel(@NonNull BlockingQueue<@Nullable Object> channelQueue) {
        super(channelQueue);
        DIAG.debug("Channel created with queue type: {}", channelQueue.getClass().getSimpleName());
    }

    /**
     * Unlimited channel backed by a {@link LinkedBlockingQueue}.
     */
    public static <T> @NonNull Channel<T> unlimited() {
        DIAG.debug("Creating unlimited channel");
        return new Channel<>(new LinkedBlockingQueue<>());
    }

    /**
     * Bounded channel backed by an {@link ArrayBlockingQueue}.
     */
    public static <T> @NonNull Channel<T> buffered(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        DIAG.debug("Creating buffered channel with capacity: {}", capacity);
        return new Channel<>(new ArrayBlockingQueue<>(capacity));
    }

    /**
     * Rendezvous channel backed by a {@link SynchronousQueue}.
     */
    public static <T> @NonNull Channel<T> rendezvous() {
        DIAG.debug("Creating rendezvous channel");
        return new Channel<>(new SynchronousQueue<>());
    }
    // [/🧩 Section: construction]

    // 🧩 Section: send

    /**
     * Send a non-null item (blocking if necessary).
     *
     * @throws NullPayloadException if item is null (use InteropChannel instead)
     */
    public void send(@NonNull SuspendContext s, @NonNull T item) {
        DIAG.debug("Attempting to send item on thread: {}", Thread.currentThread().getName());
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        s.checkCancellation();
        if (item == null)
            throw new NullPayloadException("Null payloads are not allowed; use InteropChannel<T> instead");
        sendInternal(s, item);
        DIAG.debug("Item sent successfully");
    }

    /**
     * Try non-blocking send; returns false if queue full or channel closed.
     */
    public boolean trySend(@NonNull T item) {
        if (item == null) throw new IllegalArgumentException("Item cannot be null – use InteropChannel for nulls");
        boolean success = !closed && queue.offer(item);
        DIAG.debug("trySend result: {}, channel closed: {}", success, closed);
        return success;
    }
    // [/🧩 Section: send]

    // 🧩 Section: receive

    /**
     * Receive a value (blocking), throwing if channel closed or payload null.
     */
    public @NonNull T receive(@NonNull SuspendContext s) throws ClosedReceiveException {
        DIAG.debug("Attempting to receive on thread: {}", Thread.currentThread().getName());
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        s.checkCancellation();
        T result = receiveOptional(s).orElseThrow(NullPayloadException::new);
        DIAG.debug("Received item from channel");
        return result;
    }

    /**
     * Receive ignoring nulls, retrying until a non-null value arrives.
     */
    public @NonNull T receiveIgnoreNulls(@NonNull SuspendContext s) throws ClosedReceiveException {
        if (s == null) throw new IllegalArgumentException("SuspendContext cannot be null");
        DIAG.debug("Starting receiveIgnoreNulls loop");
        int attempts = 0;
        for (; ; ) {
            Optional<T> opt = receiveOptional(s);
            attempts++;
            if (opt.isPresent()) {
                DIAG.debug("receiveIgnoreNulls succeeded after {} attempts", attempts);
                return opt.get();
            }
            DIAG.debug("Ignoring null in receiveIgnoreNulls (attempt {})", attempts);
        }
    }

    /**
     * Try non-blocking receive; returns null if none available, throws if closed.
     */
    public @Nullable T tryReceive() {
        var opt = tryReceiveOptional();
        if (opt.isEmpty() && closed) {
            DIAG.debug("tryReceive on closed channel -> throwing");
            throw new ClosedReceiveException();
        }
        T result = opt.orElse(null);
        DIAG.debug("tryReceive result: {}", result != null ? "item" : "null");
        return result;
    }
    // [/🧩 Section: receive]

    // 🧩 Section: iteration

    /**
     * Loop consuming items until channel closed.
     */
    public void forEach(@NonNull SuspendContext s, @NonNull SuspendConsumer<T> action) {
        if (s == null || action == null) throw new IllegalArgumentException("Args cannot be null");
        DIAG.debug("Starting forEach loop");
        int count = 0;
        try {
            for (; ; ) {
                T v = receive(s);
                count++;
                DIAG.debug("forEach processing item {}", count);
                action.accept(s, v);
            }
        } catch (ClosedReceiveException ignored) {
            DIAG.debug("forEach completed normally after {} items", count);
        } catch (RuntimeException re) {
            DIAG.error("RuntimeException in forEach after {} items", count, re);
            throw re;
        } catch (Exception e) {
            DIAG.error("Checked exception in forEach after {} items", count, e);
            throw new RuntimeException("Error in channel consumer", e);
        }
    }

    /**
     * Loop consuming non-null items until channel closed, ignoring null payloads.
     */
    public void forEachIgnoreNulls(@NonNull SuspendContext s, @NonNull SuspendConsumer<T> action) {
        if (s == null || action == null) throw new IllegalArgumentException("Args cannot be null");
        DIAG.debug("Starting forEachIgnoreNulls loop");
        int itemCount = 0, nullCount = 0;
        try {
            for (; ; ) {
                Optional<T> opt = receiveOptional(s);
                if (opt.isPresent()) {
                    itemCount++;
                    DIAG.debug("forEachIgnoreNulls processing item {} (ignored {} nulls)", itemCount, nullCount);
                    action.accept(s, opt.get());
                } else {
                    nullCount++;
                    DIAG.debug("Ignoring null in forEachIgnoreNulls (null #{}, items={})", nullCount, itemCount);
                }
            }
        } catch (ClosedReceiveException ignored) {
            DIAG.debug("forEachIgnoreNulls completed after {} items, {} nulls ignored", itemCount, nullCount);
        } catch (RuntimeException re) {
            DIAG.error("RuntimeException in forEachIgnoreNulls", re);
            throw re;
        } catch (Exception e) {
            DIAG.error("Checked exception in forEachIgnoreNulls", e);
            throw new CompletionException(e);
        }
    }
    // [/🧩 Section: iteration]
}
