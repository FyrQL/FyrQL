/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.exec.compiled;

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
