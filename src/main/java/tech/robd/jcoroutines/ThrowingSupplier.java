/*
[File Info]
path: src/main/java/tech/robd/jcoroutines/ThrowingSupplier.java
description: Simple supplier interface that allows checked exceptions.
license: Apache-2.0
editable: yes
structured: true
[/File Info]
*/
package tech.robd.jcoroutines;

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


/**
 * Simple supplier interface for JCoroutines.
 * Similar to {@link java.util.function.Supplier} but allows exceptions.
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}
