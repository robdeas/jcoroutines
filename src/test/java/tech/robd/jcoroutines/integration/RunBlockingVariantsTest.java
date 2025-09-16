/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/RunBlockingVariantsTest.java
 description: Verifies thread choices for runBlocking (PT), runBlockingVT (VT), and runBlockingIO (VT ok-to-block).
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
import tech.robd.jcoroutines.tools.Probes;

import static org.junit.jupiter.api.Assertions.*;

public class RunBlockingVariantsTest {

    @Test
        // Platform thread policy: runBlocking runs on PT for CPU-bound work; checks Probes.here().virtual() == false.
        // No race: cpuWork returns immediately; assertion purely about thread type and call-count.
    void runBlocking_usesPlatformThreads_forCpuWork() throws Exception {
        Probes.WorkerService svc = new Probes.WorkerService();

        Probes.ThreadInfo info = Coroutines.runBlocking(ctx -> {
            assertEquals(42, svc.cpuWork(42), "cpuWork");
            return Probes.here();
        });

        assertFalse(info.virtual(), "runBlocking should use platform threads by design");
        assertEquals(1, svc.calls(), "calls");
    }

    @Test
        // VT variant: runBlockingVT should run on a Virtual Thread.
        // No race: single-step work; assertion just checks thread type and call-count.
    void runBlockingVT_usesVirtualThreads() throws Exception {
        Probes.WorkerService svc = new Probes.WorkerService();

        Probes.ThreadInfo info = Coroutines.runBlockingVT(ctx -> {
            svc.ping();
            return Probes.here();
        });

        assertTrue(info.virtual(), "runBlockingVT should use virtual threads");
        assertEquals(1, svc.calls(), "calls");
    }

    @Test
        // IO variant: runBlockingIO runs on VT and is allowed to block (simulated by ioWork sleep).
        // No race: blocking is intentional; assertion checks thread type and call-count.
    void runBlockingIO_isVirtual_and_okToBlock() throws Exception {
        Probes.WorkerService svc = new Probes.WorkerService();

        Probes.ThreadInfo info = Coroutines.runBlockingIO(ctx -> {
            String v = svc.ioWork(50, "ok");
            assertEquals("ok", v, "io value");
            return Probes.here();
        });

        assertTrue(info.virtual(), "runBlockingIO should use virtual threads");
        assertEquals(1, svc.calls(), "calls");
    }
}
