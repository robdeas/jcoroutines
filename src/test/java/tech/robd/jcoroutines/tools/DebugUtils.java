/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/tools/DebugUtils.java
 description: Lightweight, breakpoint-friendly debug and test logging utility with PRINT, DEBUG, TRACE levels and POINT markers.
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
package tech.robd.jcoroutines.tools;

import java.io.PrintStream;
import java.time.LocalTime;
import java.util.function.Supplier;

/**
 * Lightweight debug/test logging utility.
 * <p>
 * Unlike a full logging framework, {@code DebugUtils} is intentionally simple:
 * <ul>
 *   <li>Messages are written directly to {@code System.out} (or a pluggable stream).</li>
 *   <li>All logs funnel through a single location — useful for setting breakpoints.</li>
 *   <li>Levels are {@link Level#PRINT}, {@link Level#DEBUG}, {@link Level#TRACE}.</li>
 *   <li>{@link Level#POINT} markers are always printed and act as test breadcrumbs.</li>
 * </ul>
 * <p>
 * This is intended for <b>tests</b> and for investigating Heisenbugs in concurrency/coroutines
 * without cluttering production logs.
 */
public final class DebugUtils {
    private DebugUtils() {
    }

    /**
     * Logging levels supported by {@link DebugUtils}.
     */
    public enum Level {
        /**
         * Special marker, always printed regardless of current level.
         */
        POINT(-1),
        /**
         * Suppress all logs.
         */
        NONE(0),
        /**
         * Standard test print messages.
         */
        PRINT(1),
        /**
         * Debug-level detail.
         */
        DEBUG(2),
        /**
         * Trace-level verbosity.
         */
        TRACE(3);

        private final int value;

        Level(int v) {
            this.value = v;
        }

        /**
         * @return the numeric value of this level.
         */
        public int getValue() {
            return value;
        }

        /**
         * Parse a {@code Level} from an integer.
         * Falls back to {@link #PRINT} if not recognized.
         */
        public static Level fromValue(int v) {
            for (Level l : values()) if (l.value == v) return l;
            return PRINT;
        }

        /**
         * Parse a {@code Level} from a string.
         * Accepts enum names (e.g. {@code "DEBUG"}) or numeric values (e.g. {@code "2"}).
         * Falls back to {@link #PRINT} if not recognized.
         */
        public static Level fromString(String s) {
            if (s == null) return PRINT;
            try {
                return fromValue(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                try {
                    return Level.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException ignored2) {
                    return PRINT;
                }
            }
        }
    }

    // Configurable output (capture-friendly)
    private static volatile PrintStream OUT = System.out;

    /**
     * Redirect log output.
     *
     * @param out custom PrintStream (falls back to System.out if null)
     */
    public static void setOut(PrintStream out) {
        OUT = (out == null ? System.out : out);
    }

    /**
     * @return the current PrintStream used for logging.
     */
    public static PrintStream getOut() {
        return OUT;
    }

    // Runtime level (system prop/env default)
    private static volatile Level CURRENT_LEVEL = initLevel();

    private static Level initLevel() {
        String prop = System.getProperty("jcoroutines.debug");
        if (prop == null) prop = System.getenv("JCOROUTINES_DEBUG");
        return Level.fromString(prop);
    }

    /**
     * Set the global debug level.
     */
    public static void setLevel(Level level) {
        CURRENT_LEVEL = (level == null ? Level.PRINT : level);
    }

    /**
     * @return the current global debug level.
     */
    public static Level getLevel() {
        return CURRENT_LEVEL;
    }

    /**
     * Temporarily set a level for the duration of a block, restoring afterwards.
     */
    public static void withLevel(Level level, Runnable block) {
        Level old = getLevel();
        try {
            setLevel(level);
            block.run();
        } finally {
            setLevel(old);
        }
    }

    // ---- Simple (no context)

    /**
     * Print a basic message at {@link Level#PRINT}.
     */
    public static void print(String msg) {
        log(Level.PRINT, msg, false);
    }

    /**
     * Print a basic message at {@link Level#DEBUG}.
     */
    public static void debug(String msg) {
        log(Level.DEBUG, msg, false);
    }

    /**
     * Print a basic message at {@link Level#TRACE}.
     */
    public static void trace(String msg) {
        log(Level.TRACE, msg, false);
    }

    // ---- Supplier overloads to avoid work when disabled

    /**
     * Print with lazy evaluation at {@link Level#PRINT}.
     */
    public static void print(Supplier<String> msg) {
        if (enabled(Level.PRINT)) log(Level.PRINT, msg.get(), false);
    }

