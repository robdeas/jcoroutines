/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/CircuitBreaker.java
 description: Coroutine-friendly circuit breaker with CLOSED/OPEN/HALF_OPEN states, probe gating,
              fallback/timeout helpers, and an in-memory registry for named breakers.
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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A coroutine-friendly circuit breaker with three states:
 * <ul>
 *   <li><b>CLOSED</b>: normal operation; consecutive failures trip the breaker to OPEN.</li>
 *   <li><b>OPEN</b>: calls are short-circuited until the open timeout elapses.</li>
 *   <li><b>HALF_OPEN</b>: allows up to N concurrent probe calls; any failure re-opens immediately;
 *       S successes close the breaker.</li>
 * </ul>
 *
 * <p>Use {@link #execute(SuspendContext, SuspendFunction)} for guarded execution,
 * {@link #executeWithFallback(SuspendContext, SuspendFunction, SuspendFunction)} to provide a fallback
 * when the breaker is OPEN, and {@link #executeWithTimeout(SuspendContext, Duration, SuspendFunction)}
 * to apply a scoped timeout via {@link SuspendContext#withTimeout(Duration, SuspendFunction)}.</p>
 *
 * <p>Instances are thread-safe; atomics are used for counters/state timestamps.
 * The state field itself is volatile to allow fast reads.</p>
 */
public final class CircuitBreaker {

    /**
     * Current state of the circuit breaker.
     */
    public enum State {CLOSED, OPEN, HALF_OPEN}

    // 🧩 Section: state
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(0);
    // [/🧩 Section: state]

    // 🧩 Section: config
    private final int failureThreshold;   // failures in CLOSED to open
    private final Duration openTimeout;   // dwell time in OPEN before half-open
    private final int successThreshold;   // successes in HALF_OPEN to close
    private final int halfOpenMaxProbes;  // concurrent calls allowed in HALF_OPEN
    private final String name;

    /**
     * Gate for HALF_OPEN concurrency control.
     */
    private final AtomicInteger halfOpenInFlight = new AtomicInteger(0);
    // [/🧩 Section: config]

    // 🧩 Section: registry
    private static final ConcurrentHashMap<String, CircuitBreaker> REGISTRY = new ConcurrentHashMap<>();
    // [/🧩 Section: registry]

    // 🧩 Section: construction

    /**
     * Default: 5 failures open, 1 minute open timeout, need 3 successes to close, 1 concurrent probe.
     */
    public CircuitBreaker() {
        this("default", 5, Duration.ofMinutes(1), 3, 1);
    }

    /**
     * Construct a breaker with standard probe gating (1 concurrent probe).
     */
    public CircuitBreaker(@NonNull final String name,
                          final int failureThreshold,
                          @NonNull final Duration openTimeout,
                          final int successThreshold) {
        this(name, failureThreshold, openTimeout, successThreshold, 1);
    }

    /**
     * Construct a breaker with full control of thresholds and probe concurrency.
     *
     * @param name              logical name (non-null)
     * @param failureThreshold  number of failures in CLOSED to move to OPEN (&gt;0)
     * @param openTimeout       time to wait in OPEN before moving to HALF_OPEN (non-null)
     * @param successThreshold  successes required in HALF_OPEN to move to CLOSED (&gt;0)
     * @param halfOpenMaxProbes maximum concurrent probe calls allowed in HALF_OPEN (&gt;0)
     */
    public CircuitBreaker(@NonNull String name,
                          int failureThreshold,
                          @NonNull Duration openTimeout,
                          int successThreshold,
                          int halfOpenMaxProbes) {
        if (name == null || openTimeout == null) throw new IllegalArgumentException("Name/timeout cannot be null");
        if (failureThreshold <= 0 || successThreshold <= 0) throw new IllegalArgumentException("thresholds > 0");
        if (halfOpenMaxProbes <= 0) throw new IllegalArgumentException("halfOpenMaxProbes > 0");

        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeout = openTimeout;
        this.successThreshold = successThreshold;
        this.halfOpenMaxProbes = halfOpenMaxProbes;
    }

    /**
     * Get or create a named breaker using sensible defaults.
     *
     * @param name the breaker name (non-null)
     * @return a cached or newly created breaker
     */
    public static @NonNull CircuitBreaker named(@NonNull final String name) {
        if (name == null) throw new IllegalArgumentException("name == null");
        return REGISTRY.computeIfAbsent(name, k -> new CircuitBreaker(k, 5, Duration.ofMinutes(1), 3, 1));
    }
    // [/🧩 Section: construction]

    // 🧩 Section: execution

    /**
     * Execute an operation under breaker control.
     *
     * <p>If state is OPEN, throws {@link CircuitBreakerOpenException} without invoking {@code op}.
     * In HALF_OPEN, enforces the concurrent probe limit.</p>
     *
     * @throws CircuitBreakerOpenException if short-circuited (OPEN or probe limit exceeded)
     */
    public <T> T execute(@NonNull SuspendContext s, @NonNull SuspendFunction<T> op) {
        if (s == null || op == null) throw new IllegalArgumentException("args");
        s.checkCancellation();

        State snapshot = advanceStateIfNeeded();

        if (snapshot == State.OPEN) {
            throw new CircuitBreakerOpenException("Circuit '" + name + "' is OPEN");
        }

        boolean countedProbe = false;
        try {
            if (snapshot == State.HALF_OPEN) {
                // 🧩 Point: execution/half-open-probe-gate
                int now = halfOpenInFlight.incrementAndGet();
                if (now > halfOpenMaxProbes) {
                    halfOpenInFlight.decrementAndGet();
                    throw new CircuitBreakerOpenException(
                            "Circuit '" + name + "' HALF_OPEN – probe limit reached"
                    );
                }
                countedProbe = true;
            }

            T result = op.apply(s); // may throw
            onSuccess();
            return result;

        } catch (Throwable t) {
            onFailure();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        } finally {
            if (countedProbe) halfOpenInFlight.decrementAndGet();
        }
    }

    /**
     * Execute with a fallback if the breaker is OPEN.
     *
     * <p>Only OPEN short-circuits use the fallback. Exceptions thrown by {@code op} or
     * {@code fallback} still propagate.</p>
     */
    public <T> T executeWithFallback(@NonNull SuspendContext s,
                                     @NonNull SuspendFunction<T> op,
                                     @NonNull SuspendFunction<T> fallback) {
        try {
            return execute(s, op);
        } catch (CircuitBreakerOpenException open) {
            try {
                return fallback.apply(s);
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error e) throw e;
                throw new RuntimeException(t);
            }
        }
    }

    /**
     * Execute with a per-call timeout using {@link SuspendContext#withTimeout(Duration, SuspendFunction)}.
     */
    public <T> T executeWithTimeout(@NonNull SuspendContext s,
                                    @NonNull Duration timeout,
                                    @NonNull SuspendFunction<T> op) {
        return execute(s, ctx -> ctx.withTimeout(timeout, op));
    }
    // [/🧩 Section: execution]

    // 🧩 Section: state-machine

    /**
     * Advance state based on time/counters; returns the (possibly updated) current state.
     */
    private State advanceStateIfNeeded() {
        long now = System.currentTimeMillis();
        State st = state;

        switch (st) {
            case CLOSED -> {
                if (failureCount.get() >= failureThreshold) {
                    state = State.OPEN;
                    return State.OPEN;
                }
                return State.CLOSED;
            }
            case OPEN -> {
                if (now - lastFailureTime.get() >= openTimeout.toMillis()) {
                    state = State.HALF_OPEN;
                    successCount.set(0);
                    halfOpenInFlight.set(0);
                    return State.HALF_OPEN;
                }
                return State.OPEN;
            }
            case HALF_OPEN -> {
                return State.HALF_OPEN; // decisions occur in onSuccess/onFailure
            }
            default -> {
                return st;
            }
        }
    }

    /**
     * Record a successful call and transition if needed.
     */
    private void onSuccess() {
        lastSuccessTime.set(System.currentTimeMillis());

        if (state == State.HALF_OPEN) {
            if (successCount.incrementAndGet() >= successThreshold) {
                state = State.CLOSED;
                failureCount.set(0);
                successCount.set(0);
                halfOpenInFlight.set(0);
            }
        } else if (state == State.CLOSED) {
            failureCount.set(0); // reset failure streak
        }
    }

    /**
     * Record a failed call and transition if needed.
     */
    private void onFailure() {
        lastFailureTime.set(System.currentTimeMillis());

        if (state == State.HALF_OPEN) {
            state = State.OPEN;          // any failure re-opens immediately
            successCount.set(0);
            halfOpenInFlight.set(0);
            return;
        }

        if (state == State.CLOSED && failureCount.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
        }
    }
    // [/🧩 Section: state-machine]

    // 🧩 Section: introspection
    public State getState() {
        return state;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public long getLastFailureTime() {
        return lastFailureTime.get();
    }

    public long getLastSuccessTime() {
        return lastSuccessTime.get();
    }

    public String getName() {
        return name;
    }

    public int getHalfOpenInFlight() {
        return halfOpenInFlight.get();
    }

    public int getHalfOpenMaxProbes() {
        return halfOpenMaxProbes;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public Duration getOpenTimeout() {
        return openTimeout;
    }
    // [/🧩 Section: introspection]

    // 🧩 Section: control

    /**
     * Reset to CLOSED and clear counters/timestamps.
     */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        halfOpenInFlight.set(0);
        lastFailureTime.set(0);
        lastSuccessTime.set(0);
    }

    /**
     * Force OPEN immediately (e.g., ops action).
     */
    public void forceOpen() {
        state = State.OPEN;
        halfOpenInFlight.set(0);
        lastFailureTime.set(System.currentTimeMillis());
    }

    /**
     * Immutable snapshot of stats.
     */
    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
                name, state, failureCount.get(), successCount.get(),
                lastFailureTime.get(), lastSuccessTime.get(),
                failureThreshold, openTimeout, successThreshold,
                halfOpenInFlight.get(), halfOpenMaxProbes
        );
    }
    // [/🧩 Section: control]

    // 🧩 Section: misc
    @Override
    public String toString() {
        return "CircuitBreaker[" + name + " state=" + state +
                " failures=" + failureCount.get() + " successes=" + successCount.get() + "]";
    }
    // [/🧩 Section: misc]

    // 🧩 Section: nested-types

    /**
     * Thrown when calls are short-circuited due to OPEN/HALF_OPEN probe limits.
     */
    public static final class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    /**
     * Statistics snapshot for monitoring/diagnostics.
     */
    public static final class CircuitBreakerStats {
        public final String name;
        public final State state;
        public final int failureCount;
        public final int successCount;
        public final long lastFailureTime;
        public final long lastSuccessTime;
        public final int failureThreshold;
        public final Duration openTimeout;
        public final int successThreshold;
        public final int halfOpenInFlight;
        public final int halfOpenMaxProbes;

        private CircuitBreakerStats(String name, State state, int failureCount, int successCount,
                                    long lastFailureTime, long lastSuccessTime,
                                    int failureThreshold, Duration openTimeout, int successThreshold,
                                    int halfOpenInFlight, int halfOpenMaxProbes) {
            this.name = name;
            this.state = state;
            this.failureCount = failureCount;
            this.successCount = successCount;
            this.lastFailureTime = lastFailureTime;
            this.lastSuccessTime = lastSuccessTime;
            this.failureThreshold = failureThreshold;
            this.openTimeout = openTimeout;
            this.successThreshold = successThreshold;
            this.halfOpenInFlight = halfOpenInFlight;
            this.halfOpenMaxProbes = halfOpenMaxProbes;
        }

        @Override
        public String toString() {
            return "CircuitBreakerStats[" + name + " " + state +
                    " fails=" + failureCount + "/" + failureThreshold +
                    " succ=" + successCount + "/" + successThreshold +
                    " probes=" + halfOpenInFlight + "/" + halfOpenMaxProbes + "]";
        }
    }
    // [/🧩 Section: nested-types]
}
