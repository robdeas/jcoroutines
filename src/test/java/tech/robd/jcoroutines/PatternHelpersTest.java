/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/PatternHelpersTest.java
 description: Tests for PatternHelpers: multi-stage pipeline transformation and fan-out/fan-in result coverage.
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.runBlockingCpu;

final class PatternHelpersTest {

    @Test
    @Timeout(2)
        // Pipeline: three-stage transformation should yield deterministic per-item mapping.
        // Race-avoidance: runs entirely inside one runBlockingCpu; stages close channels explicitly to avoid flakiness.
    void multiStagePipelineTransformsInOrder() {
        List<String> out = runBlockingCpu(s ->
                PatternHelpers.multiStagePipeline(s, List.of("A", "B", "C"))
        );
        assertNotNull(out);
        assertEquals(3, out.size());
        assertEquals("processed[transformed[a]]", out.get(0));
        assertEquals("processed[transformed[b]]", out.get(1));
        assertEquals("processed[transformed[c]]", out.get(2));
    }

    @Test
    @Timeout(2)
        // Fan-out/fan-in: processes all tasks; result count equals input count (order unspecified).
        // Race-avoidance: structure and bounded queues inside PatternHelpers avoid races; assertion checks content not order.
    void fanOutFanInProducesForEachInput() {
        List<String> tasks = List.of("t1", "t2", "t3", "t4", "t5");
        List<String> results = runBlockingCpu(s ->
                PatternHelpers.fanOutFanIn(s, tasks, 3)
        );
        assertNotNull(results);
        assertEquals(tasks.size(), results.size());
        // every item should be present (order may vary by worker scheduling)
        assertTrue(results.stream().allMatch(r -> r.contains("completed: t")));
    }
}
