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
 * Changes to the rows of a table that a source makes by key, opened with
 * {@link Writer#change}: rows to update, to delete and to insert. The key
 * columns name the rows; a row whose key columns hold the given values is
 * changed, and if several rows hold them, all of them are. A {@code NULL}
 * in a key column matches a {@code NULL}. The engine passes every key at
 * most once to {@link #update} or {@link #delete}, and then either
 * completes the changes with {@link #commit} or abandons them with
 * {@link #close} alone; nothing is visible before the commit, as far as
 * the source allows. Changes are used by one thread, like a {@link Sink}.
 * <p>
 * Errors are unchecked exceptions; an {@link IllegalArgumentException}
 * carries a message for the user, such as a key that the table already
 * holds.
 */
public interface RowChanges extends AutoCloseable {

    /**
     * Updates rows. The columns of the batch are the key columns with the
     * values of the rows to update, then the updated columns with their new
     * values, in the order and with the types of the table.
     */
    void update(Batch batch);

    /**
     * Deletes rows. The columns of the batch are the key columns with the
     * values of the rows to delete.
     */
    void delete(Batch batch);

    /**
     * Inserts rows. The columns of the batch are all columns of the table,
     * in its order and with its types.
     */
    void insert(Batch batch);

    /**
     * Completes the changes and makes them visible.
     *
     * @return the number of rows updated, deleted and inserted
     */
    long commit();

    /**
     * Abandons the changes if they were not committed. After a commit it
     * only frees what the changes hold.
     */
    @Override
    void close();
}
