/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ScopeBasicsTest.java
 description: Basic scope tests: launch on default VT, async returns result via CF join, and delay cooperates in VT.
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
import tech.robd.jcoroutines.JCoroutineScope;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.tools.Probes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScopeBasicsTest {

    @Test
        // VT launch: scope.launch should run on the default Virtual Thread executor.
        // Race-avoidance: a brief runBlockingVT delay yields so the launched task can increment the probe before asserting.
    void scope_launch_runsOnDefaultVT() throws Exception {
        Probes.WorkerService svc = new Probes.WorkerService();
        try (JCoroutineScope scope = Coroutines.newScope()) {  // defaults to VT

            scope.launch(ctx -> {
                svc.ping();
                return;
            });
            // simple yield: give the launched task a moment (tests can also use latches)
            Coroutines.runBlockingVT(ctx -> {
                ctx.delay(20);
                return null;
            });

            assertEquals(1, svc.calls(), "launch increments calls");

        }
    }

    @Test
        // Async result: scope.async returns a handle whose CF (result()) joins with the computed value.
        // No race: simple cpuWork returns promptly; assertion only checks value and call count.
    void scope_async_returnsResult() {
        Probes.WorkerService svc = new Probes.WorkerService();
        try (JCoroutineScope scope = Coroutines.newScope()) {
            JCoroutineHandle<Integer> handleF = scope.async(ctx -> svc.cpuWork(10));
            var f = handleF.result();
            int v = f.join();
            assertTrue(v != 0, "some cpu result");
            assertEquals(1, svc.calls(), "calls");
        }
    }

    @Test
        // Delay cooperates on VT: elapsed time around runBlockingVT(ctx.delay(50)) should be >= ~45ms.
        // No race: wall clock tolerance accounts for scheduling jitter.
    void delay_works_inVT() throws Exception {
        long start = System.currentTimeMillis();
        Coroutines.runBlockingVT(ctx -> {
            ctx.delay(50);
            return null;
        });
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 45, "delay should roughly hold");
    }
}
