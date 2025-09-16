/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/diagnostics/NoOpD.java
 description: No-op Diagnostics implementation. Singleton enum used when diagnostics are disabled.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: no
 [/File Info]
*/

/*
 * Copyright (c) 2025 Rob Deas Ltd.
 *
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

package tech.robd.jcoroutines.diagnostics;

/**
 * No-op diagnostics implementation.
 *
 * <p>Returned by {@link Diagnostics#of(Class)} when diagnostics are globally disabled.
 * Provides zero-overhead methods that do nothing.</p>
 */
enum NoOpD implements Diagnostics {
    /**
     * Singleton instance.
     */
    INSTANCE;

    @Override
    public Class<?> owner() {
        return Diagnostics.class;
    }

    @Override
    public void debug(String msg, Object... args) {
        // no-op
    }

    @Override
    public void info(String msg, Object... args) {
        // no-op
    }

    @Override
    public void warn(String msg, Object... args) {
        // no-op
    }

    @Override
    public void error(String msg, Object... args) {
        // no-op
    }
}
