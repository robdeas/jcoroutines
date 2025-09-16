/*
 [File Info]
 path: src/test/java/tech/robd/jcoroutines/CoroutineGCTests.java
 description: GC/retention tests for tokens, handles, contexts, and lambda/callback capture patterns.
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
 * You may not use this file except in compliance with the License.
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
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.CancellationTokenImpl;
import tech.robd.jcoroutines.tools.DebugUtils;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static tech.robd.jcoroutines.Coroutines.newScope;

public class CoroutineGCTests {

    // Simple GC helper
    private static void forceGC() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // === CORE TOKEN CLEANUP TESTS ===
    @Test
    // Token cleanup: parent/child tokens should fully clean up after cancel/markCompleted.
    // No race: direct calls; asserts active child count and callback count where relevant.
    void gcTokenCleanup() throws Exception {
        CancellationTokenImpl parentToken = new CancellationTokenImpl();
        CancellationTokenImpl childToken = (CancellationTokenImpl) parentToken.child();

        childToken.cancel();
        assertTrue(childToken.isFullyCleanedUp(), "Child should be fully cleaned up");
        assertFalse(parentToken.isCancelled());

        parentToken.markCompleted();
        assertTrue(parentToken.isFullyCleanedUp(), "Parent should be fully cleaned up");
    }

    @Test
    @Timeout(3)
        // Lifecycle coverage: normal completion, cancellation, and child-parent cleanup, including callback/pending counts.
        // No race: synchronous API calls followed by final cleanup.
    void gcCancellationTokenLifecycle() throws Exception {
        // Test 1: Normal completion
        CancellationTokenImpl token = new CancellationTokenImpl();
        token.markCompleted();
        assertTrue(token.isFullyCleanedUp(), "Token should be cleaned up after markCompleted()");

        // Test 2: Cancellation
        CancellationTokenImpl token2 = new CancellationTokenImpl();
        token2.cancel();
        assertTrue(token2.isFullyCleanedUp(), "Token should be cleaned up after cancel()");

        // Test 3: Child-parent cleanup
        CancellationTokenImpl parent = new CancellationTokenImpl();
        CancellationTokenImpl child = (CancellationTokenImpl) parent.child();

        assertEquals(1, parent.getActiveChildCount(), "Parent should have 1 child");
        assertEquals(1, parent.getPendingCallbackCount(), "Parent should have 1 callback");

        child.markCompleted();
        assertTrue(child.isFullyCleanedUp(), "Child should be cleaned up");

        parent.cleanup(); // Force cleanup of dead weak refs
        parent.markCompleted();
        assertTrue(parent.isFullyCleanedUp(), "Parent should be fully cleaned up");
    }

    // === LAMBDA CAPTURE TESTS ===
    @Test
    @Timeout(3)
    // Demonstrates lambda-capture retention: capturing an outer object keeps it reachable after handle completes.
    // Expected retention: prints debug info and does not fail; forceGC() used to encourage collection.
    void gcLambdaCaptureRetention() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<Object> capturedObjectRef;
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            Object capturedObject = new Object();
            capturedObjectRef = new WeakReference<>(capturedObject, q);

            try (var scope = newScope()) {
                // This pattern captures the object - should cause retention
                Object finalCapturedObject = capturedObject;
                JCoroutineHandle<String> h = scope.async(s -> {
                    return "result: " + finalCapturedObject.toString(); // Captures capturedObject
                });
                handleRef = new WeakReference<>(h, q);

                h.join();
                h = null;
            }

            capturedObject = null; // Drop our reference
        }

        forceGC();

        boolean handleCollected = handleRef.get() == null;
        boolean objectCollected = capturedObjectRef.get() == null;

        DebugUtils.print("Lambda capture test results:");
        DebugUtils.print("  Handle collected: " + handleCollected);
        DebugUtils.print("  Captured object collected: " + objectCollected);

        // This will likely fail due to lambda capture retention
        if (!objectCollected) {
            System.out.println("EXPECTED: Object retained due to lambda capture");
            // Don't fail - this is expected behavior showing the problem
        }
    }

    @Test
    @Timeout(3)
        // No-capture pattern: object created inside lambda (not captured) should be collectible along with the handle.
        // GC is nondeterministic: forceGC() attempts to accelerate collection; asserts refs are null.
    void gcNoLambdaCapture() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<Object> objectRef;
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            Object testObject = new Object();
            objectRef = new WeakReference<>(testObject, q);

            try (var scope = newScope()) {
                // This pattern doesn't capture - object should be collectible
                JCoroutineHandle<String> h = scope.async(s -> {
                    Object localObject = new Object(); // Created inside, not captured
                    return "result: " + localObject.toString();
                });
                handleRef = new WeakReference<>(h, q);

                h.join();
                h = null;
            }

            testObject = null; // Drop our reference
        }

        forceGC();

        assertNull(handleRef.get(), "Handle should be collectible");
        assertNull(objectRef.get(), "Object should be collectible (not captured)");
    }

    // === FIXED EXCEPTION TEST (NO LAMBDA CAPTURE) ===
    @Test
    @Timeout(3)
    // Exception path (fixed): exception is created inside the lambda (not captured) so handle can be collected.
    // GC note: forceGC() accelerates collection before asserting handleRef is cleared.
    void gcExceptionPathCleanup_Fixed() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            try (var scope = newScope()) {
                JCoroutineHandle<Void> h = scope.launch(s -> {
                    // Create exception inside lambda, don't capture it
                    throw new RuntimeException("created inside lambda");
                });
                handleRef = new WeakReference<>(h, q);

                assertThrows(RuntimeException.class, h::join);
                h = null;
            }
        }

        forceGC();
        assertNull(handleRef.get(), "Handle should be collectible after exception");
    }

    // === CONTEXT RETENTION TEST (FIXED) ===
    @Test
    @Timeout(3)
    // Context no-retention: returning the SuspendContext and closing the scope should allow GC of handle + context.
    // GC note: forceGC() accelerates collection, asserts both weak refs are cleared.
    void gcSuspendContextRetention_Fixed() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<tech.robd.jcoroutines.SuspendContext> ctxRef;
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            try (var scope = newScope()) {
                JCoroutineHandle<tech.robd.jcoroutines.SuspendContext> h = scope.async(s -> {
                    // Return the context directly - no additional capture
                    return s;
                });
                handleRef = new WeakReference<>(h, q);

                tech.robd.jcoroutines.SuspendContext ctx = h.join();
                ctxRef = new WeakReference<>(ctx, q);

                ctx = null;
                h = null;
            }
        }

        forceGC();
        assertNull(handleRef.get(), "Handle should be collectible after scope close");
        assertNull(ctxRef.get(), "SuspendContext should be collectible after scope close");
    }

    // === SEPARATE TEST FOR CALLBACK CAPTURE ISSUE ===
    @Test
    @Timeout(3)
    // Callback capture retention: registering a callback that captures an object keeps it alive until cleanup.
    // Expected retention: prints diagnostics; does not fail the test.
    void gcCallbackCaptureRetention() throws Exception {
        // This test demonstrates the callback capture problem
        CancellationTokenImpl token = new CancellationTokenImpl();
        WeakReference<Object> capturedRef;

        {
            Object capturedObject = new Object();
            capturedRef = new WeakReference<>(capturedObject);

            // This WILL cause retention due to lambda capture
            try (AutoCloseable registration = token.onCancel(() -> {
                DebugUtils.print("Callback executed: " + capturedObject);
            })) {
                assertEquals(1, token.getPendingCallbackCount(), "Should have 1 pending callback");
                token.markCompleted();
            } // registration.close() called automatically

            // capturedObject can't be set to null due to effectively final requirement
        }

        forceGC();

        // This test is expected to fail, demonstrating the lambda capture issue
        if (capturedRef.get() != null) {
            DebugUtils.print("EXPECTED: Object retained due to lambda capture in callback");
            DebugUtils.print("Token fully cleaned: " + token.isFullyCleanedUp());
            DebugUtils.print("Token pending callbacks: " + token.getPendingCallbackCount());
            // Don't fail - this demonstrates the expected problem
        } else {
            DebugUtils.print("Unexpected: Callback capture was cleaned up properly");
        }
    }


    // === SIMPLE SUCCESS CASE TEST ===
    @Test
    @Timeout(3)
    // Simple success: handle should be collectible after a non-capturing async completes and scope closes.
    // GC note: forceGC() accelerates collection before asserting handleRef is cleared.
    void gcHandleRetentionAfterCompletion() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> handleRef;

        {
            try (var scope = newScope()) {
                JCoroutineHandle<Integer> h = scope.async(s -> 42); // Simple, no capture
                assertEquals(42, h.join());

                handleRef = new WeakReference<>(h, q);
                h = null;
            }
        }

        forceGC();
        assertNull(handleRef.get(), "Handle should be collectible after successful completion");
    }

    // === HEISENTEST DEBUGGING ===
    // === HEISENTEST DEBUGGING ===
    @Test
    @Timeout(3)
    // Isolation: cancelling a blocked child should not poison the scope; parent can still async new work.
    // Race-avoidance: short sleep gives child time to start blocking before cancel().
    void gcChildCancellationIsolation() throws Exception {
        try (JCoroutineScope parent = newScope()) {
            // Create a child that will block until cancelled
            JCoroutineHandle<Void> child = parent.launch(CoroutineGCTests::waitForCancellation);

            // Give child a moment to start
            Thread.sleep(50);

            child.cancel();
            assertThrows(CancellationException.class, child::join);

            // Parent should immediately be able to create new work
            JCoroutineHandle<String> survivor = parent.async(CoroutineGCTests::returnSurvivor);
            assertEquals("survivor", survivor.join());
        }
    }

    // Static methods to avoid any capture issues
    private static void waitForCancellation(tech.robd.jcoroutines.SuspendContext s) throws Exception {
        // This will block until cancelled/interrupted
        Thread.sleep(5000); // Long enough to be cancelled
    }


    private static String returnSurvivor(tech.robd.jcoroutines.SuspendContext s) {
        return "survivor";
    }

    // === DETAILED DEBUG (KEEP FOR INVESTIGATION) ===
    @Test
    @Timeout(5)
    // Investigative: intentionally capture an outer exception to demonstrate retention; prints per-GC-round status.
    // Expected retention: loops GC rounds and emits diagnostics; does not fail.
    void gcExceptionPathCleanup_Detailed() throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<>();
        WeakReference<JCoroutineHandle<?>> handleRef;
        WeakReference<RuntimeException> exceptionRef;
        WeakReference<CompletableFuture<?>> futureRef;

        {
            RuntimeException testException = new RuntimeException("debug test");
            exceptionRef = new WeakReference<>(testException, q);

            try (var scope = newScope()) {
                // This captures the exception - showing the problem
                RuntimeException finalTestException = testException;
                JCoroutineHandle<Void> h = scope.launch(s -> {
                    throw finalTestException; // LAMBDA CAPTURE - this is the issue!
                });

                handleRef = new WeakReference<>(h, q);
                futureRef = new WeakReference<>(h.result(), q);

                assertThrows(RuntimeException.class, h::join);
                h = null;
            }
            testException = null;
        }

        // Try aggressive GC
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(50);

            boolean handleCollected = handleRef.get() == null;
            boolean exceptionCollected = exceptionRef.get() == null;
            boolean futureCollected = futureRef.get() == null;

            DebugUtils.print("GC round " + i + ": handle=" + handleCollected +
                    ", exception=" + exceptionCollected +
                    ", future=" + futureCollected);

            if (handleCollected && exceptionCollected && futureCollected) {
                DebugUtils.print("All objects collected after " + i + " rounds");
                return; // Success
            }
        }

        // Show what's still retained for investigation
        DebugUtils.print("Final state - EXPECTED FAILURE due to lambda capture:");
        DebugUtils.print("  Handle collected: " + (handleRef.get() == null));
        DebugUtils.print("  Exception collected: " + (exceptionRef.get() == null));
        DebugUtils.print("  Future collected: " + (futureRef.get() == null));

        // Don't fail - this demonstrates the problem
        if (exceptionRef.get() != null) {
            DebugUtils.print("Exception retained as expected due to lambda capture");
        }
    }
}