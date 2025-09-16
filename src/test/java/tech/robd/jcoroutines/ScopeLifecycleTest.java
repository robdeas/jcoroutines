/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/ScopeLifecycleTest.java
 description: Scope lifecycle tests: close() is idempotent and cancels children; double join is safe; join() propagates cancellation as CancellationException.
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
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.TestAwaitUtils;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.newScope;

final class ScopeLifecycleTest {

    @Test
    @Timeout(3)
        // Idempotent close: calling close() twice should be safe and cancel all children.
        // Race-avoidance: CountDownLatch 'gate' ensures the child entered delay; awaitDone(h, 500) lets cancellation propagate
        // before asserting handle/CF states and join() throwing CancellationException.
    void closeIsIdempotentAndCancelsChildren() throws Exception {
        try (JCoroutineScope scope = newScope()) {
            var gate = new CountDownLatch(1);
            JCoroutineHandle<Void> h = scope.launch(s -> {
                gate.countDown();
                s.delay(10_000);
            });
            assertTrue(gate.await(500, MILLISECONDS));

            scope.close();
            scope.close(); // idempotent

            TestAwaitUtils.awaitDone(h, 500);
            assertTrue(h.result().isCancelled() || h.result().isCompletedExceptionally());
            assertThrows(CancellationException.class, h::join);
        }
    }


    @Test
    @Timeout(2)
        // Join semantics: join() can be called twice and returns the same value without throwing.
        // No race: small delay inside async ensures scheduling; test asserts state flags afterwards.
    void doubleJoinIsSafe() throws Exception {
        try (JCoroutineScope scope = newScope()) {
            JCoroutineHandle<Integer> h = scope.async(s -> {
                s.delay(10);
                return 7;
            });
            assertEquals(7, h.join());
            // second join shouldn't throw (CompletableFuture#get is idempotent)
            assertEquals(7, h.join());
            assertTrue(h.isCompleted());
            assertFalse(h.isActive());
        }
    }

    @Test
    @Timeout(2)
        // Cancellation propagation: cancelling a handle should make join() throw CancellationException.
        // Race-avoidance: long delay keeps the task in-flight so cancel() isn't a pre-start no-op.
    void joinPropagatesCancellationAsException() {
        try (JCoroutineScope scope = newScope()) {
            JCoroutineHandle<Void> h = scope.launch(s -> s.delay(1000));
            h.cancel();
            assertThrows(CancellationException.class, h::join);
        }
    }
}
