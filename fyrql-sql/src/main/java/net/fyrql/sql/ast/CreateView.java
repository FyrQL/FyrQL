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

import net.fyrql.sql.Position;

import java.util.List;
import java.util.Objects;

/**
 * {@code CREATE [OR REPLACE] VIEW [IF NOT EXISTS] name [(columns)] AS query}.
 *
 * @param name the name of the view, of one to three parts
 * @param columns the names of the columns, or empty for the names the query gives them
 * @param query the query of the view
 * @param text the text of the query as written, without a closing semicolon
 * @param position the position of the name
 */
public record CreateView(List<String> name, List<String> columns, Query query, String text, boolean orReplace,
        boolean ifNotExists, Position position) implements Statement {

    public CreateView {
        name = List.copyOf(name);
        columns = List.copyOf(columns);
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(text, "text");
        if (orReplace && ifNotExists) {
            throw new IllegalArgumentException("OR REPLACE and IF NOT EXISTS exclude each other");
        }
        Objects.requireNonNull(position, "position");
    }
}
