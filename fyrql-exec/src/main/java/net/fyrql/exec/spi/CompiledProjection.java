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
import net.fyrql.core.vector.Vector;

/** A compiled expression: the counterpart of {@link net.fyrql.core.expr.Evaluator#eval}. */
public interface CompiledProjection {

    /** Evaluates the expression for every row of a batch. */
    Vector evaluate(Batch in);

    /** Returns the source code the expression was compiled from. */
    String source();
}
