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

package net.fyrql.sql.bind;

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.Statement;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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
 * binding, as a {@code DATE} in the time zone of the session and a
 * {@code TIMESTAMP WITH TIME ZONE}. Every cast, function and comparison
 * that depends on the time zone computes in that of the session, see
 * {@link ZoneBinding}.</li>
 * <li>A parameter {@code ?} of a prepared query takes the type its context
 * gives a bare {@code NULL}, see {@link #prepare}.</li>
 * <li>A statement that writes binds to the plan of the rows it writes,
 * see {@link BoundStatement}; {@code INSERT} brings them to the columns of
 * the table.</li>
 * </ul>
 * Every error is a {@link SqlException} with the position of the part of the
 * query it concerns. A binder holds no state between queries.
 */
public final class Binder {

    private final CatalogView catalog;
    private final FunctionRegistry functions;
    private final Supplier<ZoneId> zone;
    private final Settings settings;

    /** A binder in the time zone of the JVM. */
    public Binder(CatalogView catalog, FunctionRegistry functions) {
        this(catalog, functions, ZoneId::systemDefault);
    }

    /** A binder in the time zone the supplier gives at each binding, the time zone of the session. */
    public Binder(CatalogView catalog, FunctionRegistry functions, Supplier<ZoneId> zone) {
        this(catalog, functions, zone, Settings.DEFAULT);
    }

    /**
     * A binder in the time zone the supplier gives at each binding, with
     * the other settings of the session, which {@code SHOW} and
     * {@code current_setting} read at each binding.
     */
    public Binder(CatalogView catalog, FunctionRegistry functions, Supplier<ZoneId> zone, Settings settings) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.functions = Objects.requireNonNull(functions, "functions");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Binds a query at the current time.
     *
     * @throws SqlException if the query does not bind
     */
    public PlanNode bind(Query query) {
        return bind(query, Instant.now(), zone.get());
    }

    /**
     * Binds a query with {@code current_date} and {@code now()} at a given
     * time and in a time zone.
     *
     * @throws SqlException if the query does not bind
     */
    public PlanNode bind(Query query, Instant now, ZoneId zone) {
        return ZoneBinding.plan(new QueryBinder(catalog, context(now, zone, ParameterTypes.none())).query(query),
                zone);
    }

    /**
     * A query with parameters, bound.
     *
     * @param plan the plan, whose schema is that of the result; it is not
     *        run, as its parameters have no values
     * @param parameterTypes the type of each parameter, from the first
     */
    public record Prepared(PlanNode plan, List<DataType> parameterTypes) {
        public Prepared {
            Objects.requireNonNull(plan, "plan");
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    /**
     * Binds a query with parameters at the current time, see
     * {@link #prepare(Query, List, Instant, ZoneId)}.
     *
     * @throws SqlException if the query does not bind, or a parameter would have two types
     */
    public Prepared prepare(Query query, List<SqlExpr.Parameter> parameters) {
        return prepare(query, parameters, Instant.now(), zone.get());
    }

    /**
     * Binds a query with parameters. A parameter takes the type that its
     * context gives a bare {@code NULL}: the type of the other side of a
     * comparison or of arithmetic, of the other values of {@code IN},
     * {@code BETWEEN}, {@code CASE} and {@code COALESCE}, {@code BOOLEAN}
     * as a condition, the type of {@code CAST(? AS type)}, the first
     * type that a function takes, trying {@code BIGINT}, {@code DOUBLE},
     * {@code INTEGER}, ... and {@code VARCHAR} last. From then on it is a
     * value of that type that is no constant. Once every parameter has its
     * type, the query is bound once more with the types from the start,
     * which gives the plan: so the query binds to the types it binds to
     * with any value. A parameter that only a bare {@code NULL} would stand
     * in for, such as {@code SELECT ?}, is a {@code VARCHAR}. A parameter
     * by itself in {@code LIMIT} or {@code OFFSET} is a {@code BIGINT}; in
     * another place that needs a constant it is not supported.
     *
     * @param parameters the parameters of the query, the one numbered
     *        {@code n} at index {@code n - 1}
     * @throws SqlException if the query does not bind, or a parameter would have two types
     */
    public Prepared prepare(Query query, List<SqlExpr.Parameter> parameters, Instant now, ZoneId zone) {
        ParameterTypes types = ParameterTypes.inferred();
        new QueryBinder(catalog, context(now, zone, types)).query(query);
        List<DataType> result = parameterTypes(types, parameters);
        PlanNode plan = new QueryBinder(catalog, context(now, zone, fixed(parameters, result))).query(query);
        return new Prepared(ZoneBinding.plan(plan, zone), result);
    }

    /**
     * Binds a statement at the current time: a query, or a statement that
     * writes.
     *
     * @throws SqlException if the statement does not bind
     */
    public BoundStatement bind(Statement statement) {
        return bind(statement, Instant.now(), zone.get());
    }

    /**
     * Binds a statement with {@code current_date} and {@code now()} at a
     * given time and in a time zone.
     *
     * @throws SqlException if the statement does not bind
     */
    public BoundStatement bind(Statement statement, Instant now, ZoneId zone) {
        return ZoneBinding.statement(new StatementBinder(catalog, context(now, zone, ParameterTypes.none()))
                .statement(statement), zone);
    }

    /**
     * A statement with parameters, bound.
     *
     * @param statement the statement, which is not run, as its parameters
     *        have no values
     * @param parameterTypes the type of each parameter, from the first
     */
    public record PreparedStatement(BoundStatement statement, List<DataType> parameterTypes) {
        public PreparedStatement {
            Objects.requireNonNull(statement, "statement");
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    /**
     * Binds a statement with parameters at the current time, see
     * {@link #prepare(Statement, List, Instant, ZoneId)}.
     *
     * @throws SqlException if the statement does not bind, or a parameter would have two types
     */
    public PreparedStatement prepare(Statement statement, List<SqlExpr.Parameter> parameters) {
        return prepare(statement, parameters, Instant.now(), zone.get());
    }

    /**
     * Binds a statement with parameters, as
     * {@link #prepare(Query, List, Instant, ZoneId)} binds a query. In
     * {@code VALUES} of an {@code INSERT}, a parameter takes the type of
     * the column its value goes to.
     *
     * @throws SqlException if the statement does not bind, or a parameter would have two types
     */
    public PreparedStatement prepare(Statement statement, List<SqlExpr.Parameter> parameters, Instant now,
            ZoneId zone) {
        ParameterTypes types = ParameterTypes.inferred();
        new StatementBinder(catalog, context(now, zone, types)).statement(statement);
        List<DataType> result = parameterTypes(types, parameters);
        BoundStatement bound = new StatementBinder(catalog, context(now, zone, fixed(parameters, result)))
                .statement(statement);
        return new PreparedStatement(ZoneBinding.statement(bound, zone), result);
    }

    /**
     * Returns the types the first binding found for the parameters, in their
     * order; a parameter without a type is a {@code VARCHAR}, as a parameter
     * of PostgreSQL without a type is a {@code text}.
     */
    private static List<DataType> parameterTypes(ParameterTypes types, List<SqlExpr.Parameter> parameters) {
        List<DataType> result = new ArrayList<>();
        for (SqlExpr.Parameter p : parameters) {
            DataType type = types.type(p.index());
            result.add(type != null ? type : DataType.VARCHAR);
        }
        return result;
    }

    /** Returns the types of the parameters for the second binding, by number. */
    private static ParameterTypes fixed(List<SqlExpr.Parameter> parameters, List<DataType> types) {
        Map<Integer, DataType> byIndex = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            byIndex.put(parameters.get(i).index(), types.get(i));
        }
        return ParameterTypes.fixed(byIndex);
    }

    private BindContext context(Instant now, ZoneId zone, ParameterTypes parameters) {
        return new BindContext(functions, new HashSet<>(functions.names()), now, zone, settings, parameters);
    }
}
