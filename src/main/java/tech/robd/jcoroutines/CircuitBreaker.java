/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/FixedCircuitBreaker.java
 description: Thread-safe circuit breaker with atomic state transitions and proper concurrency control
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;



/**
 * Thread-safe circuit breaker with atomic state management and proper concurrency control.
 *
 * Circuit breaker included experimentally to explore async resilience patterns. May move to a separate library based on usage patterns and feedback."
 *
 * Features some thread safety , but not yet fully complete:
 * - Uses AtomicReference for state instead of volatile field
 * - Atomic state transitions using compareAndSet operations
 * - Proper synchronization of probe slot management
 * - Read-write lock coordination for complex state changes
 * - Fixed critical race conditions in failure handling
 *
 * WARNING: Still has some race conditions in read-lock to write-lock transitions.
 * A more comprehensive fix is planned for a future release.
 *
 * KNOWN ISSUES:
 * - Gap between read lock release and write lock acquisition in advanceStateIfNeeded()
 * - Multiple threads can simultaneously attempt time-based state transitions
 * - Success count incrementing outside of proper synchronization in some paths
 */
@Experimental("Circuit breaker concurrency model under development - some race conditions remain")
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    // Atomic state management
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(0);
    private final AtomicInteger halfOpenInFlight = new AtomicInteger(0);

    // Configuration
    private final int failureThreshold;
    private final Duration openTimeout;
    private final int successThreshold;
    private final int halfOpenMaxProbes;
    private final String name;

    // Lock for coordinating complex state transitions
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();


    static final ConcurrentHashMap<String, CircuitBreaker> REGISTRY = new ConcurrentHashMap<>();

    public CircuitBreaker() {
        this("default", 5, Duration.ofMinutes(1), 3, 1);
    }

    public CircuitBreaker(@NonNull final String name,
                          final int failureThreshold,
                          @NonNull final Duration openTimeout,
                          final int successThreshold) {
        this(name, failureThreshold, openTimeout, successThreshold, 1);
    }

    public CircuitBreaker(@NonNull String name,
                          int failureThreshold,
                          @NonNull Duration openTimeout,
                          int successThreshold,
                          int halfOpenMaxProbes) {
        if (name == null || openTimeout == null) {
            throw new IllegalArgumentException("Name/timeout cannot be null");
        }
        if (failureThreshold <= 0 || successThreshold <= 0) {
            throw new IllegalArgumentException("thresholds > 0");
        }
        if (halfOpenMaxProbes <= 0) {
            throw new IllegalArgumentException("halfOpenMaxProbes > 0");
        }

        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeout = openTimeout;
        this.successThreshold = successThreshold;
        this.halfOpenMaxProbes = halfOpenMaxProbes;
    }

    public static @NonNull CircuitBreaker named(@NonNull final String name) {
        if (name == null) throw new IllegalArgumentException("name == null");
        return REGISTRY.computeIfAbsent(name,
                k -> new CircuitBreaker(k, 5, Duration.ofMinutes(1), 3, 1));
    }

    public <T> T execute(@NonNull SuspendContext s, @NonNull SuspendFunction<T> op) {
        if (s == null || op == null) throw new IllegalArgumentException("args");
        s.checkCancellation();

        State currentState = advanceStateIfNeeded();

        switch (currentState) {
            case OPEN:
                throw new CircuitBreakerOpenException("Circuit '" + name + "' is OPEN");
            case CLOSED:
                return executeInClosed(s, op);
            case HALF_OPEN:
                return executeInHalfOpen(s, op);
            default:
                throw new IllegalStateException("Unknown state: " + currentState);
        }
    }

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

    public <T> T executeWithTimeout(@NonNull SuspendContext s,
                                    @NonNull Duration timeout,
                                    @NonNull SuspendFunction<T> op) {
        return execute(s, ctx -> ctx.withTimeout(timeout, op));
    }

    private State advanceStateIfNeeded() {
        stateLock.readLock().lock();
        try {
            State current = state.get();
            switch (current) {
                case CLOSED:
                    if (failureCount.get() >= failureThreshold) {
                        return transitionToOpen();
                    }
                    return State.CLOSED;

                case OPEN:
                    long elapsed = System.currentTimeMillis() - lastFailureTime.get();
                    if (elapsed >= openTimeout.toMillis()) {
                        return attemptTransitionToHalfOpen();
                    }
                    return State.OPEN;

                case HALF_OPEN:
                    return State.HALF_OPEN;

                default:
                    return current;
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private State transitionToOpen() {
        stateLock.writeLock().lock();
        try {
            // Double-check under write lock
            if (failureCount.get() >= failureThreshold &&
                    state.compareAndSet(State.CLOSED, State.OPEN)) {
                halfOpenInFlight.set(0);
                return State.OPEN;
            }
            return state.get();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private State attemptTransitionToHalfOpen() {
        stateLock.writeLock().lock();
        try {
            long elapsed = System.currentTimeMillis() - lastFailureTime.get();
            if (elapsed >= openTimeout.toMillis() &&
                    state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                successCount.set(0);
                halfOpenInFlight.set(0);
                return State.HALF_OPEN;
            }
            return state.get();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    // Execution in different states
    private <T> T executeInClosed(@NonNull SuspendContext s, @NonNull SuspendFunction<T> op) {
        try {
            T result = op.apply(s);
            onSuccess();
            return result;
        } catch (Throwable t) {
            onFailure();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    private <T> T executeInHalfOpen(@NonNull SuspendContext s, @NonNull SuspendFunction<T> op) {
        // Acquire probe slot atomically
        if (!acquireProbeSlot()) {
            throw new CircuitBreakerOpenException(
                    "Circuit '" + name + "' HALF_OPEN – probe limit reached");
        }

        try {
            T result = op.apply(s);
            onSuccess();
            return result;
        } catch (Throwable t) {
            onFailure();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        } finally {
            releaseProbeSlot();
        }
    }

    private boolean acquireProbeSlot() {
        stateLock.readLock().lock();
        try {
            if (state.get() == State.HALF_OPEN) {
                while (true) {
                    int current = halfOpenInFlight.get();
                    if (current >= halfOpenMaxProbes) {
                        return false;
                    }
                    if (halfOpenInFlight.compareAndSet(current, current + 1)) {
                        return true;
                    }
                    // CAS failed, retry
                }
            }
            return false;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private void releaseProbeSlot() {
        halfOpenInFlight.decrementAndGet();
    }

    // State change handlers with proper synchronization
    private void onSuccess() {
        lastSuccessTime.set(System.currentTimeMillis());

        // Acquire lock once for the entire state-changing operation
        stateLock.writeLock().lock();
        try {
            State current = state.get();
            switch (current) {
                case HALF_OPEN:
                    int newSuccessCount = successCount.incrementAndGet();
                    if (newSuccessCount >= successThreshold) {
                        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                            resetCountersAndProbes();
                        }
                    }
                    break;

                case CLOSED:
                    failureCount.set(0);
                    break;

                case OPEN:
                    // No action needed
                    break;
            }
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void resetCountersAndProbes() {
        failureCount.set(0);
        successCount.set(0);
        halfOpenInFlight.set(0);
    }

    private void onFailure() {
        lastFailureTime.set(System.currentTimeMillis());

        // Get current state once to avoid race conditions
        State current = state.get();

        switch (current) {
            case HALF_OPEN:
                stateLock.writeLock().lock();
                try {
                    // Double-check state hasn't changed
                    if (state.get() == State.HALF_OPEN &&
                            state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                        successCount.set(0);
                        halfOpenInFlight.set(0);
                    }
                } finally {
                    stateLock.writeLock().unlock();
                }
                break;

            case CLOSED:
                // CRITICAL FIX: Atomically increment failure count and check threshold under lock
                stateLock.writeLock().lock();
                try {
                    // Double-check we're still in CLOSED state
                    if (state.get() == State.CLOSED) {
                        int newFailureCount = failureCount.incrementAndGet();
                        if (newFailureCount >= failureThreshold) {
                            // Transition to OPEN immediately under the same lock
                            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                                halfOpenInFlight.set(0);
                            }
                        }
                    }
                } finally {
                    stateLock.writeLock().unlock();
                }
                break;

            case OPEN:
                // Already open, no action needed
                break;
        }
    }

    // Public API methods
    public State getState() {
        return state.get();
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

    public void reset() {
        stateLock.writeLock().lock();
        try {
            state.set(State.CLOSED);
            failureCount.set(0);
            successCount.set(0);
            halfOpenInFlight.set(0);
            lastFailureTime.set(0);
            lastSuccessTime.set(0);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public void forceOpen() {
        stateLock.writeLock().lock();
        try {
            state.set(State.OPEN);
            halfOpenInFlight.set(0);
            lastFailureTime.set(System.currentTimeMillis());
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
                name, state.get(), failureCount.get(), successCount.get(),
                lastFailureTime.get(), lastSuccessTime.get(),
                failureThreshold, openTimeout, successThreshold,
                halfOpenInFlight.get(), halfOpenMaxProbes
        );
    }

    @Override
    public String toString() {
        return "FixedCircuitBreaker[" + name + " state=" + state.get() +
                " failures=" + failureCount.get() + " successes=" + successCount.get() + "]";
    }

    // Nested classes
    public static final class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

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

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Experimental {
        String value();
    }
}