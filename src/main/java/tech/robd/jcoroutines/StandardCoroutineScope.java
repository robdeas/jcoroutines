/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/StandardCoroutineScope.java
 description: Simple coroutine scope backed by an internal JCoroutineScopeImpl.
              Wraps an executor and provides async/launch/runBlocking APIs.
 license: Apache-2.
 author: Rob Deas
 editable: yes
 structured: no
 [/File Info]
*/
package tech.robd.jcoroutines;
/*
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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tech.robd.jcoroutines.fn.JCoroutineHandle;
import tech.robd.jcoroutines.internal.JCoroutineScopeImpl;

import java.util.concurrent.Executor;

/**
 * A straightforward coroutine scope backed by a {@link JCoroutineScopeImpl}.
 *
 * <p>Owns an executor and provides async, launch, and runBlocking APIs.
 * Call {@link #close()} to cancel children and release resources.</p>
 */
public final class StandardCoroutineScope implements JCoroutineScope, AutoCloseable {

    private final JCoroutineScopeImpl delegate = new JCoroutineScopeImpl();

    /**
     * Launch an async operation and return a handle.
     */
    @Override
    public <T> JCoroutineHandle<T> async(SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("block == null");
        return delegate.async(block);
    }

    /**
     * Launch a fire-and-forget operation.
     */
    @Override
    public JCoroutineHandle<Void> launch(@NonNull SuspendRunnable block) {
        if (block == null) throw new IllegalArgumentException("block == null");
        return delegate.launch(block);
    }

    /**
     * Run a suspend block and block the caller until it completes.
     */
    @Override
    public <T extends @Nullable Object> T runBlocking(@NonNull SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("block == null");
        return delegate.runBlocking(block);
    }

    /**
     * Run a suspend block on a given executor and block the caller.
     */
    @Override
    public <T> T runBlocking(@NonNull Executor executor, @NonNull SuspendFunction<T> block) {
        if (block == null) throw new IllegalArgumentException("block == null");
        return delegate.runBlocking(executor, block);
    }

    /**
     * @return the executor backing this scope.
     */
    @Override
    public Executor executor() {
        return delegate.executor();
    }

    /**
     * Cancel all children and release resources.
     */
    @Override
    public void close() {
        delegate.close();
    }
}
