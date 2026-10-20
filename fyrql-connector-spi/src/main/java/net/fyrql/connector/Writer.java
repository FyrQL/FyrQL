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

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

/**
 * What a source offers to change its tables, through
 * {@link Connector#writer()}: create a table, append rows to one, drop one.
 * The engine checks the catalog first, so that {@link #create} is called
 * for a table that does not exist, in a schema that does, and
 * {@link #append} and {@link #drop} for a table that exists. After the
 * commit of a {@link #create} and after a {@link #drop}, the
 * {@link Connector#catalog() catalog} of the source shows the change.
 */
public interface Writer {

    /**
     * Opens a sink that creates a table with the given columns; the table
     * appears with the commit of the sink, even if no row was written.
     *
     * @throws IllegalArgumentException with a message for the user that
     *         names the column and the type, if the source cannot store a
     *         type
     */
    Sink create(TableRef table, Schema schema);

    /**
     * Opens a sink that appends rows to an existing table, in the columns
     * of its {@linkplain Catalog#schema(TableRef) schema}.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot write the table
     */
    Sink append(TableRef table);

    /**
     * Drops an existing table.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot drop the table
     */
    void drop(TableRef table);
}
