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

/**
 * Where the rows of a write go: a new or an existing table of a source, or
 * a file. The engine writes batches with {@link #write} and then either
 * completes the write with {@link #commit} or abandons it with
 * {@link #close} alone. As far as the source allows, nothing written is
 * visible before the commit: a file is written under a temporary name and
 * renamed, a database writes in one transaction, a table in memory
 * appears or grows at once. A sink is used by one thread.
 * <p>
 * Errors are unchecked exceptions; an {@link IllegalArgumentException}
 * carries a message for the user, such as a value that the target cannot
 * store.
 */
public interface Sink extends AutoCloseable {

    /**
     * Writes a batch. Its columns have the types of the schema the sink was
     * opened with; the names may differ.
     */
    void write(Batch batch);

    /**
     * Completes the write and makes it visible.
     *
     * @return the number of rows written
     */
    long commit();

    /**
     * Abandons the write if it was not committed: rolls back, deletes a
     * temporary file, forgets the rows. After a commit it only frees what
     * the sink holds.
     */
    @Override
    void close();
}
