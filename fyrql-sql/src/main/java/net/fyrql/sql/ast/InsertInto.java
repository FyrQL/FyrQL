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

package net.fyrql.sql.ast;

import java.util.List;
import java.util.Objects;

/**
 * {@code INSERT INTO name [(columns)] query}, where the query may be
 * {@code VALUES}.
 *
 * @param name the name of the table, of one to three parts
 * @param columns the columns the query writes, empty for all of them
 * @param query the rows
 * @param position the position of the name
 */
public record InsertInto(List<String> name, List<String> columns, Query query, Position position)
        implements Statement {

    public InsertInto {
        name = List.copyOf(name);
        columns = List.copyOf(columns);
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(position, "position");
    }
}
