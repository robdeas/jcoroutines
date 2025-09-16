/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/internal/JCoroutineScopeImpl.java
 description: Default implementation of JCoroutineScope. Backed by a virtual-thread-per-task
              executor with CancellationToken-based structured cancellation.
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
import tech.robd.jcoroutines.*;
import tech.robd.jcoroutines.diagnostics.Diagnostics;
import tech.robd.jcoroutines.fn.JCoroutineHandle;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default structured-like scope built on a {@link Executors#newVirtualThreadPerTaskExecutor()}.
 *
 * <p>Uses event-driven cancellation via {@link CancellationToken} instead of tracking futures.
 * Provides async/launch APIs for concurrent tasks and blocking run APIs for integration points.</p>
 */
public final class JCoroutineScopeImpl implements JCoroutineScope, AutoCloseable {

    // 🧩 Section: diagnostics
    private static final Diagnostics DIAG = Diagnostics.of(JCoroutineScopeImpl.class);
    // [/🧩 Section: diagnostics]

    // 🧩 Section: state
    private final ExecutorService executor;
    private final CancellationToken scopeToken;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String name;
    private final int scopeId = System.identityHashCode(this);
    private static final AtomicInteger COUNTER = new AtomicInteger();
    // [/🧩 Section: state]

    // 🧩 Section: construction
    public JCoroutineScopeImpl() {
        this("jc-scope-" + COUNTER.incrementAndGet());
    }

    public JCoroutineScopeImpl(String name) {
        this.name = Objects.requireNonNullElse(name, "jc-scope");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.scopeToken = new CancellationTokenImpl();
        DIAG.debug("scope#{} created name={}", scopeId, this.name);
    }
    // [/🧩 Section: construction]

    // 🧩 Section: async
    @Override
    public <T> JCoroutineHandle<T> async(SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("block is null");

        if (closed.get() || executor.isShutdown()) {
            DIAG.warn("scope#{} rejected async launch: CLOSED", scopeId);
            return cancelledHandle("Scope is closed");
        }

        final CancellationToken childToken = scopeToken.child();
        final CompletableFuture<T> cf = new CompletableFuture<>();
        final int handleId = System.identityHashCode(cf);
        final SuspendContext suspend = SuspendContext.create(this, childToken);
        AutoCloseable cancelReg = null;

        try {
            Future<?> f = executor.submit(() -> {
                Thread current = Thread.currentThread();
                DIAG.debug("scope#{} async#{} start", scopeId, handleId);
                try (AutoCloseable reg = childToken.onCancel(() -> {
                    DIAG.debug("scope#{} async#{} child-token->interrupt", scopeId, handleId);
                    current.interrupt();
                })) {
                    try {
                        T v = block.apply(suspend);
                        cf.complete((@Nullable T) v);
                        DIAG.debug("scope#{} async#{} complete OK", scopeId, handleId);
                    } catch (CancellationException ce) {
                        cf.completeExceptionally(ce);
                        DIAG.debug("scope#{} async#{} cancelled", scopeId, handleId);
                    } catch (Throwable t) {
                        cf.completeExceptionally(t);
                        DIAG.debug("scope#{} async#{} failed ex={}", scopeId, handleId, t.getClass().getSimpleName());
                    }
                } catch (Exception ex) {
                    DIAG.debug("scope#{} async#{} reg-close ex={}", scopeId, handleId, ex.toString());
                }
            });

            // 🧩 Point: async/cancel-link
            cancelReg = childToken.onCancel(() -> {
                DIAG.debug("scope#{} async#{} child-token->cancel", scopeId, handleId);
                cf.cancel(true);
                f.cancel(true);
            });

            final AutoCloseable finalCancelReg = cancelReg;
            cf.whenComplete((r, t) -> {
                try {
                    finalCancelReg.close();
                } catch (Exception ignored) {
                }
                try {
                    childToken.cancel();
                } catch (Exception ignored) {
                }
            });

        } catch (RejectedExecutionException rex) {
            DIAG.warn("scope#{} async#{} rejected", scopeId, handleId);
            cf.completeExceptionally(rex);
            if (cancelReg != null) try {
                cancelReg.close();
            } catch (Exception ignored) {
            }
            try {
                childToken.cancel();
            } catch (Exception ignored) {
            }
        }

        return new JCoroutineHandleImpl<>(cf, childToken);
    }
    // [/🧩 Section: async]

    // 🧩 Section: launch
    @Override
    public JCoroutineHandle<Void> launch(SuspendRunnable block) {
        if (block == null) throw new IllegalArgumentException("block is null");

        if (closed.get() || executor.isShutdown()) {
            DIAG.warn("scope#{} rejected launch: CLOSED", scopeId);
            return cancelledHandle("Scope is closed");
        }

        final CancellationToken childToken = scopeToken.child();
        final CompletableFuture<Void> cf = new CompletableFuture<>();
        final int handleId = System.identityHashCode(cf);
        final SuspendContext suspend = SuspendContext.create(this, childToken);
        AutoCloseable cancelReg = null;

        try {
            Future<?> f = executor.submit(() -> {
                Thread current = Thread.currentThread();
                DIAG.debug("scope#{} launch#{} start", scopeId, handleId);
                try (AutoCloseable reg = childToken.onCancel(() -> {
                    DIAG.debug("scope#{} launch#{} child-token->interrupt", scopeId, handleId);
                    current.interrupt();
                })) {
                    try {
                        block.run(suspend);
                        cf.complete(null);
                        DIAG.debug("scope#{} launch#{} complete OK", scopeId, handleId);
                    } catch (CancellationException ce) {
                        cf.completeExceptionally(ce);
                        DIAG.debug("scope#{} launch#{} cancelled", scopeId, handleId);
                    } catch (Throwable t) {
                        cf.completeExceptionally(t);
                        DIAG.debug("scope#{} launch#{} failed ex={}", scopeId, handleId, t.getClass().getSimpleName());
                    }
                } catch (Exception ex) {
                    DIAG.debug("scope#{} launch#{} reg-close ex={}", scopeId, handleId, ex.toString());
                }
            });

            cancelReg = childToken.onCancel(() -> {
                DIAG.debug("scope#{} launch#{} child-token->cancel", scopeId, handleId);
                cf.cancel(true);
                f.cancel(true);
            });

            final AutoCloseable finalCancelReg = cancelReg;
            cf.whenComplete((r, t) -> {
                try {
                    finalCancelReg.close();
                } catch (Exception ignored) {
                }
                try {
                    childToken.cancel();
                } catch (Exception ignored) {
                }
            });

        } catch (RejectedExecutionException rex) {
            DIAG.warn("scope#{} launch#{} rejected", scopeId, handleId);
            cf.completeExceptionally(rex);
            if (cancelReg != null) try {
                cancelReg.close();
            } catch (Exception ignored) {
            }
            try {
                childToken.cancel();
            } catch (Exception ignored) {
            }
        }

        return new JCoroutineHandleImpl<>(cf, childToken);
    }
    // [/🧩 Section: launch]

    // 🧩 Section: runBlocking
    @Override
    public <T extends @Nullable Object> T runBlocking(SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("block == null");
        if (closed.get()) throw new IllegalStateException("Scope is closed");

        final CancellationToken child = scopeToken.child();
        final SuspendContext s = SuspendContext.create(this, child);
        final Thread caller = Thread.currentThread();
        AutoCloseable reg = null;

        try {
            reg = child.onCancel(caller::interrupt);
            return block.apply(s);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted");
        } catch (CancellationException ce) {
            throw ce;
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            if (reg != null) try {
                reg.close();
            } catch (Exception ignored) {
            }
            try {
                child.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public <T extends @Nullable Object> T runBlocking(
            @NonNull Executor exec, @NonNull SuspendFunction<T> block) {
        if (exec == null) throw new IllegalArgumentException("executor == null");
        if (block == null) throw new IllegalArgumentException("block == null");
        if (exec instanceof ExecutorService es && es.isShutdown()) {
            throw new IllegalStateException("Executor is shut down");
        }
        if (closed.get() || executor.isShutdown()) throw new IllegalStateException("Scope is closed");

        final CancellationToken child = scopeToken.child();
        final SuspendContext s = SuspendContext.create(this, child);
        final CompletableFuture<T> cf = new CompletableFuture<>();
        final AtomicReference<Thread> running = new AtomicReference<>();
        AutoCloseable cancelReg = null;

        try {
            cancelReg = child.onCancel(() -> {
                Thread t = running.get();
                if (t != null) t.interrupt();
                cf.cancel(true);
            });
            if (child.isCancelled()) cf.cancel(true);

            exec.execute(() -> {
                Thread t = Thread.currentThread();
                running.set(t);
                try (AutoCloseable taskReg = child.onCancel(t::interrupt)) {
                    try {
                        cf.complete(block.apply(s));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cf.completeExceptionally(new CancellationException("Interrupted"));
                    } catch (Throwable e) {
                        cf.completeExceptionally(e);
                    }
                } catch (Exception e) {
                    cf.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException rex) {
            if (cancelReg != null) try {
                cancelReg.close();
            } catch (Exception ignored) {
            }
            child.cancel();
            throw new CancellationException("Executor rejected runBlocking task");
        }

        final AutoCloseable finalCancelReg = cancelReg;
        cf.whenComplete((r, t) -> {
            try {
                if (finalCancelReg != null) finalCancelReg.close();
            } catch (Exception ignored) {
            }
            try {
                child.cancel();
            } catch (Exception ignored) {
            }
        });

        try {
            return cf.join();
        } catch (CompletionException ce) {
            Throwable c = ce.getCause();
            if (c instanceof CancellationException) throw (CancellationException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new RuntimeException(c);
        }
    }
    // [/🧩 Section: runBlocking]

    // 🧩 Section: lifecycle
    @Override
    public Executor executor() {
        return executor;
    }

    private <T> JCoroutineHandle<T> cancelledHandle(String reason) {
        CancellationToken token = scopeToken.child();
        CompletableFuture<T> cf = new CompletableFuture<>();
        cf.completeExceptionally(new CancellationException(reason));
        cf.cancel(true);
        try {
            token.cancel();
        } catch (Exception ignored) {
        }
        DIAG.debug("scope#{} cancelled handle: {}", scopeId, reason);
        return new JCoroutineHandleImpl<>(cf, token);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            DIAG.debug("scope#{} closing -> cancel token & shutdown", scopeId);
            scopeToken.cancel();
            executor.shutdownNow();
            DIAG.debug("scope#{} closed", scopeId);
        } else {
            DIAG.debug("scope#{} close() ignored (already closed)", scopeId);
        }
    }

    public String getName() {
        return name;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public String toString() {
        return "JCoroutineScope[" + name + " " + (closed.get() ? "CLOSED" : "OPEN") + "]";
    }
    // [/🧩 Section: lifecycle]
}
