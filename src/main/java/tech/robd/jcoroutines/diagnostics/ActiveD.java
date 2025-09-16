/*
 [File Info]
 path: src/main/java/tech/robd/jcoroutines/diagnostics/ActiveD.java
 description: Package-private active diagnostics implementation that binds an owner Class.
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
 * Active diagnostics implementation that carries the owning {@link Class}.
 * Package-private by design; use {@link Diagnostics#of(Class)} or
 * {@link Diagnostics#dynamic(Class)} to obtain instances.
 */
record ActiveD(Class<?> owner) implements Diagnostics {
}
