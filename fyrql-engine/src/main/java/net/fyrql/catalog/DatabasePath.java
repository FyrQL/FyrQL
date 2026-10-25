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

package net.fyrql.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Names that resolve as in PostgreSQL: in one source, the database, along
 * a list of its schemas, with sources that are catalogs, such as
 * {@code pg_catalog} and {@code information_schema}, as schemas of every
 * database.
 * <ul>
 * <li>One part is a table in the first catalog, unless the schemas name
 * it, then in the schemas in their order, the first one with the table;
 * a schema that the database does not have is passed over.</li>
 * <li>Two parts are {@code catalog.table}, or else {@code schema.table}
 * in the database.</li>
 * <li>Three parts are {@code database.catalog.table}, or else
 * {@code source.schema.table} in any source.</li>
 * </ul>
 * A new table of one part goes to the first schema of the list that the
 * database has and that is no catalog.
 * <p>
 * Internal; not covered by the compatibility promise.
 *
 * @param database the source that is the database
 * @param schemas the schemas along which names of one part resolve, in
 *        their order; an entry may name a catalog
 * @param catalogs the sources that are catalogs, the first one searched
 *        before the schemas
 */
public record DatabasePath(String database, List<String> schemas, List<String> catalogs) {

    public DatabasePath {
        Objects.requireNonNull(database, "database");
        schemas = List.copyOf(schemas);
        catalogs = List.copyOf(catalogs);
    }
}
