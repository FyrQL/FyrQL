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

import java.util.List;
import java.util.OptionalLong;

/** The schemas and tables of a source, with their columns and, where known, statistics. */
public interface Catalog {

    /** Returns the schemas; {@code main} for a source without schemas of its own. */
    List<String> schemas();

    /** Returns the tables of a schema. */
    List<String> tables(String schema);

    /**
     * Returns the columns of a table.
     *
     * @throws IllegalArgumentException if there is no such table
     */
    Schema schema(TableRef table);

    /** Returns the number of rows of a table, if the source knows it cheaply. */
    default OptionalLong rowCount(TableRef table) {
        return OptionalLong.empty();
    }
}
