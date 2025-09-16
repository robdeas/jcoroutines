/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/diagnostics/DiagnosticsBackend.java
 description: Internal diagnostics sink that forwards to SLF4J (LocationAwareLogger when available).
              Global on/off switch via system property `jcoroutines.diag` and enable()/disable().
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LocationAwareLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Internal diagnostics sink that delegates to SLF4J.
 *
 * <p>Features:
 * <ul>
 *   <li>Global enable/disable via system property {@code jcoroutines.diag}
 *       (default {@code false}) and programmatic {@link #enable()}/{@link #disable()}.</li>
 *   <li>Per-owner {@link Logger} cache keyed by {@link Class}.</li>
 *   <li>Uses {@link LocationAwareLogger} when available to preserve caller location.</li>
 *   <li>No-ops fast when disabled.</li>
 * </ul>
 *
 * <p>This class is package-private and used by {@link Diagnostics} implementations.
 */
final class DiagnosticsBackend {

    // 🧩 Section: constants-and-state
    /**
     * Fully-qualified class name for LocationAwareLogger to attribute calls correctly.
     */
    private static final String FQClassName = DiagnosticsBackend.class.getName();

    /**
     * Logger cache keyed by owner class.
     */
    private static final ConcurrentMap<Class<?>, Logger> LOGGERS = new ConcurrentHashMap<>();

    /**
     * System property to enable diagnostics: {@code -Djcoroutines.diag=true}.
     */
    public static final String DIAGNOSTICS_PROPERTY_NAME = "jcoroutines.diag";

    /**
     * Volatile flag; reads are cheap, writes flip the global state.
     */
    private static volatile boolean enabled =
            "true".equalsIgnoreCase(
                    System.getProperty(DIAGNOSTICS_PROPERTY_NAME, "false").trim()
            );
    // [/🧩 Section: constants-and-state]

    private DiagnosticsBackend() {
        // no instances
    }

    // 🧩 Section: enablement

    /**
     * Enable diagnostics globally (until disabled or JVM exit).
     */
    static void enable() {
        enabled = true;
    }

    /**
     * Disable diagnostics globally.
     */
    static void disable() {
        enabled = false;
    }

    /**
     * @return whether diagnostics are currently enabled.
     */
    static boolean isEnabled() {
        return enabled;
    }
    // [/🧩 Section: enablement]

    // 🧩 Section: logger-cache

    /**
     * Resolve or create the SLF4J logger for the given owner.
     *
     * @param owner the class whose logger we use
     * @return cached or newly created logger
     */
    private static Logger logger(Class<?> owner) {
        return LOGGERS.computeIfAbsent(owner, LoggerFactory::getLogger);
    }
    // [/🧩 Section: logger-cache]

    // 🧩 Section: emitters

    /**
     * Emit a DEBUG-level message for the given owner (no-op if disabled).
     *
     * @param owner the owning class used to route the logger
     * @param msg   message pattern
     * @param args  arguments
     */
    static void debug(Class<?> owner, String msg, Object... args) {
        if (!enabled) return; // fast path
        Logger log = logger(owner);
        // 🧩 Point: emitters/debug-location-aware
        if (log instanceof LocationAwareLogger law) {
            law.log(null, FQClassName, LocationAwareLogger.DEBUG_INT, msg, args, null);
        } else if (log.isDebugEnabled()) {
            log.debug(msg, args);
        }
    }

    /**
     * Emit an INFO-level message for the given owner (no-op if disabled).
     */
    static void info(Class<?> owner, String msg, Object... args) {
        if (!enabled) return; // fast path
        Logger log = logger(owner);
        // 🧩 Point: emitters/info-location-aware
        if (log instanceof LocationAwareLogger law) {
            law.log(null, FQClassName, LocationAwareLogger.INFO_INT, msg, args, null);
        } else if (log.isInfoEnabled()) {
            log.info(msg, args);
        }
    }

    /**
     * Emit a WARN-level message for the given owner (no-op if disabled).
     */
    static void warn(Class<?> owner, String msg, Object... args) {
        if (!enabled) return; // fast path
        Logger log = logger(owner);
        // 🧩 Point: emitters/warn-location-aware
        if (log instanceof LocationAwareLogger law) {
            law.log(null, FQClassName, LocationAwareLogger.WARN_INT, msg, args, null);
        } else if (log.isWarnEnabled()) {
            log.warn(msg, args);
        }
    }

    /**
     * Emit an ERROR-level message for the given owner (no-op if disabled).
     */
    static void error(Class<?> owner, String msg, Object... args) {
        if (!enabled) return; // fast path
        Logger log = logger(owner);
        // 🧩 Point: emitters/error-location-aware
        if (log instanceof LocationAwareLogger law) {
            law.log(null, FQClassName, LocationAwareLogger.ERROR_INT, msg, args, null);
        } else if (log.isErrorEnabled()) {
            log.error(msg, args);
        }
    }
    // [/🧩 Section: emitters]
}
