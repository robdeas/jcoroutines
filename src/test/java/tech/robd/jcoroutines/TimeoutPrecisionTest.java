/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/TimeoutPrecisionTest.java
 description: Precision tests for withTimeout and withTimeoutOrNull (bounds tuned for CI jitter).
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
 * You may not use this file except in compliance with the License.
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

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.runBlockingCpu;

final class TimeoutPrecisionTest {

    @Test
    @Timeout(2)
        // withTimeout should cancel roughly within its budget; we allow generous CI jitter in the bound.
        // Race-avoidance: inner delay (200ms) is >> timeout (30ms) to ensure timeout path deterministically.
    void withTimeoutCancelsRoughlyOnTime() {
        long start = System.nanoTime();
        assertThrows(java.util.concurrent.CancellationException.class, () ->
                runBlockingCpu(s -> s.withTimeout(Duration.ofMillis(30), x -> {
                    x.delay(200);
                    return "late";
                }))
        );
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // generous bound for CI flakiness; adjust to your infra
        assertTrue(elapsedMs <= 150, "elapsed=" + elapsedMs + "ms");
    }

    @Test
    @Timeout(2)
        // withTimeoutOrNull returns null on timeout.
        // Race-avoidance: inner delay (200ms) is >> timeout (20ms) to ensure deterministic timeout.
    void withTimeoutOrNullReturnsNull() {
        String res = runBlockingCpu(s -> s.withTimeoutOrNull(Duration.ofMillis(20), x -> {
            x.delay(200);
            return "late";
        }));
        assertNull(res);
    }
}
