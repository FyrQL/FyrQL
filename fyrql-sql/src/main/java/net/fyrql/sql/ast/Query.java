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
 * A query: the common tables of its {@code WITH}, a body, and the
 * {@code ORDER BY}, {@code LIMIT} and {@code OFFSET} that apply to the
 * result of the body. {@code limit} and {@code offset} are {@code null} when
 * absent; {@code LIMIT ALL} and {@code LIMIT NULL} are absent.
 */
public record Query(List<CommonTable> with, QueryBody body, List<OrderItem> orderBy, SqlExpr limit,
        SqlExpr offset) implements Statement {

    public Query {
        with = List.copyOf(with);
        Objects.requireNonNull(body, "body");
        orderBy = List.copyOf(orderBy);
    }

    /** A query of only a body. */
    public static Query of(QueryBody body) {
        return new Query(List.of(), body, List.of(), null, null);
    }

    /** Tells whether the query is its body alone, without {@code WITH}, {@code ORDER BY}, {@code LIMIT}, {@code OFFSET}. */
    public boolean isBodyOnly() {
        return with.isEmpty() && orderBy.isEmpty() && limit == null && offset == null;
    }

    /**
     * Whether a common table is to run once: as the engine decides, or as
     * {@code MATERIALIZED} or {@code NOT MATERIALIZED} says.
     */
    public enum Materialized {
        DEFAULT, ALWAYS, NEVER
    }

    /** {@code name [(columns)] AS [[NOT] MATERIALIZED] (query)} in a {@code WITH}. */
    public record CommonTable(String name, List<String> columns, Query query, Materialized materialized,
            Position position) {
        public CommonTable {
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(materialized, "materialized");
            Objects.requireNonNull(position, "position");
        }

        /** A common table without {@code MATERIALIZED} or {@code NOT MATERIALIZED}. */
        public CommonTable(String name, List<String> columns, Query query, Position position) {
            this(name, columns, query, Materialized.DEFAULT, position);
        }
    }
}
