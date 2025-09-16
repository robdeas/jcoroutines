/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/internal/JCoroutineHandleImpl.java
 description: Default implementation of JCoroutineHandle. Wraps a CompletableFuture
              and a CancellationToken, synchronizing their lifecycle and logging.
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

import tech.robd.jcoroutines.CancellationToken;
import tech.robd.jcoroutines.diagnostics.Diagnostics;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Default implementation of {@link JCoroutineHandle} that wraps a {@link CompletableFuture}
 * and provides cancellation through a {@link CancellationToken}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Link token → future: cancelling the token cancels the future.</li>
 *   <li>Link future → token: completing/cancelling the future updates the token.</li>
 *   <li>Ensure proper cleanup by marking the token completed when done.</li>
 *   <li>Provide introspection and consistent logging via {@link Diagnostics}.</li>
 * </ul>
 *
 * @param <T> result type of the coroutine
 */
public final class JCoroutineHandleImpl<T> implements JCoroutineHandle<T> {

    // 🧩 Section: diagnostics
    private static final Diagnostics DIAG = Diagnostics.of(JCoroutineHandleImpl.class);
    // [/🧩 Section: diagnostics]

    // 🧩 Section: state
    private final int handleId = System.identityHashCode(this);
    private final CompletableFuture<T> future;
    private final CancellationToken cancellationToken;
    // [/🧩 Section: state]

    // 🧩 Section: construction
    public JCoroutineHandleImpl(CompletableFuture<T> future, CancellationToken cancellationToken) {
        if (future == null) throw new IllegalArgumentException("Future cannot be null");
        if (cancellationToken == null) throw new IllegalArgumentException("CancellationToken cannot be null");

        this.future = future;
        this.cancellationToken = cancellationToken;

        DIAG.debug("hdl#{} create", handleId);

        // 🧩 Point: construction/token→future
        cancellationToken.onCancel(() -> {
            DIAG.debug("hdl#{} token->future cancel", handleId);
            future.cancel(true);
        });

        // 🧩 Point: construction/future→token
        future.whenComplete((result, throwable) -> {
            if (future.isCancelled()) {
                DIAG.debug("hdl#{} completed: CANCELLED", handleId);
                if (!cancellationToken.isCancelled()) {
                    DIAG.debug("hdl#{} future->token cancel", handleId);
                    cancellationToken.cancel();
                }
            } else if (throwable != null) {
                DIAG.debug("hdl#{} completed: EX ({})", handleId, throwable.getClass().getSimpleName());
                markTokenCompleted();
            } else {
                DIAG.debug("hdl#{} completed: OK", handleId);
                markTokenCompleted();
            }
        });
    }
    // [/🧩 Section: construction]

    // 🧩 Section: helpers
    private void markTokenCompleted() {
        if (cancellationToken instanceof CancellationTokenImpl) {
            try {
                ((CancellationTokenImpl) cancellationToken).markCompleted();
                DIAG.debug("hdl#{} marked token completed", handleId);
            } catch (Exception e) {
                DIAG.debug("hdl#{} error marking token completed: {}", handleId, e.toString());
            }
        }
    }
    // [/🧩 Section: helpers]

    // 🧩 Section: API
    @Override
    public boolean cancel() {
        boolean first = !cancellationToken.isCancelled();
        DIAG.debug("hdl#{} cancel() requested (first={})", handleId, first);
        try {
            cancellationToken.cancel();
        } catch (Throwable t) {
            // Never let cancel() throw out to callers
            DIAG.error("hdl#{} cancel() propagation failed: {}", handleId, t.toString());
        }
        return first;
    }

    @Override
    public boolean isActive() {
        return !future.isDone() && !cancellationToken.isCancelled();
    }

    @Override
    public boolean isCompleted() {
        return future.isDone();
    }

    @Override
    public CompletableFuture<T> result() {
        return future;
    }

    @Override
    public CompletableFuture<Void> completion() {
        return future.handle((r, t) -> null);
    }

    @Override
    public T join() throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException("Unexpected throwable", cause);
        }
    }
    // [/🧩 Section: API]

    // 🧩 Section: misc
    @Override
    public String toString() {
        String status;
        if (isCompleted()) {
            status = future.isCancelled() ? "CANCELLED" :
                    future.isCompletedExceptionally() ? "FAILED" : "COMPLETED";
        } else {
            status = cancellationToken.isCancelled() ? "CANCELLING" : "ACTIVE";
        }
        return "JCoroutineHandle[" + status + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JCoroutineHandleImpl<?> that)) return false;
        return future.equals(that.future) && cancellationToken.equals(that.cancellationToken);
    }

    @Override
    public int hashCode() {
        return future.hashCode() * 31 + cancellationToken.hashCode();
    }
    // [/🧩 Section: misc]
}
