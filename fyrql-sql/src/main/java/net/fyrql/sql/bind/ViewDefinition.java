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

package net.fyrql.sql.bind;

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.ast.Query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A view as {@code CREATE VIEW} bound it: its query, which a query that
 * reads the view binds again, and the tables its names resolved to then.
 * The names keep resolving to those tables, whatever the search path of a
 * later query, as the columns of a table keep their table in PostgreSQL; a
 * table that is gone by then is an error of that query.
 *
 * @param query the query of the view
 * @param text the text of the query as written
 * @param schema the columns of the view when it was created; a query that
 *        reads the view sees them under these names
 * @param names the tables that the names in the query resolved to, by the
 *        parts of each name as written
 * @param paths the parts that qualified the columns of each of these tables
 * @param postgresNames whether the items of a select list without an alias
 *        were named as PostgreSQL names them, see {@link CatalogView#postgresNames()}
 */
public record ViewDefinition(Query query, String text, Schema schema, Map<List<String>, TableRef> names,
        Map<TableRef, List<String>> paths, boolean postgresNames) {

    public ViewDefinition {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(schema, "schema");
        names = Collections.unmodifiableMap(new LinkedHashMap<>(names));
        paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
    }

    /**
     * Returns the catalog in which the query of the view binds again: its
     * names resolve to the tables they resolved to when it was created, and
     * everything else comes from a catalog of now.
     */
    CatalogView catalog(CatalogView now) {
        return new ViewCatalogs.Replay(this, now);
    }
}
