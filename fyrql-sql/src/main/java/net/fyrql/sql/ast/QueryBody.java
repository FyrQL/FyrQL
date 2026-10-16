/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.sql.ast;

import java.util.List;
import java.util.Objects;

/** The body of a {@link Query}: a {@code SELECT}, a set operation, {@code VALUES}, or a query in parentheses. */
public sealed interface QueryBody {

    /**
     * {@code SELECT [DISTINCT] items [FROM from] [WHERE where] [GROUP BY ...] [HAVING having]};
     * {@code from}, {@code where}, {@code groupBy} and {@code having} are {@code null} when absent.
     */
    record Select(boolean distinct, List<SelectItem> items, FromItem from, SqlExpr where, GroupBy groupBy,
            SqlExpr having, Position position) implements QueryBody {
        public Select {
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("SELECT needs at least one item");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code GROUP BY expressions}, or {@code GROUP BY ALL} with no expressions. */
    record GroupBy(boolean all, List<SqlExpr> expressions) {
        public GroupBy {
            expressions = List.copyOf(expressions);
            if (all != expressions.isEmpty()) {
                throw new IllegalArgumentException("GROUP BY needs either ALL or expressions");
            }
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
