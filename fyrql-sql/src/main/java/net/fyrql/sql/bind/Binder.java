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

package net.fyrql.sql.bind;

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Query;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;

/**
 * Turns a parsed query into a logical plan: resolves the names of tables
 * through a {@link CatalogView} and of columns through the scopes of the
 * query, picks the scalar functions of a {@link FunctionRegistry}, types
 * the literals, and translates every clause into the nodes of
 * {@link PlanNode}. Name resolution, typing and the meaning of the forms
 * follow DuckDB 1.5.6, as measured:
 * <ul>
 * <li>Names of columns and tables match regardless of case. A name that is
 * no column may be the alias of an earlier item of the select list, or, in
 * {@code WHERE}, {@code GROUP BY} and {@code HAVING}, of any item; in
 * {@code ORDER BY} a bare alias comes before a column.</li>
 * <li>{@code GROUP BY} takes expressions, positions of the select list,
 * aliases, and {@code ALL}, which groups by every item without an aggregate
 * that names a column. {@code ORDER BY} takes the same, and {@code ALL} for
 * every output column.</li>
 * <li>A join condition becomes the keys of a hash join where it is an
 * equality between the two sides, and a filter otherwise; an outer join
 * filters the side it does not preserve by the conditions on that side and
 * keeps any other condition in the join.</li>
 * <li>A subquery in {@code WHERE}, {@code HAVING} or the select list is a
 * join with the rows of the clause, see {@link SubqueryJoiner}; elsewhere
 * it is an error. In its own {@code WHERE} clause, a subquery may reference
 * the query around it, see {@link Correlation}: a name reaches the columns
 * of the subquery first, and the conditions that read the query around it
 * become the keys and the condition of the join.</li>
 * <li>{@code WITH} is expanded at every use. {@code UNION} types a column
 * as {@code VARCHAR} if one side is text, otherwise as the common type.</li>
 * <li>{@code current_date} and {@code now()} stand for the time of the
 * binding, as a {@code DATE} and a {@code TIMESTAMP} without time zone.</li>
 * </ul>
 * Every error is a {@link SqlException} with the position of the part of the
 * query it concerns. A binder holds no state between queries.
 */
public final class Binder {

    private final CatalogView catalog;
    private final FunctionRegistry functions;

    public Binder(CatalogView catalog, FunctionRegistry functions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.functions = Objects.requireNonNull(functions, "functions");
    }

    /**
     * Binds a query at the current time.
     *
     * @throws SqlException if the query does not bind
     */
    public PlanNode bind(Query query) {
        return bind(query, LocalDateTime.now());
    }

    /**
     * Binds a query with {@code current_date} and {@code now()} at a given time.
     *
     * @throws SqlException if the query does not bind
     */
    public PlanNode bind(Query query, LocalDateTime now) {
        BindContext context = new BindContext(functions, new HashSet<>(functions.names()), now);
        return new QueryBinder(catalog, context).query(query);
    }
}
