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

/** The body of a {@link Query}: a {@code SELECT}, a set operation, {@code VALUES}, or a query in parentheses. */
public sealed interface QueryBody {

    /**
     * {@code SELECT [DISTINCT [ON (distinctOn)]] items [FROM from] [WHERE where] [GROUP BY ...]
     * [HAVING having] [WINDOW windows] [QUALIFY qualify]}; {@code from}, {@code where},
     * {@code groupBy}, {@code having} and {@code qualify} are {@code null} when absent,
     * {@code distinctOn} is empty, and with it {@code distinct} is false.
     */
    record Select(boolean distinct, List<SelectItem> items, FromItem from, SqlExpr where, GroupBy groupBy,
            SqlExpr having, List<NamedWindow> windows, SqlExpr qualify, List<SqlExpr> distinctOn, Position position)
            implements QueryBody {
        public Select {
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("SELECT needs at least one item");
            }
            windows = List.copyOf(windows);
            distinctOn = List.copyOf(distinctOn);
            if (distinct && !distinctOn.isEmpty()) {
                throw new IllegalArgumentException("DISTINCT ON is no DISTINCT");
            }
            Objects.requireNonNull(position, "position");
        }

        /** A select without {@code DISTINCT ON}. */
        public Select(boolean distinct, List<SelectItem> items, FromItem from, SqlExpr where, GroupBy groupBy,
                SqlExpr having, List<NamedWindow> windows, SqlExpr qualify, Position position) {
            this(distinct, items, from, where, groupBy, having, windows, qualify, List.of(), position);
        }

        /** A select without {@code WINDOW} and {@code QUALIFY}. */
        public Select(boolean distinct, List<SelectItem> items, FromItem from, SqlExpr where, GroupBy groupBy,
                SqlExpr having, Position position) {
            this(distinct, items, from, where, groupBy, having, List.of(), null, position);
        }
    }

    /** {@code name AS (window)} in the {@code WINDOW} clause; the window names no other. */
    record NamedWindow(String name, WindowSpec window, Position position) {
        public NamedWindow {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(window, "window");
            if (window.base() != null) {
                throw new IllegalArgumentException("a window of the WINDOW clause names no other");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * {@code GROUP BY expressions}, {@code GROUP BY ALL} with no expressions,
     * or grouping sets: each set the indexes of the expressions it groups by,
     * none for the set of all rows, as {@code GROUPING SETS}, {@code ROLLUP},
     * {@code CUBE} and {@code ()} give them; without sets, all expressions
     * form the one set.
     */
    record GroupBy(boolean all, List<SqlExpr> expressions, List<List<Integer>> sets) {
        public GroupBy {
            expressions = List.copyOf(expressions);
            sets = sets.stream().map(List::copyOf).toList();
            if (all ? !expressions.isEmpty() || !sets.isEmpty() : expressions.isEmpty() && sets.isEmpty()) {
                throw new IllegalArgumentException("GROUP BY needs either ALL, expressions or grouping sets");
            }
            for (List<Integer> set : sets) {
                for (int i : set) {
                    if (i < 0 || i >= expressions.size()) {
                        throw new IllegalArgumentException("No expression " + i + " to group by");
                    }
                }
            }
        }

        /** {@code GROUP BY expressions}, or {@code GROUP BY ALL} with no expressions. */
        public GroupBy(boolean all, List<SqlExpr> expressions) {
            this(all, expressions, List.of());
        }
    }

    /** The kinds of set operations. */
    enum SetKind {
        UNION, EXCEPT, INTERSECT
    }

    /**
     * {@code left UNION|EXCEPT|INTERSECT [ALL] right}. As in DuckDB,
     * {@code INTERSECT} binds more tightly than the other two, which
     * associate to the left.
     */
    record SetOperation(SetKind kind, boolean all, QueryBody left, QueryBody right) implements QueryBody {
        public SetOperation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** {@code VALUES (...), (...)}; every row has at least one value. */
    record Values(List<List<SqlExpr>> rows, Position position) implements QueryBody {
        public Values {
            rows = rows.stream().map(List::copyOf).toList();
            if (rows.isEmpty() || rows.stream().anyMatch(List::isEmpty)) {
                throw new IllegalArgumentException("VALUES needs rows of at least one value");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * A query in parentheses that has its own {@code WITH}, {@code ORDER BY},
     * {@code LIMIT} or {@code OFFSET}; parentheses around a body alone leave
     * no trace.
     */
    record Parenthesized(Query query) implements QueryBody {
        public Parenthesized {
            Objects.requireNonNull(query, "query");
        }
    }
}
