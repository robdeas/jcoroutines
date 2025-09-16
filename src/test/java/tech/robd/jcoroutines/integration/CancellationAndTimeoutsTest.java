/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/CancellationAndTimeoutsTest.java
 description: Integration tests for cancellation propagation and CompletableFuture timeouts in jcoroutines.
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
package tech.robd.jcoroutines.integration;

import org.junit.jupiter.api.Test;
import tech.robd.jcoroutines.Coroutines;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

public class CancellationAndTimeoutsTest {

    @Test
        // Cancelling the CF returned by ctx.async should cancel the child and make join() throw CancellationException.
        // Race-avoidance: the child calls delay(1000) so cancellation happens while work is in-flight, not pre-start.
    void childCancellation_propagates() throws Exception {
        Coroutines.runBlockingVT(ctx -> {
            var jobHandle = ctx.async(c -> {
                c.delay(1_000);
                return null;
            });
            var job = jobHandle.result();

            assertTrue(job.cancel(true), "cancel returns true");
            assertTrue(job.isCancelled(), "isCancelled");

            // join() on a cancelled CF throws CancellationException
            assertThrows(CancellationException.class, job::join);
            return null;
        });
    }


    @Test
        // CompletableFuture.orTimeout triggers a timeout; we accept common wrapping shapes:
        //   - CompletionException with TimeoutException cause,
        //   - direct TimeoutException, or
        //   - RuntimeException whose cause is TimeoutException (from certain runBlocking wrappers).
        // No race: op.delay(300ms) >> timeout(100ms).
    void async_orTimeout_timesOut() {
        Throwable thrown = assertThrows(Throwable.class, () ->
                Coroutines.runBlockingVT(ctx -> {
                    var op = ctx.async(c -> {
                        c.delay(300);
                        return null;
                    }).result();
                    // This schedules a timeout on the CF and then we join it.
                    op.orTimeout(100, TimeUnit.MILLISECONDS).join();
                    return null;
                })
        );

        switch (thrown) {
            case CompletionException ce ->
                    assertInstanceOf(TimeoutException.class, ce.getCause(), "CompletionException cause should be TimeoutException");
            case TimeoutException timeoutException -> {
                // direct path: some wrappers/paths rethrow the cause itself
            }
            case RuntimeException re when re.getCause() instanceof TimeoutException -> {
                // executor runBlocking() path that wraps checked causes in RuntimeException
            }
            case null, default ->
                    fail("Expected timeout via CompletionException(TimeoutException) or TimeoutException, but got: " + thrown);
        }
    }

}
