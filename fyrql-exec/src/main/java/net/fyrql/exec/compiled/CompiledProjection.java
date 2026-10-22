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
import net.fyrql.core.vector.Vector;

/** A compiled expression: the counterpart of {@link net.fyrql.core.expr.Evaluator#eval}. */
public interface CompiledProjection {

    /** Evaluates the expression for every row of a batch. */
    Vector evaluate(Batch in);

    /** Returns the source code the expression was compiled from. */
    String source();
}
