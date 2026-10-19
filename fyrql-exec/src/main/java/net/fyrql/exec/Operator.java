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

package net.fyrql.exec;

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

/**
 * A physical operator in the Volcano style over batches: {@link #next()}
 * pulls the next batch, {@code null} after the last one. An operator never
 * returns an empty batch.
 */
public interface Operator extends AutoCloseable {

    /** Returns the columns of the batches. */
    Schema schema();

    /** Returns the next batch, or {@code null} at the end. */
    Batch next();

    @Override
    void close();
}
