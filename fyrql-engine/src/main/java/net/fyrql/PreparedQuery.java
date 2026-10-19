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

package net.fyrql;

import net.fyrql.catalog.SearchPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.List;
import java.util.Objects;

/**
 * A query with parameters {@code ?}, parsed and bound by
 * {@link FyrQL#prepare(String)}, which {@link FyrQL#execute(PreparedQuery, List)}
 * runs with values for its parameters.
 *
 * @param sql the text of the query
 * @param searchPath the search path the names of the query are looked up along
 * @param schema the columns of the result
 * @param parameterTypes the type of each parameter, from the first
 */
public record PreparedQuery(String sql, SearchPath searchPath, Schema schema, List<DataType> parameterTypes) {

    public PreparedQuery {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(searchPath, "searchPath");
        Objects.requireNonNull(schema, "schema");
        parameterTypes = List.copyOf(parameterTypes);
    }
}