    /**
     * Print with lazy evaluation at {@link Level#DEBUG}.
     */
    public static void debug(Supplier<String> msg) {
        if (enabled(Level.DEBUG)) log(Level.DEBUG, msg.get(), false);
    }

    /**
     * Print with lazy evaluation at {@link Level#TRACE}.
     */
    public static void trace(Supplier<String> msg) {
        if (enabled(Level.TRACE)) log(Level.TRACE, msg.get(), false);
    }

    // ---- Context versions (timestamp + thread + class)

    /**
     * Print with timestamp/thread/class at {@link Level#PRINT}.
     */
    public static void printWithContext(String msg) {
        log(Level.PRINT, msg, true);
    }

    /**
     * Print with timestamp/thread/class at {@link Level#DEBUG}.
     */
    public static void debugWithContext(String msg) {
        log(Level.DEBUG, msg, true);
    }

    /**
     * Print with timestamp/thread/class at {@link Level#TRACE}.
     */
    public static void traceWithContext(String msg) {
        log(Level.TRACE, msg, true);
    }

    /**
     * Print a {@code POINT} marker.
     * <p>
     * POINT markers are always emitted and contain only a simple string
     * (no timestamp/thread/class), making them ideal for easy string
     * matching in tests.
     *
     * @param message marker text
     */
    public static void markPoint(String message) {
        OUT.println("POINT: " + message);
    }

    /**
     * Checks if the specified logging level is enabled based on the current global debug level.
     *
     * @param level the logging level to check
     * @return {@code true} if the specified level is enabled (i.e., its value is less than or equal to the current global debug level),
     * otherwise {@code false}
     */
    private static boolean enabled(Level level) {
        return CURRENT_LEVEL.getValue() >= level.getValue();
    }

    /**
     * Logs a message at the specified logging level. The message can optionally
     * include additional context, such as a timestamp, the current thread name,
     * and the calling class name.
     *
     * @param level       the logging level to use for the message
     * @param message     the text message to log
     * @param withContext if {@code true}, includes context information (timestamp,
     *                    thread name, and calling class name) in the log output
     */
    private static void log(Level level, String message, boolean withContext) {
        if (!enabled(level)) return;

        String prefix = switch (level) {
            case PRINT -> "PRINTED";
            case DEBUG -> "PRINTED DEBUG";
            case TRACE -> "PRINTED TRACE";
            case POINT -> "POINT";
            default -> "PRINTED";
        };

        if (withContext) {
            String timestamp = LocalTime.now().toString();
            String thread = Thread.currentThread().getName();
            String klass = getCallingClassName();
            OUT.printf("%s: [%s] [%s] [%s] %s%n", prefix, timestamp, thread, klass, message);
        } else {
            OUT.println(prefix + ": " + message);
        }
    }

    /**
     * Retrieves the simple name of the class that called the method. This is determined by analyzing
     * the current thread's stack trace and extracting the class name from the appropriate stack
     * trace element.
     *
     * @return the simple name of the calling class, or "Unknown" if the calling class cannot be determined
     */
    private static String getCallingClassName() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int idx = Math.min(4, st.length - 1);
        if (idx >= 0 && idx < st.length) {
            String clazzName = st[idx].getClassName();
            int dot = clazzName.lastIndexOf('.');
            return (dot >= 0 ? clazzName.substring(dot + 1) : clazzName);
        }
        return "Unknown";
    }

    /**
     * Print a stack trace through DebugUtils.
     * <p>
     * Unlike {@code e.printStackTrace()}, this funnels output through the
     * configured DebugUtils PrintStream, and prefixes with a level.
     *
     * @param t the Throwable whose stack trace to print
     */
    public static void printStackTrace(Throwable t) {
        printStackTrace(Level.PRINT, t);
    }

    /**
     * Print a stack trace at a specific DebugUtils level.
     *
     * @param level logging level to use for the header
     * @param t     the Throwable whose stack trace to print
     */
    public static void printStackTrace(Level level, Throwable t) {
        if (t == null) return;
        if (level != Level.POINT && !enabled(level)) return;

        String prefix = switch (level) {
            case PRINT -> "PRINTED";
            case DEBUG -> "PRINTED DEBUG";
            case TRACE -> "PRINTED TRACE";
            case POINT -> "POINT";
            default -> "PRINTED";
        };

        OUT.println(prefix + ": Exception - " + t);

        for (StackTraceElement ste : t.getStackTrace()) {
            OUT.println("\tat " + ste);
        }

        Throwable cause = t.getCause();
        if (cause != null) {
            OUT.println("Caused by: " + cause);
            for (StackTraceElement ste : cause.getStackTrace()) {
                OUT.println("\tat " + ste);
            }
        }
    }


}
