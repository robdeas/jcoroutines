/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/SuspendContextCombinatorsTest.java
 description: Tests for SuspendContext combinators: awaitAll/awaitAny, parallel, zip, withTimeout/withTimeoutOrNull, and retry with backoff.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.runBlockingCpu;

final class SuspendContextCombinatorsTest {

    @Test
    @Timeout(2)
        // Combinators: awaitAll returns all results in order; awaitAny returns whichever completes first;
        // parallel executes both functions concurrently; zip combines two futures via a zipper.
        // Race-avoidance: staggered delays make ordering deterministic for awaitAll/parallel/zip;
        // new handles are created for awaitAny because previous ones were consumed.
    void awaitAllAwaitAnyParallelZip() throws Exception {
        String joined = runBlockingCpu(s -> {
            var h1 = s.async(x -> {
                x.delay(10);
                return "A";
            });
            var h2 = s.async(x -> {
                x.delay(15);
                return "B";
            });
            var h3 = s.async(x -> {
                x.delay(12);
                return "C";
            });

            List<String> all = s.awaitAll(h1.result(), h2.result(), h3.result());
            assertEquals(List.of("A", "B", "C"), all);

            // Create new handles for awaitAny test since the previous ones are consumed
            var f1 = s.async(x -> {
                x.delay(10);
                return "A";
            });
            var f2 = s.async(x -> {
                x.delay(15);
                return "B";
            });
            var f3 = s.async(x -> {
                x.delay(12);
                return "C";
            });

            String any = s.awaitAny(f1.result(), f2.result(), f3.result());
            assertTrue(any.equals("A") || any.equals("B") || any.equals("C"));

            List<String> par = s.parallel(
                    x -> {
                        x.delay(5);
                        return "X";
                    },
                    x -> {
                        x.delay(6);
                        return "Y";
                    }
            );
            assertEquals(List.of("X", "Y"), par);

            var u = s.async(x -> {
                x.delay(5);
                return "user";
            });
            var p = s.async(x -> {
                x.delay(7);
                return "profile";
            });
            String zipped = s.zip(u.result(), p.result(), (a, b) -> a + ":" + b);

            return String.join(",", all) + "|" + zipped;
        });

        assertEquals("A,B,C|user:profile", joined);
    }

    @Test
    @Timeout(2)
        // Timeouts: withTimeout succeeds under the limit, throws CancellationException when over limit;
        // withTimeoutOrNull returns null on timeout.
        // Race-avoidance: large delay (100ms) vs small timeouts (20ms/50ms) ensures deterministic outcomes.
    void withTimeoutAndOrNull() throws Exception {
        // success
        String ok = runBlockingCpu(s ->
                s.withTimeout(Duration.ofMillis(50), x -> {
                    x.delay(10);
                    return "ok";
                })
        );
        assertEquals("ok", ok);

        // cancellation thrown
        assertThrows(CancellationException.class, () ->
                runBlockingCpu(s ->
                        s.withTimeout(Duration.ofMillis(20), x -> {
                            x.delay(100);
                            return "late";
                        })
                )
        );

        // orNull path
        String maybe = runBlockingCpu(s ->
                s.withTimeoutOrNull(Duration.ofMillis(20), x -> {
                    x.delay(100);
                    return "late";
                })
        );
        assertNull(maybe);
    }

    @Test
    @Timeout(2)
        // Retry with backoff: succeeds on the 3rd attempt with maxRetries=3, then demonstrates failure after maxRetries=2.
        // Race-avoidance: short fixed delays keep run time bounded; AtomicInteger counts attempts deterministically.
    void retrySucceedsAndFails() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String res = runBlockingCpu(s ->
                s.retry(3, Duration.ofMillis(5), 2.0, ctx -> {
                    int n = attempts.incrementAndGet();
                    if (n < 3) throw new RuntimeException("fail " + n);
                    return "ok@" + n;
                })
        );
        assertEquals("ok@3", res);
        assertEquals(3, attempts.get());

        assertThrows(RuntimeException.class, () ->
                runBlockingCpu(s ->
                        s.retry(2, Duration.ofMillis(1), 2.0, __ -> {
                            throw new RuntimeException("always");
                        })
                )
        );
    }
}