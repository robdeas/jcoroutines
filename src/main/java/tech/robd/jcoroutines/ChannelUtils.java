/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/ChannelUtils.java
 description: Static helper/factory methods for Channel and InteropChannel.
              Provides unified creation, send/receive, trySend/tryReceive, and state checks.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 [/File Info]
*/
package tech.robd.jcoroutines;
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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.diagnostics.Diagnostics;

import java.util.Objects;

/**
 * Utility class for working with {@link Channel} and {@link InteropChannel}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Factory methods for unlimited, buffered, and rendezvous channels.</li>
 *   <li>Convenience send/receive wrappers with null-checking.</li>
 *   <li>Helpers for non-blocking trySend/tryReceive.</li>
 *   <li>State inspection (closed, empty).</li>
 * </ul>
 */
public final class ChannelUtils {
    private static final Diagnostics DIAG = Diagnostics.of(ChannelUtils.class);

    private ChannelUtils() {
    }

    // [🧩 Section: factories-strict]

    /**
     * Create unlimited capacity channel (LinkedBlockingQueue).
     */
    public static <T> @NonNull Channel<T> unlimited() {
        DIAG.debug("Creating unlimited channel via ChannelUtils");
        return Channel.unlimited();
    }

    /**
     * Create buffered channel with fixed capacity (ArrayBlockingQueue).
     */
    public static <T> @NonNull Channel<T> buffered(int cap) {
        DIAG.debug("Creating buffered channel with capacity {} via ChannelUtils", cap);
        return Channel.buffered(cap);
    }

    /**
     * Create rendezvous channel (SynchronousQueue).
     */
    public static <T> @NonNull Channel<T> rendezvous() {
        DIAG.debug("Creating rendezvous channel via ChannelUtils");
        return Channel.rendezvous();
    }
    // [/🧩 Section: factories-strict]

    // [🧩 Section: send-receive-strict]

    /**
     * Send a non-null item to a channel.
     */
    public static <T> void send(@NonNull SuspendContext s, @NonNull Channel<T> ch, @NonNull T item) {
        Objects.requireNonNull(s, "SuspendContext cannot be null");
        Objects.requireNonNull(ch, "Channel cannot be null");
        Objects.requireNonNull(item, "item cannot be null");
        DIAG.debug("ChannelUtils.send() delegating to channel");
        ch.send(s, item);
    }

    /**
     * Receive a non-null item from a channel.
     */
    public static <T> @NonNull T receive(@NonNull SuspendContext s, @NonNull Channel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Arguments cannot be null");
        DIAG.debug("ChannelUtils.receive() delegating to channel");
        return ch.receive(s);
    }

    /**
     * Receive from channel, ignoring any null values (interop tolerance).
     */
    public static <T> @NonNull T receiveIgnoreNulls(@NonNull SuspendContext s, @NonNull Channel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Arguments cannot be null");
        DIAG.debug("ChannelUtils.receiveIgnoreNulls() delegating to channel");
        return ch.receiveIgnoreNulls(s);
    }

    /**
     * Try to send without blocking.
     */
    public static <T> boolean trySend(@NonNull Channel<T> ch, @NonNull T item) {
        if (ch == null || item == null) throw new IllegalArgumentException("Arguments cannot be null");
        boolean result = ch.trySend(item);
        DIAG.debug("ChannelUtils.trySend() result: {}", result);
        return result;
    }

    /**
     * Try to receive without blocking.
     */
    public static <T> T tryReceive(@NonNull Channel<T> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        T result = ch.tryReceive();
        DIAG.debug("ChannelUtils.tryReceive() result: {}", result != null ? "item" : "null");
        return result;
    }

    /**
     * Close a channel.
     */
    public static void close(@NonNull Channel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        DIAG.debug("ChannelUtils.close() closing channel");
        ch.close();
    }
    // [/🧩 Section: send-receive-strict]

    // [🧩 Section: factories-interop]

    /**
     * Create unlimited capacity interop channel.
     */
    public static <T> @NonNull InteropChannel<T> unlimitedInterop() {
        DIAG.debug("Creating unlimited interop channel via ChannelUtils");
        return InteropChannel.unlimited();
    }

