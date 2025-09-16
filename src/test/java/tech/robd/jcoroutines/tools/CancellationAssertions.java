/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/tools/CancellationAssertions.java
 description: Test utility that normalizes different cancellation shapes (direct or wrapped) into a single assertion helper.
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

import org.junit.jupiter.api.function.Executable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Assertion helpers that treat multiple <em>cancellation shapes</em> as equivalent.
 * <p>
 * Depending on the code path under test, cancellation may surface as:
 * </p>
 * <ul>
 *   <li>a direct {@link CancellationException} (e.g., {@code handle.join()}), or</li>
 *   <li>a {@link CompletionException} whose {@linkplain CompletionException#getCause() cause}
 *       is a {@link CancellationException} (e.g., {@code future.join()}).</li>
 * </ul>
 *
 * <p>{@code CancellationAssertions} unifies these possibilities so tests can read:
 * <pre>{@code
 * expectCancelled(handle::join);
 * // or
 * expectCancelled(handle.result());   // CompletableFuture view
 * }</pre>
 * </p>
 *
 * <p><strong>Thread-safety:</strong> Stateless and safe to use from any thread.</p>
 *
 * @author Rob Deas
 * @since 0.1.0
 */
public final class CancellationAssertions {
    private CancellationAssertions() { /* utility class */ }

    /**
     * Asserts that executing {@code op} ends in cancellation, accepting both a direct
     * {@link CancellationException} and a {@link CompletionException} whose cause is a
     * {@code CancellationException}.
     *
     * <p>If neither shape is observed, the assertion fails with a descriptive message.</p>
     *
     * @param op the operation to execute (typically a {@code join}-like lambda)
     * @throws AssertionError if a non-cancellation exception is thrown or no exception is thrown
     */
    public static void expectCancelled(Executable op) {
        try {
            op.execute();
            fail("Expected CancellationException (direct) or CompletionException(cause=CancellationException)");
        } catch (CancellationException ok) {
            // direct CE — acceptable
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            assertInstanceOf(CancellationException.class, cause,
                    "Expected CompletionException(cause=CancellationException) but was: " + cause);
        } catch (Throwable t) {
            fail("Expected cancellation; got: " + t);
        }
    }

    /**
     * Convenience overload for a {@link CompletableFuture}: asserts that {@link CompletableFuture#join()}
     * results in cancellation (direct or wrapped).
     *
     * @param cf the future to check
     * @throws AssertionError if {@code cf.join()} does not end in a recognized cancellation shape
     */
    public static void expectCancelled(CompletableFuture<?> cf) {
        expectCancelled(cf::join);
    }
}
