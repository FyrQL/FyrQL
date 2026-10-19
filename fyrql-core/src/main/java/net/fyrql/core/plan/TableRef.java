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

package net.fyrql.core.plan;

import java.util.Objects;

/**
 * The three-part name of a table: the source it was registered as, a schema
 * within the source ({@code main} for sources without schemas), and the
 * table.
 */
public record TableRef(String source, String schema, String table) {

    /** The schema of sources that have no schemas of their own. */
    public static final String MAIN = "main";

    public TableRef {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
    }

    /** Returns a reference in the schema {@code main}. */
    public static TableRef of(String source, String table) {
        return new TableRef(source, MAIN, table);
    }

    @Override
    public String toString() {
        return source + "." + schema + "." + table;
    }
}
