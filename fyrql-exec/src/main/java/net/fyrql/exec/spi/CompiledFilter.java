/*
 * Copyright 2026 the FyrQL authors
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

package net.fyrql.exec.spi;

import net.fyrql.core.vector.Batch;

/** A compiled condition: the counterpart of {@link net.fyrql.core.expr.Evaluator#select}. */
public interface CompiledFilter {

    /** Returns the rows of a batch, in order, for which the condition is {@code TRUE}. */
    int[] select(Batch in);

    /** Returns the source code the condition was compiled from. */
    String source();
}
