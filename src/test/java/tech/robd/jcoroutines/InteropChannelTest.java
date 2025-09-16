/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/InteropChannelTest.java
 description: Optional interop round-trip on InteropChannel (null ↔ Optional.empty) and a simple smoke test.
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
import tech.robd.jcoroutines.tools.DebugUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InteropChannelTest {

    @Test
    @Timeout(2)
        // Interop Optional round-trip: sendInterop('Hello'), sendInterop(null), sendInterop('World');
        // receiveInterop yields Optional.of('Hello'), Optional.empty(), Optional.of('World').
        // Race-avoidance: all ops in one runBlockingCpu; close() occurs before final receives; finally ensures idempotent close.
    void optionalInteropRoundTrip() throws Exception {
        DebugUtils.print("Starting optionalInteropRoundTrip test");

        InteropChannel<String> ch = ChannelUtils.unlimitedInterop();
        DebugUtils.print("Created unlimited interop channel: " + ch.getChannelId());

        try {
            String result = Coroutines.runBlockingCpu(s -> {
                DebugUtils.print("Inside runBlocking with SuspendContext: " + s);

                // Send values including null
                DebugUtils.print("Sending 'Hello'");
                ChannelUtils.sendInterop(s, ch, "Hello");

                DebugUtils.print("Sending null");
                ChannelUtils.sendInterop(s, ch, null);

                DebugUtils.print("Sending 'World'");
                ChannelUtils.sendInterop(s, ch, "World");

                DebugUtils.print("Closing channel");
                ChannelUtils.close(ch);

                // Receive values
                DebugUtils.print("Receiving first value");
                Optional<String> r1 = ChannelUtils.receiveInterop(s, ch);
                DebugUtils.print("Received r1: " + r1);

                DebugUtils.print("Receiving second value");
                Optional<String> r2 = ChannelUtils.receiveInterop(s, ch);
                DebugUtils.print("Received r2: " + r2);

                DebugUtils.print("Receiving third value");
                Optional<String> r3 = ChannelUtils.receiveInterop(s, ch);
                DebugUtils.print("Received r3: " + r3);

                // Verify results
                DebugUtils.print("Verifying results");
                assertEquals(Optional.of("Hello"), r1, "First value should be 'Hello'");
                assertEquals(Optional.empty(), r2, "Second value should be empty");
                assertEquals(Optional.of("World"), r3, "Third value should be 'World'");

                DebugUtils.print("All assertions passed");
                return "test-completed";
            });

            DebugUtils.print("runBlocking returned: " + result);
            assertEquals("test-completed", result);
            DebugUtils.print("Test completed successfully");

        } catch (Exception e) {
            DebugUtils.print("Exception during test: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            DebugUtils.printStackTrace(e);
            throw e;
        } finally {
            DebugUtils.print("In finally block");
            if (!ch.isClosed()) {
                DebugUtils.print("Channel still open, closing it");
                ChannelUtils.close(ch);
            } else {
                DebugUtils.print("Channel already closed");
            }
        }
    }

    @Test
    @Timeout(2)
        // Smoke test: verifies test discovery/bootstrapping; no race assumptions.
    void simpleTest() throws Exception {
        DebugUtils.print("Running simple test to verify test discovery");
        assertTrue(true, "This should always pass");
        DebugUtils.print("Simple test passed");
    }
}