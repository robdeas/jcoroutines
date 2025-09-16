/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ErrorHandlingAndBridgeTest.java
 description: Integration tests for exception propagation from suspended bodies and a synchronous blocking facade over suspend-style logic.
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
import tech.robd.jcoroutines.SuspendContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ErrorHandlingAndBridgeTest {

    @Test
        // Exception propagation: a RuntimeException thrown inside runBlockingVT should surface to the caller.
        // No race: exception is thrown immediately in the suspended lambda.
    void exceptionsPropagateFromSuspendedBody() {
        RuntimeException boom = assertThrows(RuntimeException.class, () ->
                Coroutines.runBlockingVT(ctx -> {
                    throw new RuntimeException("boom");
                })
        );
        assertEquals("boom", boom.getMessage());
    }
    @Test
        // Synchronous facade: a blocking method delegates to a suspend-style impl via runBlockingVT and returns its value.
        // Stability: small delay inside the suspend impl ensures test asserts value, not timing.
    void blockingFacade_callsInternalSuspendStyleMethod() throws Exception {
        String v = fetchUserBlocking(7L);
        assertEquals("user#7", v);
    }

    private String fetchUserBlocking(long id) throws Exception {
        return Coroutines.runBlockingVT(ctx -> fetchUser(ctx, id));
    }

    // pretend suspend-style impl
    private String fetchUser(SuspendContext c, long id) {
        c.delay(10);
        return "user#" + id;
    }
}
