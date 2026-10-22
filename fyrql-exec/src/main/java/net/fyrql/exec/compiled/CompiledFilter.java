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

/** A compiled condition: the counterpart of {@link net.fyrql.core.expr.Evaluator#select}. */
public interface CompiledFilter {

    /** Returns the rows of a batch, in order, for which the condition is {@code TRUE}. */
    int[] select(Batch in);

    /** Returns the source code the condition was compiled from. */
    String source();
}
