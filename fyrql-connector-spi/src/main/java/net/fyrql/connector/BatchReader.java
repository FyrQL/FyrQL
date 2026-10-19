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

package net.fyrql.connector;

import net.fyrql.core.vector.Batch;

/** Delivers the batches of one split, with the schema of its scan plan. */
public interface BatchReader extends AutoCloseable {

    /** Returns the next batch, or {@code null} after the last one. */
    Batch next();

    @Override
    default void close() {
    }

    /**
     * Asks the reader to stop, from another thread, while {@link #next()}
     * may be waiting on its source: a reader of a remote source cancels the
     * request, so that {@code next()} returns early, usually with an
     * exception. It is a request at best effort, which a reader may ignore,
     * as the default does; it must be safe to call at any time, also more
     * than once and after {@link #close()}, and it does not close the
     * reader.
     */
    default void cancel() {
    }
}
