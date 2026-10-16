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

/**
 * A compiled {@link PipelineSpec}: the stages of the chain run row by row
 * in one loop, with the results of the operators of the interpreter. An
 * instance serves one operator at a time.
 */
public interface CompiledPipeline {

    /**
     * Runs the stages over a batch of the source and returns the rows the
     * last stage produces, or {@code null} if no row is left.
     *
     * @param in     a batch of the source, not empty
     * @param tables the build sides of the joins, in the order of {@link PipelineSpec#probes()}; empty
     *               without joins
     */
    Batch consume(Batch in, JoinTable[] tables);

    /** Returns the index of the stage whose code threw the last error of {@link #consume}. */
    int stage();

    /** Returns the source code the pipeline was compiled from. */
    String source();
}
