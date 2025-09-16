/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/tools/Probes.java
 description: Lightweight probes for thread characteristics plus a tiny worker service for CPU/IO-shaped test workloads.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
 tags: [robokeytags,v1]
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

package tech.robd.jcoroutines.tools;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probe helpers used throughout the test suite.
 * <p>
 * This utility provides:
 * </p>
 * <ul>
 *   <li>{@link #here()} – capture the current thread's name, <em>virtual</em> flag,
 *       and JVM thread id in a simple, serializable {@link ThreadInfo} record,</li>
 *   <li>{@link WorkerService} – a tiny service object whose methods perform either
 *       CPU-shaped work or blocking IO (via {@code Thread.sleep}) so tests can assert
 *       execution context and call counts, and</li>
 *   <li>lightweight assertion helpers ({@link #assertVirtual(boolean, ThreadInfo)} and
 *       {@link #assertEquals(Object, Object, String)}) that do not depend on JUnit and
 *       can be used from ad-hoc runners.</li>
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> The class is stateless. {@link WorkerService} is
 * thread-safe for concurrent calls thanks to its {@link AtomicInteger} counter; its
 * methods are otherwise side-effect free beyond time spent computing/sleeping.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class Probes {

    private Probes() { /* utility class */ }

    /**
     * Immutable snapshot of a thread's identity and characteristics.
     *
     * @param name    the thread's name (as returned by {@link Thread#getName()})
     * @param virtual whether the thread is a <em>virtual thread</em> (Project Loom)
     * @param id      the JVM thread id (as returned by {@link Thread#threadId()})
     */
    public static record ThreadInfo(String name, boolean virtual, long id) {
    }

    /**
     * Returns a {@link ThreadInfo} snapshot of the <em>current</em> thread.
     *
     * @return a new {@link ThreadInfo} for the current thread
     */
    public static ThreadInfo here() {
        Thread t = Thread.currentThread();
        return new ThreadInfo(t.getName(), t.isVirtual(), t.threadId());
    }

    /**
     * Simple service we can call from tests to verify execution context and count calls.
     * <p>
     * Each method increments the {@linkplain #calls() call count} before performing its work.
     * </p>
     */
    public static final class WorkerService {
        private final AtomicInteger calls = new AtomicInteger();

        /**
         * Increments the call counter and returns a {@link ThreadInfo} snapshot to assert
         * which thread type executed the call.
         *
         * @return the {@link ThreadInfo} of the thread executing this method
         */
        public ThreadInfo ping() {
            calls.incrementAndGet();
            return Probes.here();
        }

        /**
         * Performs a small, deterministic CPU-bound loop so tests can assert that
         * CPU work runs on the expected dispatcher/executor. The return value is
         * the input for easy assertions; the loop exists only to burn cycles.
         *
         * @param x an integer used to seed the loop and returned to the caller
         * @return the same integer {@code x}
         */
        public int cpuWork(int x) {
            calls.incrementAndGet();
            // simulate CPU cycles
            long acc = 0;
            for (int i = 0; i < 50_000; i++) acc += (x + i) % 7;
            return x; // deterministic & easy to assert
        }

        /**
         * Simulates a blocking IO operation via {@link Thread#sleep(long)}.
         * <p>
         * If interrupted, the thread's interrupted status is restored and a {@link RuntimeException}
         * is thrown with the {@link InterruptedException} as its cause. This shape is convenient for tests.
         * </p>
         *
         * @param millis  how long to sleep
         * @param payload a string to return after the sleep completes
         * @return {@code payload}
         * @throws RuntimeException if interrupted while sleeping
         */
        public String ioWork(long millis, String payload) {
            calls.incrementAndGet();
            try {
                Thread.sleep(millis); // acceptable on VT in tests
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return payload;
        }

        /**
         * @return the number of times any method has been invoked
         */
        public int calls() {
            return calls.get();
        }

        /**
         * Resets the call counter to zero.
         */
        public void reset() {
            calls.set(0);
        }
    }

    /**
     * Asserts that {@code info.virtual() == expected}. Throws {@link AssertionError} otherwise.
     * <p>
     * Useful in ad-hoc runners where JUnit is not available; test classes are free to use
     * their framework's own assertion helpers instead.
     * </p>
     *
     * @param expected whether a virtual thread is expected
     * @param info     the captured {@link ThreadInfo}
     * @throws AssertionError if the expectation is not met
     */
    public static void assertVirtual(boolean expected, ThreadInfo info) {
        if (info.virtual() != expected) {
            throw new AssertionError("Expected virtual=" + expected + " but was " + info);
        }
    }

    /**
     * Null-safe equality assertion that throws {@link AssertionError} on mismatch.
     * <p>Provided to avoid a hard dependency on a test framework from sample runners.</p>
     *
     * @param expected expected value
     * @param actual   actual value
     * @param label    a short label included in the failure message to aid diagnostics
     * @throws AssertionError if {@code !Objects.equals(expected, actual)}
     */
    public static void assertEquals(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
}
