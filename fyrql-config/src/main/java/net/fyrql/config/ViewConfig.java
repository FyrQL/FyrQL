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

package net.fyrql.config;

import java.util.List;
import java.util.Objects;

/**
 * A view of a configuration, which the engine creates as it is built, as
 * {@code CREATE VIEW name (columns) AS sql} does.
 *
 * @param name the name of the view, of one to three parts with dots
 *        between, each as in SQL, unquoted or in double quotes
 * @param columns the names of its columns, or none for the names its query gives them
 * @param sql its query
 * @param line the line of the file where the view starts, or {@code 0} if unknown
 */
public record ViewConfig(String name, List<String> columns, String sql, int line) {

    /**
     * @throws IllegalArgumentException if the name or the query is blank
     */
    public ViewConfig {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("A view needs a name");
        }
        if (Objects.requireNonNull(sql, "sql").isBlank()) {
            throw new IllegalArgumentException("The view " + name + " needs a query");
        }
        columns = List.copyOf(columns);
    }

    /** A view whose line is unknown. */
    public ViewConfig(String name, List<String> columns, String sql) {
        this(name, columns, sql, 0);
    }
}
