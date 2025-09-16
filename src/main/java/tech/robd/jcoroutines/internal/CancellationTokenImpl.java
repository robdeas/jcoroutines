/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/internal/CancellationTokenImpl.java
 description: Default implementation of CancellationToken with callback registration,
              weak-referenced children, and cascading cancellation support.
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

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link CancellationToken} using event-driven callbacks
 * with weak-referenced children. Diagnostics are silent unless enabled.
 */
public final class CancellationTokenImpl implements CancellationToken {

    // 🧩 Section: diagnostics
    private static final Diagnostics DIAG = Diagnostics.of(CancellationTokenImpl.class);
    // [/🧩 Section: diagnostics]

    // 🧩 Section: state
    private final int tokId = System.identityHashCode(this);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    // Callbacks are wrapped to guarantee at-most-once execution
    private final ConcurrentLinkedQueue<CallbackWrapper> callbacks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<WeakReference<CancellationTokenImpl>> children = new ConcurrentLinkedQueue<>();

    // Parent linkage using WEAK references to prevent retention cycles
    private volatile WeakReference<CancellationTokenImpl> parentRef;
    private volatile WeakReference<AutoCloseable> parentCallbackRef;
    // [/🧩 Section: state]

    // 🧩 Section: callback-wrapper

    /**
     * Wrapper to allow callback removal and weak referencing.
     */
    private static final class CallbackWrapper {
        private final AtomicReference<Runnable> callbackRef;
        private final AtomicBoolean executed = new AtomicBoolean(false);

        CallbackWrapper(Runnable callback) {
            this.callbackRef = new AtomicReference<>(callback);
        }

        void execute() {
            if (executed.compareAndSet(false, true)) {
                Runnable callback = callbackRef.getAndSet(null);
                if (callback != null) callback.run();
            }
        }

        void clear() {
            callbackRef.set(null);
        }

        boolean isCleared() {
            return callbackRef.get() == null;
        }
    }
    // [/🧩 Section: callback-wrapper]

    // 🧩 Section: query
    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
    // [/🧩 Section: query]

    // 🧩 Section: registration
    @Override
    public AutoCloseable onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");
        CallbackWrapper wrapper = new CallbackWrapper(callback);

        if (cancelled.get()) {
            DIAG.debug("tok#{} onCancel: already cancelled -> run immediately", tokId);
            safeExecute(wrapper, "onCancel-immediate");
            return () -> {
            }; // no-op
        }

        callbacks.offer(wrapper);

        if (cancelled.get()) {
            if (callbacks.remove(wrapper)) {
                DIAG.debug("tok#{} onCancel: race -> run after registration", tokId);
                safeExecute(wrapper, "onCancel-race");
            }
        } else {
            DIAG.debug("tok#{} onCancel: registered (pending={})", tokId, callbacks.size());
        }