    /**
     * Create buffered interop channel with fixed capacity.
     */
    public static <T> @NonNull InteropChannel<T> bufferedInterop(int cap) {
        DIAG.debug("Creating buffered interop channel with capacity {} via ChannelUtils", cap);
        return InteropChannel.buffered(cap);
    }

    /**
     * Create rendezvous interop channel.
     */
    public static <T> @NonNull InteropChannel<T> rendezvousInterop() {
        DIAG.debug("Creating rendezvous interop channel via ChannelUtils");
        return InteropChannel.rendezvous();
    }
    // [/🧩 Section: factories-interop]

    // [🧩 Section: send-receive-interop]

    /**
     * Send a potentially null item to an interop channel.
     */
    public static <T> void sendInterop(@NonNull SuspendContext s, @NonNull InteropChannel<T> ch, @Nullable T item) {
        Objects.requireNonNull(s, "SuspendContext cannot be null");
        Objects.requireNonNull(ch, "Channel cannot be null");
        DIAG.debug("ChannelUtils.sendInterop() sending {} item", item != null ? "non-null" : "null");
        ch.send(s, item);
    }

    /**
     * Send an explicit empty value to an interop channel.
     */
    public static <T> void sendEmpty(@NonNull SuspendContext s, @NonNull InteropChannel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Arguments cannot be null");
        DIAG.debug("ChannelUtils.sendEmpty() sending empty value");
        ch.sendEmpty(s);
    }

    /**
     * Receive from interop channel as Optional.
     */
    public static <T> java.util.Optional<T> receiveInterop(@NonNull SuspendContext s, @NonNull InteropChannel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Arguments cannot be null");
        DIAG.debug("ChannelUtils.receiveInterop() delegating to interop channel");
        return ch.receive(s);
    }

    /**
     * Receive from interop channel as nullable.
     */
    public static <T> T receiveNullable(@NonNull SuspendContext s, @NonNull InteropChannel<T> ch) {
        if (s == null || ch == null) throw new IllegalArgumentException("Arguments cannot be null");
        DIAG.debug("ChannelUtils.receiveNullable() delegating to interop channel");
        return ch.receiveNullable(s);
    }

    /**
     * Try to send to interop channel without blocking.
     */

    public static <T> boolean trySendInterop(@NonNull InteropChannel<T> ch, T item) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        boolean result = ch.trySend(item);
        DIAG.debug("ChannelUtils.trySendInterop() result: {}", result);
        return result;
    }

    /**
     * Try to receive from interop channel without blocking.
     */
    public static <T> java.util.Optional<T> tryReceiveInterop(@NonNull InteropChannel<T> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        var result = ch.tryReceive();
        DIAG.debug("ChannelUtils.tryReceiveInterop() result: {}", result.isPresent() ? "present" : "empty");
        return result;
    }

    /**
     * Close an interop channel.
     */
    public static void close(@NonNull InteropChannel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        DIAG.debug("ChannelUtils.close() closing interop channel");
        ch.close();
    }
    // [/🧩 Section: send-receive-interop]

    // [🧩 Section: utilities]

    /**
     * Check if a channel is closed.
     */
    public static boolean isClosed(@NonNull Channel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        boolean result = ch.isClosed();
        DIAG.debug("ChannelUtils.isClosed() channel closed: {}", result);
        return result;
    }

    /**
     * Check if an interop channel is closed.
     */
    public static boolean isClosed(@NonNull InteropChannel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        boolean result = ch.isClosed();
        DIAG.debug("ChannelUtils.isClosed() interop channel closed: {}", result);
        return result;
    }

    /**
     * Check if a channel is empty.
     */
    public static boolean isEmpty(@NonNull Channel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        boolean result = ch.isEmpty();
        DIAG.debug("ChannelUtils.isEmpty() channel empty: {}", result);
        return result;
    }

    /**
     * Check if an interop channel is empty.
     */
    public static boolean isEmpty(@NonNull InteropChannel<?> ch) {
        if (ch == null) throw new IllegalArgumentException("Channel cannot be null");
        boolean result = ch.isEmpty();
        DIAG.debug("ChannelUtils.isEmpty() interop channel empty: {}", result);
        return result;
    }
    // [/🧩 Section: utilities]
}
