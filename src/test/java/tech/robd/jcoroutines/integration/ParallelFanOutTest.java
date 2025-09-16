/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/integration/ParallelFanOutTest.java
 description: Integration test for fan-out over IO-like tasks using VT + CompletableFuture joins under runBlockingVT.
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParallelFanOutTest {


    @Test
        // VT fan-out: map URLs to ctx.async(...) tasks, collect CompletableFutures, then join all under runBlockingVT.
        // IO simulation: Thread.sleep to emulate blocking IO on virtual threads.
        // No race: all joins occur inside the same blocking scope; order is not assumed beyond simple prefix asserts.
    void vtFanOut_overIoTasks() throws Exception {
        List<String> urls = List.of("u1", "u2", "u3");

        List<String> bodies = Coroutines.runBlockingVT(ctx -> {
            // Use CompletableFuture to avoid checked exceptions in streams
            List<CompletableFuture<String>> futures = urls.stream()
                    .map(u -> ctx.async(c -> {
                        // simulate blocking IO on a virtual thread
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "GET " + u + " -> 200";
                    }).result())
                    .toList();

            return futures.stream().map(CompletableFuture::join).toList();
        });

        assertEquals(3, bodies.size());
        assertTrue(bodies.stream().allMatch(b -> b.startsWith("GET ")));
    }

    private String fakeHttp(String url) {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "GET " + url + " -> 200";
    }
}
