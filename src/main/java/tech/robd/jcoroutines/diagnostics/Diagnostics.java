/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/diagnostics/Diagnostics.java
 description: Lightweight diagnostics facade. Instance methods forward to DiagnosticsBackend,
              with factories for active/dynamic/noop behavior.
 license: Apache-2.0
 author: Rob Deas
 editable: yes
 structured: yes
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
 * Minimal diagnostics/logging facade bound to an owning {@link Class}.
 * <p>
 * Instance methods forward to a static {@code DiagnosticsBackend} so this API remains
 * tiny and easy to disable in production builds. Factories control behavior:
 * <ul>
 *   <li>{@link #of(Class)} – resolves to a no-op if globally disabled.</li>
 *   <li>{@link #dynamic(Class)} – always active instance; backend flag checked per-call.</li>
 *   <li>{@link #noop()} – explicit no-op instance.</li>
 * </ul>
 * Intended for internal test diagnostics and lightweight library tracing.
 */
@FunctionalInterface
public interface Diagnostics {

    // 🧩 Section: identity

    /**
     * Owning class used for routing and formatting diagnostics output.
     *
     * @return the owner class associated with this diagnostics instance
     */
    Class<?> owner();
    // [/🧩 Section: identity]

    // 🧩 Section: forwarding

    /**
     * Emit a debug message via the {@code DiagnosticsBackend}.
     *
     * @param msg  printf/SLF4J-style message pattern
     * @param args arguments to format into {@code msg}
     */
    default void debug(String msg, Object... args) {
        DiagnosticsBackend.debug(owner(), msg, args);
    }

    /**
     * Emit an info message via the {@code DiagnosticsBackend}.
     *
     * @param msg  printf/SLF4J-style message pattern
     * @param args arguments to format into {@code msg}
     */
    default void info(String msg, Object... args) {
        DiagnosticsBackend.info(owner(), msg, args);
    }

    /**
     * Emit a warning message via the {@code DiagnosticsBackend}.
     *
     * @param msg  printf/SLF4J-style message pattern
     * @param args arguments to format into {@code msg}
     */
    default void warn(String msg, Object... args) {
        DiagnosticsBackend.warn(owner(), msg, args);
    }

    /**
     * Emit an error message via the {@code DiagnosticsBackend}.
     *
     * @param msg  printf/SLF4J-style message pattern
     * @param args arguments to format into {@code msg}
     */
    default void error(String msg, Object... args) {
        DiagnosticsBackend.error(owner(), msg, args);
    }
    // [/🧩 Section: forwarding]

    // 🧩 Section: factories

    /**
     * Create a diagnostics instance for {@code owner}. If the backend is globally disabled,
     * returns a no-op to avoid overhead.
     *
     * @param owner the owning class (non-null)
     * @return active or no-op diagnostics depending on backend state
     */
    static Diagnostics of(Class<?> owner) {
        return DiagnosticsBackend.isEnabled() ? new ActiveD(owner) : NoOpD.INSTANCE;
    }

    /**
     * Create a diagnostics instance that always delegates, checking enablement per call.
     * Useful when the backend enablement may change over time.
     *
     * @param owner the owning class (non-null)
     * @return an active diagnostics instance
     */
    static Diagnostics dynamic(Class<?> owner) {
        return new ActiveD(owner); // checks flag at each call via backend
    }

    /**
     * Return a no-op diagnostics instance.
     *
     * @return a no-op diagnostics singleton
     */
    static Diagnostics noop() {
        return NoOpD.INSTANCE;
    }
    // [/🧩 Section: factories]
}
