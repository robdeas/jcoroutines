/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/internal/BaseChannel.java
 description: Abstract base for coroutine channels. Provides send/receive logic, cancellation
              handling, optional empty/null sentinels, and lifecycle control.
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

package tech.robd.jcoroutines.internal;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.NullPayloadException;
import tech.robd.jcoroutines.SuspendContext;
import tech.robd.jcoroutines.diagnostics.Diagnostics;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base for coroutine channels.
 *
 * <p>Provides:
 * <ul>
 *   <li>Backed by a {@link BlockingQueue} with sentinels for closed/empty.</li>
 *   <li>Cancellation-aware send/receive methods with interrupt handling.</li>
 *   <li>Optional payload support via {@link Optional} and {@link #OPTIONAL_EMPTY} sentinel.</li>
 *   <li>Lifecycle control via {@link #close()} and {@link ClosedReceiveException}.</li>
 *   <li>Lightweight diagnostics via {@link Diagnostics}.</li>
 * </ul>
 *
 * @param <T> channel element type
 */
public abstract class BaseChannel<T> {

    // 🧩 Section: diagnostics
    /**
     * Snapshot is fine — Gradle sets -Djcoroutines.diag=true before tests start.
     */
    private static final Diagnostics DIAG = Diagnostics.of(BaseChannel.class);
    // [/🧩 Section: diagnostics]

    // 🧩 Section: state
    private final int chId = System.identityHashCode(this);
    protected final @NonNull BlockingQueue<@Nullable Object> queue;
    protected volatile boolean closed = false;

    /**
     * Sentinel object used to mark closed channels.
     */
    protected static final @NonNull Object CLOSED = new Object() {
        @Override
        public String toString() {
            return "CLOSED_SENTINEL";
        }
    };

    /**
     * Sentinel object used to represent {@code Optional.empty()}.
     */
    protected static final @NonNull Object OPTIONAL_EMPTY = new Object() {
        @Override
        public String toString() {
            return "OPTIONAL_EMPTY_SENTINEL";
        }
    };
    // [/🧩 Section: state]

    // 🧩 Section: construction
    protected BaseChannel(@NonNull BlockingQueue<@Nullable Object> q) {
        this.queue = q;
        DIAG.debug("ch#{} init queue={}", chId, q.getClass().getSimpleName());
    }
    // [/🧩 Section: construction]

    // 🧩 Section: receive

    /**
     * Blocking receive with cancellation support.
     *
     * @param s suspend context
     * @return optional value (never null)
     * @throws ClosedReceiveException if channel is closed
     */
    protected @NonNull Optional<T> receiveOptional(@NonNull SuspendContext s) throws ClosedReceiveException {
        s.checkCancellation();
        Thread current = Thread.currentThread();

        // 🧩 Point: receive/register-cancel
        AutoCloseable reg = s.getCancellationToken().onCancel(() -> {
            DIAG.debug("ch#{} recv cancelled -> interrupt thread", chId);
            current.interrupt();
        });

        try {
            for (; ; ) {
                Object o = queue.poll(10, TimeUnit.MILLISECONDS);
                s.checkCancellation();

                if (o == null) {
                    if (closed) {
                        DIAG.debug("ch#{} recv -> CLOSED (empty)", chId);
                        throw new ClosedReceiveException();
                    }
                    continue;
                }

                if (o == CLOSED) {
                    DIAG.debug("ch#{} recv CLOSED sentinel (drain mode)", chId);
                    continue; // drain before throwing
                }

                if (o == OPTIONAL_EMPTY) {
                    DIAG.debug("ch#{} recv OPTIONAL_EMPTY", chId);
                    return Optional.empty();
                }

                @SuppressWarnings("unchecked") T t = (T) o;
                DIAG.debug("ch#{} recv ok ", chId);
                return Optional.of(t);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            DIAG.warn("ch#{} recv interrupted", chId);
            throw new CancellationException("Interrupted while receiving");
        } finally {
            try {
                reg.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Try receive without blocking.
     */
    protected @NonNull Optional<T> tryReceiveOptional() {
        Object o = queue.poll();
        if (o == null) {
            if (closed) {
                DIAG.debug("ch#{} tryRecv -> CLOSED (empty)", chId);
                throw new ClosedReceiveException();
            }
            return Optional.empty();
        }
        if (o == CLOSED) {
            DIAG.debug("ch#{} tryRecv CLOSED sentinel", chId);
            throw new ClosedReceiveException();
        }
        if (o == OPTIONAL_EMPTY) {
            DIAG.debug("ch#{} tryRecv OPTIONAL_EMPTY", chId);
            return Optional.empty();
        }
        @SuppressWarnings("unchecked") T t = (T) o;
        DIAG.debug("ch#{} tryRecv ok (null?={})", chId, (t == null));
        return Optional.of(t);
    }
    // [/🧩 Section: receive]

    // 🧩 Section: send

    /**
     * Internal send with cancellation awareness.
     *
     * @param s    suspend context
     * @param item payload (must be non-null except for sentinels)
     */
    protected void sendInternal(@NonNull SuspendContext s, @Nullable Object item) {
        s.checkCancellation();
        if (item == null) {
            DIAG.error("ch#{} send rejected: null payload", chId);
            throw new NullPayloadException("Null payload not allowed on strict Channel");
        }
        Thread current = Thread.currentThread();

        try (AutoCloseable reg = s.getCancellationToken().onCancel(() -> {
            DIAG.debug("ch#{} send cancelled -> interrupt thread", chId);
            current.interrupt();
        })) {
            try {
                if (closed) {
                    DIAG.warn("ch#{} send rejected: closed", chId);
                    throw new IllegalStateException("Channel is closed");
                }
                queue.put(item);
                if (item == OPTIONAL_EMPTY) {
                    DIAG.debug("ch#{} send OPTIONAL_EMPTY ok", chId);
                } else if (item == CLOSED) {
                    DIAG.debug("ch#{} send CLOSED sentinel (unexpected)", chId);
                } else {
                    DIAG.debug("ch#{} send ok", chId);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                DIAG.warn("ch#{} send interrupted", chId);
                throw new CancellationException("Interrupted while sending");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Convenience: enqueue an {@link Optional#empty()} marker.
     */
    protected void sendOptionalEmpty(@NonNull SuspendContext s) {
        DIAG.debug("ch#{} send OPTIONAL_EMPTY -> enqueue", chId);
        sendInternal(s, OPTIONAL_EMPTY);
    }
    // [/🧩 Section: send]

    // 🧩 Section: lifecycle
    public int getChannelId() {
        return chId;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Close channel and enqueue CLOSED sentinel to wake receivers.
     */
    public void close() {
        if (!closed) {
            closed = true;
            DIAG.debug("ch#{} close() invoked", chId);
            boolean offered = queue.offer(CLOSED);
            if (!offered) {
                try {
                    boolean ok = queue.offer(CLOSED, 1, TimeUnit.MILLISECONDS);
                    DIAG.debug("ch#{} close: wake sent (timed=1ms, ok={})", chId, ok);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    DIAG.warn("ch#{} close: interrupted while waking", chId);
                }
            } else {
                DIAG.debug("ch#{} close: wake sent (immediate)", chId);
            }
        } else {
            DIAG.debug("ch#{} close() ignored (already closed)", chId);
        }
    }

    /**
     * Exception thrown when attempting to receive from a closed channel.
     */
    public static class ClosedReceiveException extends RuntimeException {
        public ClosedReceiveException() {
            super("Channel closed");
        }
    }
    // [/🧩 Section: lifecycle]
}
