/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/ChannelStrictVsInteropNullsTest.java
 description: Strict vs interop null behavior: strict Channel rejects null; InteropChannel maps null ↔ Optional.empty() safely.
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
import tech.robd.jcoroutines.tools.Nulls;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tech.robd.jcoroutines.Coroutines.runBlockingCpu;

final class ChannelStrictVsInteropNullsTest {
    /**
     * Tests that a strictly-typed {@link Channel} instance rejects null payloads
     * by throwing a {@link NullPayloadException} when an attempt is made to send
     * a null value to the channel. Ensures that the strict contract of the
     * {@code Channel} is upheld and invalid inputs are not allowed.
     *
     * <ul>
     *    <li>Creates an unlimited capacity {@link Channel} instance.</li>
     *    <li>Attempts to send a null value to the channel within a coroutine
     *        using runBlocking.</li>
     *    <li>Validates that the expected exception {@link NullPayloadException}
     *        is thrown for the null input.</li>
     *    <li>Ensures the channel is closed properly after the test execution.</li>
     * </ul>
     * <p>
     * Annotations:
     * <ul>
     *    <li>{@code @SuppressWarnings("NullAway")}: Suppresses static analysis
     *        warnings about potential null references in the test.</li>
     *    <li>{@code @Test}: Indicates this is a test method.</li>
     *    <li>{@code @Timeout(2)}: Enforces a time limit of 2 seconds for the test
     *        to complete.</li>
     * </ul>
     */
    @SuppressWarnings("NullAway")
    @Test
    @Timeout(2)
    // Strict contract: Channel<T> forbids null; sending Nulls.NULL_STRING should throw NullPayloadException.
    // No race: performed inside a single runBlockingCpu block; channel closed in finally.
    void strictChannelRejectsNull() {
        Channel<String> strict = Channel.unlimited();
        try {
            assertThrows(NullPayloadException.class, () ->
                    runBlockingCpu(s -> {
                        // this is deliberate TEST it's not allowed but shouldn't break
                        strict.send(s, Nulls.NULL_STRING);
                        return (Void) null;
                    })
            );
        } finally {
            strict.close();
        }
    }

    @Test
    @Timeout(2)
        // Interop mapping: sendInterop(null) -> Optional.empty(), sendInterop("value") -> Optional.of("value").
        // No race: producer/consumer calls happen in one runBlockingCpu block; channel closed deterministically.
    void interopChannelTransportsEmptyAndValue() {
        InteropChannel<String> interop = ChannelUtils.unlimitedInterop();
        try {
            String out = runBlockingCpu(s -> {
                ChannelUtils.sendInterop(s, interop, null);     // Optional.empty
                ChannelUtils.sendInterop(s, interop, "value");  // Optional.of
                ChannelUtils.close(interop);

                Optional<String> a = ChannelUtils.receiveInterop(s, interop);
                Optional<String> b = ChannelUtils.receiveInterop(s, interop);

                assertEquals(Optional.empty(), a);
                assertEquals(Optional.of("value"), b);
                return "ok";
            });
            assertEquals("ok", out);
        } finally {
            ChannelUtils.close(interop);
        }
    }
}
