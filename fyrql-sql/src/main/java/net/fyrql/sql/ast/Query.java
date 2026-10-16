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

/**
 * A query: the common tables of its {@code WITH}, a body, and the
 * {@code ORDER BY}, {@code LIMIT} and {@code OFFSET} that apply to the
 * result of the body. {@code limit} and {@code offset} are {@code null} when
 * absent; {@code LIMIT ALL} and {@code LIMIT NULL} are absent.
 */
public record Query(List<CommonTable> with, QueryBody body, List<OrderItem> orderBy, SqlExpr limit,
        SqlExpr offset) {

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

    /** {@code name [(columns)] AS (query)} in a {@code WITH}. */
    public record CommonTable(String name, List<String> columns, Query query, Position position) {
        public CommonTable {
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(position, "position");
        }
    }
}