        return () -> {
            if (callbacks.remove(wrapper)) {
                wrapper.clear();
            }
        };
    }

    @Override
    public void onCancelQuietly(Runnable callback) {
        try (AutoCloseable ignored = onCancel(callback)) {
            // auto-close
        } catch (Exception e) {
            DIAG.debug("tok#{} onCancelQuietly: error in callback setup", tokId);
        }
    }
    // [/🧩 Section: registration]

    // 🧩 Section: child
    @Override
    public CancellationToken child() {
        CancellationTokenImpl child = new CancellationTokenImpl();

        if (cancelled.get()) {
            DIAG.debug("tok#{} child created tok#{} (parent already cancelled)", tokId, child.tokId);
            child.cancel();
            return child;
        }

        child.parentRef = new WeakReference<>(this);
        WeakReference<CancellationTokenImpl> childRef = new WeakReference<>(child);
        children.offer(childRef);

        AutoCloseable parentCallback = this.onCancel(() -> {
            CancellationTokenImpl c = childRef.get();
            if (c != null) {
                DIAG.debug("tok#{} cascading cancel to child tok#{}", tokId, c.tokId);
                c.cancel();
            }
        });
        child.parentCallbackRef = new WeakReference<>(parentCallback);

        if (cancelled.get()) {
            child.cancel();
        }

        DIAG.debug("tok#{} child created tok#{} with weak cascade callback", tokId, child.tokId);
        return child;
    }
    // [/🧩 Section: child]

    // 🧩 Section: cancel
    @Override
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            DIAG.debug("tok#{} cancel: already cancelled", tokId);
            return false;
        }

        DIAG.debug("tok#{} cancel: firing callbacks={} children={}",
                tokId, callbacks.size(), children.size());

        CallbackWrapper wrapper;
        while ((wrapper = callbacks.poll()) != null) {
            safeExecute(wrapper, "cancel-callback");
        }

        WeakReference<CancellationTokenImpl> ref;
        while ((ref = children.poll()) != null) {
            CancellationTokenImpl child = ref.get();
            if (child != null) child.cancel();
        }

        markCompleted();
        return true;
    }
    // [/🧩 Section: cancel]

    // 🧩 Section: completion

    /**
     * Mark this token as successfully completed (not cancelled).
     */
    public void markCompleted() {
        if (completed.compareAndSet(false, true)) {
            DIAG.debug("tok#{} marking completed (cancelled={})", tokId, cancelled.get());

            WeakReference<AutoCloseable> parentCbRef = parentCallbackRef;
            if (parentCbRef != null) {
                AutoCloseable parentCb = parentCbRef.get();
                if (parentCb != null) {
                    try {
                        parentCb.close();
                    } catch (Exception e) {
                        DIAG.debug("tok#{} error closing parent callback: {}", tokId, e.toString());
                    }
                }
                parentCallbackRef = null;
            }

            parentRef = null;

            CallbackWrapper wrapper;
            while ((wrapper = callbacks.poll()) != null) {
                wrapper.clear();
            }
            children.clear();

            DIAG.debug("tok#{} marked completed, cleaned up resources", tokId);
        }
    }

    /**
     * Force cleanup of all references (for testing/debugging).
     */
    public void forceCleanup() {
        markCompleted();
        callbacks.clear();
        children.clear();
        parentRef = null;
        parentCallbackRef = null;
    }
    // [/🧩 Section: completion]

    // 🧩 Section: maintenance
    private void safeExecute(CallbackWrapper wrapper, String where) {
        try {
            wrapper.execute();
        } catch (Throwable t) {
            DIAG.error("tok#{} callback error @{}: {}", tokId, where, t.toString());
        }
    }

    /**
     * Remove dead weak refs (debug aid).
     */
    public void cleanup() {
        children.removeIf(ref -> ref.get() == null);
        callbacks.removeIf(CallbackWrapper::isCleared);
    }

    public int getActiveChildCount() {
        int count = 0;
        for (WeakReference<CancellationTokenImpl> ref : children) {
            if (ref.get() != null) count++;
        }
        return count;
    }

    public int getPendingCallbackCount() {
        return (int) callbacks.stream().filter(w -> !w.isCleared()).count();
    }

    /**
     * @return true if this token has been fully cleaned up.
     */
    public boolean isFullyCleanedUp() {
        return completed.get() &&
                parentRef == null &&
                parentCallbackRef == null &&
                callbacks.isEmpty() &&
                children.isEmpty();
    }
    // [/🧩 Section: maintenance]

    // 🧩 Section: misc
    @Override
    public String toString() {
        if (cancelled.get()) {
            return "CancellationToken[CANCELLED, completed=" + completed.get() + "]";
        } else if (completed.get()) {
            return "CancellationToken[COMPLETED]";
        } else {
            return "CancellationToken[ACTIVE, children=" + getActiveChildCount()
                    + ", callbacks=" + getPendingCallbackCount() + "]";
        }
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
    // [/🧩 Section: misc]
}
