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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@code FROM} reads: a table, a file, a subquery, a table function,
 * or a join of two of them. A comma between items is a {@code CROSS} join;
 * explicit joins associate to the left. The alias is {@code null} when
 * absent.
 */
public sealed interface FromItem {

    /** Returns where the item starts in the query text. */
    Position position();

    /** {@code name [AS] alias [(columns)]}: a name for an item and, optionally, for its first columns. */
    record Alias(String name, List<String> columns) {
        public Alias {
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
        }
    }

    /** A table by a name of one to three parts. */
    record TableName(List<String> name, Alias alias, Position position) implements FromItem {
        public TableName {
            name = List.copyOf(name);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a table name needs a part");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /** A file by its path in a string, as in {@code FROM 'orders.csv'}. */
    record FileName(String path, Alias alias, Position position) implements FromItem {
        public FileName {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code (query) [AS alias]}, also {@code (VALUES ...) alias(columns)}. */
    record Subquery(Query query, Alias alias, Position position) implements FromItem {
        public Subquery {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code name(arguments, key = value, ...) [AS alias]}; the named arguments keep their order. */
    record TableFunctionCall(String name, List<SqlExpr> arguments, Map<String, SqlExpr> namedArguments, Alias alias,
            Position position) implements FromItem {
        public TableFunctionCall {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            namedArguments = Collections.unmodifiableMap(new LinkedHashMap<>(namedArguments));
            Objects.requireNonNull(position, "position");
        }
    }

    /** The kinds of joins. */
    enum JoinKind {
        INNER, LEFT, RIGHT, FULL, CROSS
    }

    /**
     * {@code left [NATURAL] kind JOIN right [ON on | USING (using)]}: a
     * {@code CROSS} join has neither a condition nor {@code USING}; the
     * others have exactly one of {@code ON}, {@code USING} and
     * {@code NATURAL}.
     */
    record Join(JoinKind kind, FromItem left, FromItem right, SqlExpr on, List<String> using, boolean natural,
            Position position) implements FromItem {
        public Join {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            using = List.copyOf(using);
            int conditions = (on != null ? 1 : 0) + (using.isEmpty() ? 0 : 1) + (natural ? 1 : 0);
            if (kind == JoinKind.CROSS ? conditions != 0 : conditions != 1) {
                throw new IllegalArgumentException(kind + " JOIN with " + conditions + " conditions");
            }
            Objects.requireNonNull(position, "position");
        }
    }
}
