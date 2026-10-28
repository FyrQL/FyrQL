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
 * {@code UPDATE name [[AS] alias] SET column = value, ... [FROM from]
 * [WHERE where]}.
 *
 * @param name the name of the table, of one to three parts
 * @param alias the alias of the table, or {@code null}
 * @param assignments the columns to set with their values, in the order written
 * @param from the items of {@code FROM}, joined, or {@code null}
 * @param where the condition, or {@code null}
 * @param position the position of the name
 */
public record Update(List<String> name, String alias, List<Assignment> assignments, FromItem from, SqlExpr where,
        Position position) implements Statement {

    public Update {
        name = List.copyOf(name);
        assignments = List.copyOf(assignments);
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("UPDATE needs an assignment");
        }
        Objects.requireNonNull(position, "position");
    }

    /** {@code column = value} in {@code SET}. */
    public record Assignment(String column, SqlExpr value, Position position) {
        public Assignment {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(position, "position");
        }
    }
}
