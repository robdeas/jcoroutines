/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/ChannelTypeTest.java
 description: Manual smoke test for Channel type inference and factory methods; prints concrete implementation classes.
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

import tech.robd.jcoroutines.tools.DebugUtils;

public class ChannelTypeTest {
    public static void main(String[] args) {
        // Test 1: Direct channel creation — uses Channel.unlimited() factory directly
        Channel<String> channel1 = Channel.unlimited();

        // Test 2: ChannelUtils static factory — identical semantics to Channel.unlimited()
        Channel<String> channel2 = ChannelUtils.unlimited();

        // Test 3: Explicit type parameter — make the generic explicit for readability
        Channel<String> channel3 = ChannelUtils.<String>unlimited();

        // Test 4: var with explicit type hint — Java infers variable type from generic qualifier
        var channel4 = ChannelUtils.<String>unlimited();

        DebugUtils.print("All channels created successfully");
        DebugUtils.print("Channel1 type: " + channel1.getClass());
        DebugUtils.print("Channel2 type: " + channel2.getClass());
        DebugUtils.print("Channel3 type: " + channel3.getClass());
        DebugUtils.print("Channel4 type: " + channel4.getClass());
    }
}