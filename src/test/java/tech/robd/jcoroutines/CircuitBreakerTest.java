/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/CircuitBreakerTest.java
 description: An experimantal clsss in this release
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: no
 [/File Info]
*/
/*
 * Copyright (c) 2025 Rob Deas Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

/**
 * Basic tests for CircuitBreaker functionality.
 *
 * NOTE: These tests focus on single-threaded scenarios and basic functionality.
 * Complex concurrent scenarios are not tested due to known race conditions
 * that will be addressed in future releases.
 */
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;
    private MockSuspendContext context;

    @BeforeEach
    void setUp() {
        // Reset registry to avoid test interference
        CircuitBreaker.REGISTRY.clear();

        circuitBreaker = new CircuitBreaker("test",
                3,                           // failureThreshold
                Duration.ofMillis(100),      // openTimeout (short for tests)
                2,                           // successThreshold
                1                            // halfOpenMaxProbes
        );
        context = new MockSuspendContext();
    }

    @Test
    @DisplayName("Circuit breaker starts in CLOSED state")
    void startsInClosedState() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(0, circuitBreaker.getSuccessCount());
    }

    @Test
    @DisplayName("Named circuit breakers are cached")
    void namedCircuitBreakersAreCached() {
        CircuitBreaker cb1 = CircuitBreaker.named("shared");
        CircuitBreaker cb2 = CircuitBreaker.named("shared");

        assertSame(cb1, cb2);
        assertEquals("shared", cb1.getName());
    }

    @Test
    @DisplayName("Reset clears all counters and sets state to CLOSED")
    void resetClearsCountersAndState() {
        // Make some failures and open the circuit
        circuitBreaker.forceOpen();

        // Reset should clear everything
        circuitBreaker.reset();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(0, circuitBreaker.getSuccessCount());
    }

    @Test
    @DisplayName("Circuit breaker stats provide accurate information")
    void statsProvideAccurateInfo() {
        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();

        assertEquals("test", stats.name);
        assertEquals(CircuitBreaker.State.CLOSED, stats.state);
        assertEquals(0, stats.failureCount);
        assertEquals(3, stats.failureThreshold);
        assertEquals(2, stats.successThreshold);
        assertEquals(Duration.ofMillis(100), stats.openTimeout);
    }


    // Simple mock implementations for testing
    private static class MockSuspendContext implements SuspendContext {
        @Override
        public void checkCancellation() {
            // No-op for basic tests
        }

        @Override
        public <T> T withTimeout(Duration timeout, SuspendFunction<T> operation) {
            try {
                return operation.apply(this);
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error e) throw e;
                throw new RuntimeException(t);
            }
        }
    }

    // NOTE: These interfaces would need to be defined if not already present
    @FunctionalInterface
    interface SuspendFunction<T> {
        T apply(SuspendContext context) throws Throwable;
    }

    interface SuspendContext {
        void checkCancellation();
        <T> T withTimeout(Duration timeout, SuspendFunction<T> operation);
    }
}
