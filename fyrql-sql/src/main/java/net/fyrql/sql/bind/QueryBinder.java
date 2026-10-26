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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.expr.Expressions.Side;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Interval;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.OrderItem;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.WindowSpec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Binds the queries of one statement; see {@link Binder}. */
final class QueryBinder {

    /**
     * A common table expression with the ones visible to its own query, its
     * id in the plan, and the plan of its query once a query reads it.
     */
    private static final class Cte {
        final Query.CommonTable table;
        final Map<String, Cte> environment;
        final int id;
        PlanNode definition;

        Cte(Query.CommonTable table, Map<String, Cte> environment, int id) {
            this.table = table;
            this.environment = environment;
            this.id = id;
        }
    }

    /** The plan of a {@code FROM} clause with its columns. */
    private record Relation(PlanNode plan, List<Scope.Column> columns) {
        Scope scope() {
            return new Scope(columns, true);
        }
    }

    /**
     * An item of the select list after the stars are expanded: an
     * expression, or a column of a star.
     */
    private record Item(SqlExpr expression, Expression column, String name, String alias, Position position) {
    }

    /** A key of {@code ORDER BY}: an output column, or an expression over the input of the output. */
    private record Order(int output, Expression expression, boolean ascending, boolean nullsFirst) {
    }

    /** A condition of the {@code WHERE} clause of a subquery that references the query around it. */
    private record Conjunct(SqlExpr syntax, Expression bound) {
    }

    /**
     * The conditions of a subquery that reference the query around it: the
     * equalities of an expression over the subquery with one over the
     * placeholders of the query around it, which become keys of the join,
     * and the others.
     */
    private record Correlated(List<Expression> inner, List<Expression> outer, List<Conjunct> conditions) {
        static Correlated of(List<Conjunct> conjuncts) {
            List<Expression> inner = new ArrayList<>();
            List<Expression> outer = new ArrayList<>();
            List<Conjunct> conditions = new ArrayList<>();
            for (Conjunct c : conjuncts) {
                if (c.bound() instanceof Expression.Comparison cmp && cmp.op() == Expression.ComparisonOp.EQ) {
                    Side l = side(cmp.left(), Correlation.PLACEHOLDER);
                    Side r = side(cmp.right(), Correlation.PLACEHOLDER);
                    if (l == Side.LEFT && r == Side.RIGHT || l == Side.RIGHT && r == Side.LEFT) {
                        Expression[] operands = sameType(new Expression[] {cmp.left(), cmp.right()});
                        inner.add(l == Side.LEFT ? operands[0] : operands[1]);
                        outer.add(l == Side.LEFT ? operands[1] : operands[0]);
                        continue;
                    }
                }
                conditions.add(c);
            }
            return new Correlated(inner, outer, conditions);
        }

        /** The conditions besides the keys, or {@code null} for none. */
        Expression condition() {
            return conditions.isEmpty() ? null
                    : Expressions.conjunction(conditions.stream().map(Conjunct::bound).toList());
        }
    }

    /** The catalog of the statement. */
    private final CatalogView statementCatalog;
    /** The catalog in which names resolve: that of the statement, or that of the view being bound. */
    private CatalogView catalog;
    private final BindContext context;
    /** The bare NULLs of the select lists, by the plan of each select. */
    private final Map<PlanNode, BitSet> untypedNulls = new IdentityHashMap<>();
    /** The ids given to the common tables of the statement so far. */
    private int commonTables;
    /** The views whose queries are being bound, inside one another. */
    private final Set<TableRef> viewsInUse = new HashSet<>();

    QueryBinder(CatalogView catalog, BindContext context) {
        this.statementCatalog = catalog;
        this.catalog = catalog;
        this.context = context;
    }

    // ---------------------------------------------------------------- queries

    PlanNode query(Query q, Map<String, Cte> ctes) {
        return query(q, ctes, null);
    }

    /**
     * Binds a query; a subquery whose body is a {@code SELECT} may reference
     * the query around it in its {@code WHERE} clause.
     *
     * @param correlation the references to the query around a subquery, or {@code null}
     */
    private PlanNode query(Query q, Map<String, Cte> ctes, Correlation correlation) {
        Map<String, Cte> environment = ctes;
        Set<String> names = new HashSet<>();
        List<Cte> defined = new ArrayList<>();
        for (Query.CommonTable ct : q.with()) {
            if (!names.add(SqlTrees.key(ct.name()))) {
                throw new SqlException("Duplicate CTE name \"" + ct.name() + "\"", ct.position());
            }
            Cte cte = new Cte(ct, environment, ++commonTables);
            defined.add(cte);
            Map<String, Cte> next = new HashMap<>(environment);
            next.put(SqlTrees.key(ct.name()), cte);
            environment = next;
        }
        PlanNode plan;
        if (q.body() instanceof QueryBody.Select s) {
            plan = select(s, q.orderBy(), q.limit(), q.offset(), environment, correlation);
        } else {
            plan = body(q.body(), environment);
            plan = orderByOutputs(plan, q.orderBy());
            plan = limit(plan, q.limit(), q.offset());
        }
        return commonTables(plan, defined, correlation);
    }

    /**
     * Decides for each common table of a query that the plan reads whether
     * it runs once: then a {@link PlanNode.CommonTable} above the plan keeps
     * its rows for the scans. Otherwise the plan of its query takes the
     * place of each scan of it, bound anew for each but the first. A common
     * table runs once with {@code MATERIALIZED}, never with
     * {@code NOT MATERIALIZED}, and else if the plan reads it more than once
     * and it computes something, see {@link #computes}; a common table of a
     * subquery in an expression never runs once. The common tables are
     * decided from the last to the first, since a later one may read an
     * earlier one, whose {@link PlanNode.CommonTable} is then around the
     * one of the later.
     *
     * @param defined the common tables of the query, in their order
     */
    private PlanNode commonTables(PlanNode plan, List<Cte> defined, Correlation correlation) {
        PlanNode result = plan;
        for (int i = defined.size() - 1; i >= 0; i--) {
            Cte cte = defined.get(i);
            if (cte.definition == null) {
                continue;
            }
            int reads = 0;
            for (PlanNode n : Plans.nodes(result)) {
                if (n instanceof PlanNode.CommonTableScan s && s.id() == cte.id) {
                    reads++;
                }
            }
            boolean once = correlation == null && switch (cte.table.materialized()) {
                case ALWAYS -> true;
                case NEVER -> false;
                case DEFAULT -> reads > 1 && computes(cte.definition);
            };
            result = once ? new PlanNode.CommonTable(cte.id, cte.table.name(), cte.definition, result)
                    : inline(result, cte, new boolean[1]);
        }
        return result;
    }

    /**
     * Tells whether a plan computes something that its scans and filters do
     * not: an aggregation, a join, a window, a sort or a set operation.
     * Every scan of a common table that only reads and filters keeps its
     * filters in the source.
     */
    private static boolean computes(PlanNode plan) {
        for (PlanNode n : Plans.nodes(plan)) {
            if (n instanceof PlanNode.Aggregate || n instanceof PlanNode.Join || n instanceof PlanNode.Window
                    || n instanceof PlanNode.Sort || n instanceof PlanNode.SetOperation
                    || n instanceof PlanNode.Union) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts the plan of the query of a common table in place of each scan of
     * it: the plan bound at its first read for the first scan, a plan bound
     * anew for any other. The rebuilt nodes keep their bare NULLs.
     *
     * @param used whether the plan bound at the first read is in place already
     */
    private PlanNode inline(PlanNode node, Cte cte, boolean[] used) {
        if (node instanceof PlanNode.CommonTableScan s && s.id() == cte.id) {
            PlanNode plan = used[0] ? definition(cte) : cte.definition;
            used[0] = true;
            return plan;
        }
        List<PlanNode> children = new ArrayList<>();
        boolean changed = false;
        for (PlanNode child : node.children()) {
            PlanNode c = inline(child, cte, used);
            changed |= c != child;
            children.add(c);
        }
        if (!changed) {
            return node;
        }
        PlanNode rebuilt = Plans.withChildren(node, children);
        BitSet nulls = untypedNulls.get(node);
        if (nulls != null) {
            untypedNulls.put(rebuilt, nulls);
        }
        return rebuilt;
    }

    /** Binds the query of a common table, with the names of its column list. */
    private PlanNode definition(Cte cte) {
        PlanNode plan = query(cte.table.query(), cte.environment);
        return rename(plan, cte.table.columns(), cte.table.name(), cte.table.position());
    }

    PlanNode query(Query q) {
        return query(q, Map.of());
    }

    /**
     * The rows of an {@code INSERT}, bound.
     *
     * @param untypedNulls the columns that are a bare {@code NULL} of a
     *        select list, whose type the column they go to decides
     */
    record Rows(PlanNode plan, BitSet untypedNulls) {
    }

    /**
     * Binds the query of an {@code INSERT}. If it is {@code VALUES} alone,
     * with one value for each column it goes to, a bare {@code NULL} or a
     * parameter takes the type of its column.
     *
     * @param targets the types of the columns the values go to
     */
    Rows insertRows(Query q, List<DataType> targets) {
        PlanNode plan = q.isBodyOnly() && q.body() instanceof QueryBody.Values v
                && v.rows().get(0).size() == targets.size() ? values(v, targets) : query(q);
        return new Rows(plan, untypedNulls(plan));
    }

    private PlanNode body(QueryBody body, Map<String, Cte> ctes) {
        return switch (body) {
            case QueryBody.Select s -> select(s, List.of(), null, null, ctes, null);
            case QueryBody.SetOperation s -> setOperation(s, ctes);
            case QueryBody.Values v -> values(v, null);
            case QueryBody.Parenthesized p -> query(p.query(), ctes);
        };
    }

    /** {@code ORDER BY} of a query whose body is no {@code SELECT}: by output names and positions only. */
    private static PlanNode orderByOutputs(PlanNode plan, List<OrderItem> items) {
        if (items.isEmpty()) {
            return plan;
        }
        Schema schema = plan.schema();
        List<SortKey> keys = new ArrayList<>();
        for (OrderItem item : items) {
            if (item.isAll()) {
                for (int i = 0; i < schema.size(); i++) {
                    keys.add(new SortKey(i, item.ascending(), item.nullsFirst()));
                }
                continue;
            }
            SqlExpr e = item.expression();
            checkOrderTerm(e, schema.size());
            int column = -1;
            long position = SqlTrees.signedPosition(e);
            if (position >= 0) {
                column = outputPosition(position, schema.size(), e.position());
            } else if (e instanceof SqlExpr.Identifier id) {
                for (int i = 0; i < schema.size() && column < 0; i++) {
                    if (schema.name(i).equalsIgnoreCase(id.name())) {
                        column = i;
                    }
                }
            }
            if (column < 0) {
                throw new SqlException("Could not ORDER BY column \"" + SqlPrinter.print(e)
                        + "\": add the expression/function to every SELECT, or move the UNION into a FROM clause.",
                        e.position());
            }
            keys.add(new SortKey(column, item.ascending(), item.nullsFirst()));
        }
        return new PlanNode.Sort(plan, keys);
    }

    /**
     * Rejects what DuckDB rejects as a term of {@code ORDER BY}: a literal
     * that is no integer, which would sort by a constant, and a negative
     * integer, which is no position.
     */
    private static void checkOrderTerm(SqlExpr e, int size) {
        checkOrderLiteral(e);
        long position = SqlTrees.signedPosition(e);
        if (position != Long.MIN_VALUE && position < 0) {
            throw new SqlException("ORDER term out of range - should be between 1 and " + size, e.position());
        }
    }

    /**
     * Rejects a string, a {@code NULL} or a number that is no integer, also
     * with a minus, in {@code ORDER BY}, as DuckDB does, also inside an
     * aggregate; booleans, dates and casts pass. A parameter is rejected too.
     */
    static void checkOrderLiteral(SqlExpr e) {
        SqlExpr x = e;
        while (x instanceof SqlExpr.Unary u && u.op() == SqlExpr.UnaryOp.MINUS) {
            x = u.operand();
        }
        if (x instanceof SqlExpr.Parameter p) {
            // Neither a position nor an expression to sort by.
            throw new SqlException("Not supported: a parameter as a term of ORDER BY", p.position());
        }
        if (x instanceof SqlExpr.Literal l && (l.kind() == SqlExpr.LiteralKind.STRING
                || l.kind() == SqlExpr.LiteralKind.NULL && x == e
                || l.kind() == SqlExpr.LiteralKind.NUMBER && SqlTrees.position(l) < 0)) {
            throw new SqlException("ORDER BY non-integer literal has no effect", e.position());
        }
    }

    private static int outputPosition(long position, int size, Position where) {
        if (position < 1 || position > size) {
            throw new SqlException("ORDER term out of range - should be between 1 and " + size, where);
        }
        return (int) position - 1;
    }

    /**
     * Binds {@code LIMIT} and {@code OFFSET}, which are constants. A
     * parameter by itself is a {@code BIGINT}; the plan of a prepared query,
     * which is not run, has no bound for it, and with a value the query is
     * bound anew with a constant in its place.
     */
    private PlanNode limit(PlanNode plan, SqlExpr limit, SqlExpr offset) {
        boolean parameter = false;
        for (SqlExpr e : new SqlExpr[] {limit, offset}) {
            if (e instanceof SqlExpr.Parameter p) {
                context.parameters().check(p);
                context.parameters().typed(p, DataType.BIGINT);
                parameter = true;
            } else if (e != null) {
                SqlTrees.noParameter(e, "in LIMIT or OFFSET");
            }
        }
        if (parameter) {
            return plan;
        }
        long count = limit == null ? Long.MAX_VALUE : constantCount(limit, Long.MAX_VALUE);
        long skip = offset == null ? 0 : constantCount(offset, 0);
        return count == Long.MAX_VALUE && skip == 0 ? plan : new PlanNode.Limit(plan, count, skip);
    }

    /**
     * The separator of {@code string_agg}: a constant expression of type
     * {@code VARCHAR}, evaluated now; {@code null} for {@code NULL}.
     */
    private String separator(SqlExpr e, DataType argument) {
        SqlTrees.noParameter(e, "as the separator of string_agg");
        ExpressionBinder.Bound separator;
        try {
            separator = new ExpressionBinder(context, Scope.none(), Map.of(),
                    "Separator argument to StringAgg must be a constant", null).bound(e);
        } catch (SqlException x) {
            throw new SqlException("Separator argument to StringAgg must be a constant", e.position());
        }
        if (separator.isNull()) {
            return null;
        }
        if (separator.type() != DataType.VARCHAR) {
            throw new SqlException("No function matches string_agg(" + argument + ", " + separator.type() + ")",
                    e.position());
        }
        return (String) Literals.evaluate(separator.expression(), e.position());
    }

    /** Evaluates the constant of {@code LIMIT} or {@code OFFSET}; {@code NULL} means no bound. */
    private long constantCount(SqlExpr e, long ifNull) {
        Expression x = new ExpressionBinder(context, Scope.none(), Map.of(),
                "LIMIT and OFFSET cannot contain aggregates", null).bind(e);
        if (x.type() != DataType.BIGINT) {
            if (!Casts.allowed(x.type(), DataType.BIGINT)) {
                throw new SqlException("LIMIT and OFFSET must be numbers, not " + x.type(), e.position());
            }
            x = new Expression.Cast(x, DataType.BIGINT);
        }
        Object value = Literals.evaluate(x, e.position());
        if (value == null) {
            return ifNull;
        }
        long n = (Long) value;
        if (n < 0) {
            throw new SqlException("LIMIT/OFFSET cannot be negative", e.position());
        }
        return n;
    }

    // ------------------------------------------------------- set operations

    private PlanNode setOperation(QueryBody.SetOperation s, Map<String, Cte> ctes) {
        PlanNode left = body(s.left(), ctes);
        PlanNode right = body(s.right(), ctes);
        Schema l = left.schema();
        Schema r = right.schema();
        Position position = position(s);
        if (l.size() != r.size()) {
            throw new SqlException(
                    "Set operations can only apply to expressions with the same number of result columns", position);
        }
        List<DataType> types = new ArrayList<>();
        BitSet leftNulls = untypedNulls(left);
        BitSet rightNulls = untypedNulls(right);
        for (int i = 0; i < l.size(); i++) {
            DataType lt = l.type(i);
            DataType rt = r.type(i);
            DataType t;
            if (leftNulls.get(i)) {
                t = rt;
            } else if (rightNulls.get(i)) {
                t = lt;
            } else if (lt.equals(rt)) {
                t = lt;
            } else if ((lt == DataType.VARCHAR || rt == DataType.VARCHAR) && !lt.isNested() && !rt.isNested()) {
                // DuckDB unites anything with text as text.
                t = DataType.VARCHAR;
            } else if (lt == DataType.BOOLEAN && rt.isNumeric() || rt == DataType.BOOLEAN && lt.isNumeric()) {
                // And a boolean with a number as the number.
                t = lt == DataType.BOOLEAN ? rt : lt;
            } else {
                t = ExpressionBinder.check(() -> TypeRules.common(lt, rt), position,
                        "Cannot combine types " + lt + " and " + rt + " in a set operation");
            }
            types.add(t);
        }
        left = castColumns(left, types, leftNulls);
        right = castColumns(right, types, rightNulls);
        return switch (s.kind()) {
            case UNION -> {
                List<PlanNode> inputs = new ArrayList<>();
                if (s.all() && left instanceof PlanNode.Union u) {
                    inputs.addAll(u.inputs());
                } else {
                    inputs.add(left);
                }
                inputs.add(right);
                PlanNode union = new PlanNode.Union(inputs);
                yield s.all() ? union : distinct(union);
            }
            case EXCEPT -> new PlanNode.SetOperation(left, right, PlanNode.SetOperationKind.EXCEPT, s.all());
            case INTERSECT -> new PlanNode.SetOperation(left, right, PlanNode.SetOperationKind.INTERSECT, s.all());
        };
    }

    private static Position position(QueryBody body) {
        return switch (body) {
            case QueryBody.Select s -> s.position();
            case QueryBody.Values v -> v.position();
            case QueryBody.SetOperation s -> position(s.left());
            case QueryBody.Parenthesized p -> position(p.query().body());
        };
    }

    /** Casts the columns of a plan to the given types, keeping their names; a bare NULL becomes a NULL of the type. */
    private static PlanNode castColumns(PlanNode plan, List<DataType> types, BitSet nulls) {
        Schema schema = plan.schema();
        if (schema.types().equals(types)) {
            return plan;
        }
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            Expression c = new Expression.Column(i, schema.type(i));
            DataType t = types.get(i);
            if (!t.equals(schema.type(i))) {
                c = nulls.get(i) ? new Expression.Literal(t, null) : new Expression.Cast(c, t);
            }
            columns.add(new NamedExpression(schema.name(i), c));
        }
        return new PlanNode.Project(plan, columns);
    }

    private static PlanNode distinct(PlanNode plan) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < plan.schema().size(); i++) {
            all.add(i);
        }
        return new PlanNode.Aggregate(plan, all, List.of());
    }

    // ---------------------------------------------------------------- VALUES

    /**
     * Binds {@code VALUES}.
     *
     * @param targets the types a bare {@code NULL} or a parameter takes in
     *        each column, or {@code null} to let the other values decide
     */
    private PlanNode values(QueryBody.Values v, List<DataType> targets) {
        int width = v.rows().get(0).size();
        for (List<SqlExpr> row : v.rows()) {
            if (row.size() != width) {
                throw new SqlException("VALUES lists must all be the same length", v.position());
            }
        }
        ExpressionBinder binder = new ExpressionBinder(context, Scope.none(), Map.of(),
                "VALUES cannot contain aggregates", null);
        List<List<ExpressionBinder.Bound>> columns = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            List<ExpressionBinder.Bound> column = new ArrayList<>();
            for (List<SqlExpr> row : v.rows()) {
                ExpressionBinder.Bound b = binder.bound(row.get(c));
                column.add(targets != null && b.isNull() ? binder.typed(b, targets.get(c)) : b);
            }
            columns.add(binder.unifyRows(column, v.position()));
        }
        List<Schema.Field> fields = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < v.rows().size(); r++) {
            rows.add(new Object[width]);
        }
        // A column of bare NULLs keeps its type open, as in DuckDB.
        BitSet untyped = new BitSet();
        for (int c = 0; c < width; c++) {
            untyped.set(c, columns.get(c).stream().allMatch(ExpressionBinder.Bound::isNull));
        }
        for (int c = 0; c < width; c++) {
            List<DataType> types = columns.get(c).stream().map(ExpressionBinder.Bound::type).toList();
            DataType type = ExpressionBinder.check(() -> TypeRules.common(types), v.position(),
                    "Cannot combine the types " + types + " of a column of VALUES");
            fields.add(new Schema.Field("col" + c, type));
            for (int r = 0; r < rows.size(); r++) {
                Expression e = columns.get(c).get(r).expression();
                rows.get(r)[c] = Literals.evaluate(e.type().equals(type) ? e
                        : ZoneBinding.expression(new Expression.Cast(e, type), context.zone()),
                        v.rows().get(r).get(c).position());
            }
        }
        PlanNode.Values values = new PlanNode.Values(new Schema(fields), rows);
        untypedNulls.put(values, untyped);
        return values;
    }

    // ------------------------------------------------------------------ FROM

    private Relation from(FromItem item, Map<String, Cte> ctes) {
        return switch (item) {
            case FromItem.TableName t -> tableName(t, ctes);
            case FromItem.FileName f -> {
                String function = CatalogView.fileFunction(f.path()).orElseThrow(() -> new SqlException(
                        "No function to read the file \"" + f.path() + "\"; its extension names no known format",
                        f.position()));
                TableRef table = tableFunction(function, List.of(f.path()), Map.of(), f.position());
                yield named(scan(table), f.alias(), fileAlias(f.path()), false, f.position());
            }
            case FromItem.TableFunctionCall f when unnests(f) -> unnest(new Relation(oneRow(), List.of()),
                    unnestList(f, Scope.none()), f);
            case FromItem.TableFunctionCall f -> {
                ExpressionBinder binder = new ExpressionBinder(context, Scope.none(), Map.of(),
                        "The arguments of a table function cannot contain aggregates", null);
                List<Object> arguments = new ArrayList<>();
                for (SqlExpr a : f.arguments()) {
                    SqlTrees.noParameter(a, "as an argument of a table function");
                    arguments.add(Literals.evaluate(binder.bind(a), a.position()));
                }
                Map<String, Object> named = new LinkedHashMap<>();
                for (Map.Entry<String, SqlExpr> a : f.namedArguments().entrySet()) {
                    SqlTrees.noParameter(a.getValue(), "as an argument of a table function");
                    Expression value = binder.bind(a.getValue());
                    named.put(SqlTrees.key(a.getKey()), namedArgument(value.type(),
                            Literals.evaluate(value, a.getValue().position())));
                }
                TableRef table = tableFunction(SqlTrees.key(f.name()), arguments, named, f.position());
                yield named(scan(table), f.alias(), SqlTrees.key(f.name()), false, f.position());
            }
            case FromItem.Subquery s -> named(query(s.query(), ctes), s.alias(), "unnamed_subquery", true,
                    s.position());
            case FromItem.Join j -> join(j, ctes);
        };
    }

    /**
     * Returns the value of a named argument of a table function as the
     * connector gets it: a struct as a map from the names of its fields to
     * their values, in the order of the fields, such as the types of
     * {@code hive_types={'year': 'INTEGER'}}; any other value as it is.
     */
    private static Object namedArgument(DataType type, Object value) {
        if (!(type instanceof DataType.Struct struct) || !(value instanceof List<?> fields)) {
            return value;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            map.put(struct.fields().get(i).name(), fields.get(i));
        }
        return map;
    }

    /** The one row without columns that a {@code SELECT} without {@code FROM} reads. */
    private static PlanNode oneRow() {
        return new PlanNode.Values(new Schema(List.of()), List.<Object[]>of(new Object[0]));
    }

    /**
     * Tells whether an item of {@code FROM} unnests a list: {@code unnest},
     * and {@code range} and {@code generate_series}, which unnest the list
     * their scalar functions give.
     */
    private static boolean unnests(FromItem item) {
        return item instanceof FromItem.TableFunctionCall f && (SqlTrees.key(f.name()).equals("unnest") || series(f));
    }

    private static boolean series(FromItem.TableFunctionCall f) {
        String name = SqlTrees.key(f.name());
        return name.equals("range") || name.equals("generate_series");
    }

    /** The list of {@code unnest} or of a series in {@code FROM}, bound over the columns it may read. */
    private Expression unnestList(FromItem.TableFunctionCall f, Scope scope) {
        if (!f.namedArguments().isEmpty()) {
            String name = series(f) ? SqlTrees.key(f.name()) : "UNNEST";
            throw new SqlException("Not supported: named arguments of " + name, f.position());
        }
        if (series(f)) {
            ExpressionBinder binder = new ExpressionBinder(context, scope, Map.of(),
                    "The arguments of a table function cannot contain aggregates", null);
            Expression list = binder.bind(new SqlExpr.FunctionCall(f.name(), f.arguments(), f.position()));
            // As a table, a series takes no step of 0, which as a list gives no values.
            Expression step = f.arguments().size() == 3 ? binder.bind(f.arguments().get(2)) : null;
            if (step != null && Expressions.isConstant(step)) {
                Object value = Literals.evaluate(step, f.position());
                if (value instanceof Number n && n.longValue() == 0 && !(value instanceof Double)
                        || value instanceof Interval i && i.equals(Interval.ZERO)) {
                    throw new SqlException("interval cannot be 0!", f.position());
                }
            }
            return list;
        }
        if (f.arguments().size() != 1) {
            throw new SqlException("Wrong number of arguments for the function unnest", f.position());
        }
        ExpressionBinder.Bound list = new ExpressionBinder(context, scope, Map.of(),
                "UNNEST cannot contain aggregates", null).bound(f.arguments().get(0));
        if (list.isNull() || !(list.type() instanceof DataType.ListType)) {
            throw new SqlException("UNNEST requires a single list as input", f.position());
        }
        return list.expression();
    }

    /**
     * {@code unnest} in {@code FROM}: the rows of the input, each once per
     * element of its list, with the elements as one more column. The column
     * is called by the function, {@code unnest}, {@code range} or
     * {@code generate_series}, or by the first name of the alias; DuckDB
     * ignores any further names, and so does FyrQL.
     */
    private static Relation unnest(Relation input, Expression list, FromItem.TableFunctionCall f) {
        FromItem.Alias alias = f.alias();
        String function = SqlTrees.key(f.name());
        String name = alias != null && !alias.columns().isEmpty() ? alias.columns().get(0) : function;
        PlanNode.Unnest plan = new PlanNode.Unnest(input.plan(), list, name);
        List<Scope.Column> columns = new ArrayList<>(input.columns());
        columns.add(new Scope.Column(new Scope.Table(alias != null ? alias.name() : function, List.of()), name,
                new Expression.Column(input.plan().schema().size(), plan.elementType()), false));
        return new Relation(plan, columns);
    }

    /**
     * A join with {@code unnest} of a list of the left input, a lateral
     * reference: each left row once per element of its list. DuckDB also
     * keeps the rows without elements in a {@code LEFT} join, which FyrQL
     * does not take yet.
     */
    private Relation lateralUnnest(FromItem.Join j, Relation left, Expression list) {
        FromItem.TableFunctionCall f = (FromItem.TableFunctionCall) j.right();
        switch (j.kind()) {
            case RIGHT, FULL -> throw new SqlException(
                    "The combining JOIN type must be INNER or LEFT for a LATERAL reference", j.position());
            case LEFT -> throw new SqlException("Not supported: a LEFT JOIN with a lateral UNNEST", j.position());
            default -> {
                if (j.natural() || !j.using().isEmpty()) {
                    throw new SqlException("Not supported: NATURAL or USING with a lateral UNNEST", j.position());
                }
            }
        }
        Relation result = unnest(left, list, f);
        if (j.on() == null) {
            return result;
        }
        Expression on = new ExpressionBinder(context, result.scope(), Map.of(), "JOIN clause cannot contain aggregates!",
                null).condition(j.on());
        return new Relation(new PlanNode.Filter(result.plan(), on), result.columns());
    }

    private Relation tableName(FromItem.TableName t, Map<String, Cte> ctes) {
        if (t.name().size() == 1) {
            Cte cte = ctes.get(SqlTrees.key(t.name().get(0)));
            if (cte != null) {
                // Bound once; the query decides at its end whether its scans read the rows or bind it each.
                if (cte.definition == null) {
                    cte.definition = definition(cte);
                }
                PlanNode plan = PlanNode.CommonTableScan.all(cte.id, cte.table.name(), cte.definition);
                return named(plan, t.alias(), cte.table.name(), true, t.position());
            }
        }
        TableRef table;
        try {
            table = catalog.resolve(t.name());
        } catch (SqlException e) {
            throw new SqlException(e.reason(), t.position(), e.getCause());
        }
        Optional<ViewDefinition> view = catalog.view(table);
        PlanNode plan = view.isPresent() ? view(table, view.get(), t.position()) : scan(table);
        List<String> path = t.alias() == null ? catalog.path(table) : List.of();
        String alias = t.alias() == null ? table.table() : t.alias().name();
        List<String> names = aliasColumns(plan.schema().fields().stream().map(Schema.Field::name).toList(),
                t.alias(), alias, t.position());
        return relation(plan, new Scope.Table(alias, path), names);
    }

    /**
     * Binds the query of a view where a query reads it: its names resolve
     * to the tables they resolved to when it was created, and its columns
     * have the names they had then. An error in it names the view, at the
     * position of the name that reads it.
     */
    private PlanNode view(TableRef table, ViewDefinition view, Position position) {
        if (!viewsInUse.add(table)) {
            throw new SqlException("View " + table + " reads itself", position);
        }
        CatalogView outer = catalog;
        catalog = view.catalog(statementCatalog);
        PlanNode plan;
        try {
            plan = query(view.query(), Map.of());
        } catch (SqlException e) {
            String reason = e.reason().startsWith("In view ") ? e.reason() : "In view " + table + ": " + e.reason();
            throw new SqlException(reason, position, e);
        } finally {
            catalog = outer;
            viewsInUse.remove(table);
        }
        Schema schema = plan.schema();
        if (schema.size() != view.schema().size()) {
            throw new SqlException("View " + table + " has " + view.schema().size() + " columns, but its query now"
                    + " gives " + schema.size() + "; its tables have changed, so create it again", position);
        }
        boolean same = true;
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            same &= schema.name(i).equals(view.schema().name(i));
            columns.add(new NamedExpression(view.schema().name(i), new Expression.Column(i, schema.type(i))));
        }
        return same ? plan : new PlanNode.Project(plan, columns);
    }

    private PlanNode.Scan scan(TableRef table) {
        return PlanNode.Scan.all(table, catalog.schema(table));
    }

    private TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> named,
            Position position) {
        try {
            return catalog.tableFunction(name, arguments, named);
        } catch (SqlException e) {
            throw new SqlException(e.reason(), position, e.getCause());
        }
    }

    /** The default alias of a file: its name up to the first dot. */
    private static String fileAlias(String path) {
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Renames the first columns of a plan, as the column list of a common table expression or a view does. */
    static PlanNode rename(PlanNode plan, List<String> names, String table, Position position) {
        if (names.isEmpty()) {
            return plan;
        }
        Schema schema = plan.schema();
        if (names.size() > schema.size()) {
            throw new SqlException("table \"" + table + "\" has " + schema.size() + " columns available but "
                    + names.size() + " columns specified", position);
        }
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new NamedExpression(i < names.size() ? names.get(i) : schema.name(i),
                    new Expression.Column(i, schema.type(i))));
        }
        return new PlanNode.Project(plan, columns);
    }

    /** A relation of a plan under an alias; the names of a subquery become unique first. */
    private Relation named(PlanNode plan, FromItem.Alias alias, String defaultName, boolean unique,
            Position position) {
        List<String> names = plan.schema().fields().stream().map(Schema.Field::name).toList();
        if (unique) {
            names = unique(names);
        }
        String name = alias != null ? alias.name() : defaultName;
        return relation(plan, new Scope.Table(name, List.of()), aliasColumns(names, alias, name, position));
    }

    private Relation relation(PlanNode plan, Scope.Table table, List<String> names) {
        List<Scope.Column> columns = new ArrayList<>();
        Schema schema = plan.schema();
        BitSet untyped = untypedNulls(plan);
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new Scope.Column(table, names.get(i), new Expression.Column(i, schema.type(i)), false,
                    untyped.get(i)));
        }
        return new Relation(plan, columns);
    }

    /**
     * The columns of a plan of this binder that are a bare {@code NULL} of a
     * select list, through sorts, limits, renames, {@code DISTINCT} and set
     * operations of such columns; DuckDB keeps their type open.
     */
    private BitSet untypedNulls(PlanNode plan) {
        BitSet known = untypedNulls.get(plan);
        if (known != null) {
            return known;
        }
        return switch (plan) {
            case PlanNode.Sort s -> untypedNulls(s.input());
            case PlanNode.Limit l -> untypedNulls(l.input());
            case PlanNode.Filter f -> untypedNulls(f.input());
            case PlanNode.Unnest u -> untypedNulls(u.input());
            case PlanNode.Window w -> untypedNulls(w.input());
            case PlanNode.Project p -> {
                BitSet input = untypedNulls(p.input());
                BitSet result = new BitSet();
                for (int i = 0; i < p.columns().size(); i++) {
                    if (p.columns().get(i).expression() instanceof Expression.Column c && input.get(c.index())) {
                        result.set(i);
                    }
                }
                yield result;
            }
            case PlanNode.Aggregate a when a.aggregates().isEmpty() -> {
                BitSet input = untypedNulls(a.input());
                BitSet result = new BitSet();
                for (int i = 0; i < a.groupColumns().size(); i++) {
                    result.set(i, input.get(a.groupColumns().get(i)));
                }
                yield result;
            }
            case PlanNode.Union u -> {
                BitSet result = (BitSet) untypedNulls(u.inputs().get(0)).clone();
                for (PlanNode input : u.inputs()) {
                    result.and(untypedNulls(input));
                }
                yield result;
            }
            case PlanNode.SetOperation s -> {
                BitSet result = (BitSet) untypedNulls(s.left()).clone();
                result.and(untypedNulls(s.right()));
                yield result;
            }
            case PlanNode.CommonTable t -> untypedNulls(t.input());
            case PlanNode.CommonTableScan s -> {
                BitSet definition = untypedNulls(s.definition());
                BitSet result = new BitSet();
                for (int i = 0; i < s.columns().size(); i++) {
                    result.set(i, definition.get(s.columns().get(i)));
                }
                yield result;
            }
            default -> new BitSet();
        };
    }

    /** Applies the column list of an alias to the first names. */
    private static List<String> aliasColumns(List<String> names, FromItem.Alias alias, String table,
            Position position) {
        if (alias == null || alias.columns().isEmpty()) {
            return names;
        }
        if (alias.columns().size() > names.size()) {
            throw new SqlException("table \"" + table + "\" has " + names.size() + " columns available but "
                    + alias.columns().size() + " columns specified", position);
        }
        List<String> result = new ArrayList<>(names);
        for (int i = 0; i < alias.columns().size(); i++) {
            result.set(i, alias.columns().get(i));
        }
        return result;
    }

    /**
     * Makes names unique as DuckDB does for the columns of a subquery: a name
     * that is taken, regardless of case, gets {@code _1}, {@code _2} and so
     * on appended.
     */
    static List<String> unique(List<String> names) {
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String name : names) {
            String n = name;
            for (int k = 1; used.contains(SqlTrees.key(n)); k++) {
                n = name + "_" + k;
            }
            used.add(SqlTrees.key(n));
            result.add(n);
        }
        return result;
    }

    // ----------------------------------------------------------------- joins

    private static Side side(Expression e, int leftWidth) {
        return Expressions.side(e, leftWidth);
    }

    private static Expression shift(Expression e, int from, int by) {
        return Expressions.shift(e, from, by);
    }

    private static List<Scope.Column> shift(List<Scope.Column> columns, int by) {
        List<Scope.Column> result = new ArrayList<>();
        for (Scope.Column c : columns) {
            result.add(new Scope.Column(c.table(), c.name(), shift(c.expression(), 0, by), c.qualifiedOnly()));
        }
        return result;
    }

    /** Collects the keys of a join, computing those that are no plain columns as extra columns of each input. */
    private static final class Keys {
        final int leftWidth;
        final int rightWidth;
        final List<Expression> leftExtras = new ArrayList<>();
        final List<Expression> rightExtras = new ArrayList<>();
        final List<Integer> leftKeys = new ArrayList<>();
        final List<Integer> rightKeys = new ArrayList<>();

        Keys(int leftWidth, int rightWidth) {
            this.leftWidth = leftWidth;
            this.rightWidth = rightWidth;
        }

        /** Adds a key: an expression over the left input and one over the right input, of the same type. */
        void add(Expression left, Expression right) {
            leftKeys.add(column(left, leftWidth, leftExtras));
            rightKeys.add(column(right, rightWidth, rightExtras));
        }

        private static int column(Expression e, int width, List<Expression> extras) {
            if (e instanceof Expression.Column c) {
                return c.index();
            }
            int i = extras.indexOf(e);
            if (i < 0) {
                extras.add(e);
                i = extras.size() - 1;
            }
            return width + i;
        }

        static PlanNode extend(PlanNode plan, List<Expression> extras) {
            return Plans.withKeyColumns(plan, extras);
        }
    }

    private Relation join(FromItem.Join j, Map<String, Cte> ctes) {
        Relation left = from(j.left(), ctes);
        if (unnests(j.right())) {
            Expression list = unnestList((FromItem.TableFunctionCall) j.right(), left.scope());
            if (side(list, left.plan().schema().size()) != Side.NONE) {
                return lateralUnnest(j, left, list);
            }
        }
        Relation right = from(j.right(), ctes);
        int lw = left.plan().schema().size();
        int rw = right.plan().schema().size();
        PlanNode.JoinType type = PlanNode.JoinType.valueOf(j.kind().name());
        if (type == PlanNode.JoinType.CROSS) {
            List<Scope.Column> columns = new ArrayList<>(left.columns());
            columns.addAll(shift(right.columns(), lw));
            return new Relation(new PlanNode.Join(left.plan(), right.plan(), List.of(), List.of(), type), columns);
        }
        if (j.natural() || !j.using().isEmpty()) {
            return using(j, left, right, type);
        }
        List<Scope.Column> combined = new ArrayList<>(left.columns());
        combined.addAll(shift(right.columns(), lw));
        ExpressionBinder binder = new ExpressionBinder(context, new Scope(combined, true), Map.of(),
                "JOIN clause cannot contain aggregates!", null);
        Keys keys = new Keys(lw, rw);
        List<Expression> filters = new ArrayList<>();
        List<Expression> conditions = new ArrayList<>();
        List<Expression> leftFilters = new ArrayList<>();
        List<Expression> rightFilters = new ArrayList<>();
        for (SqlExpr conjunct : SqlTrees.conjuncts(j.on())) {
            if (conjunct instanceof SqlExpr.Binary b && b.op() == SqlExpr.BinaryOp.EQUAL) {
                ExpressionBinder.Bound l = binder.bound(b.left());
                ExpressionBinder.Bound r = binder.bound(b.right());
                Side ls = side(l.expression(), lw);
                Side rs = side(r.expression(), lw);
                if (ls == Side.LEFT && rs == Side.RIGHT || ls == Side.RIGHT && rs == Side.LEFT) {
                    Expression[] operands = sameType(binder.equality(l, r, b.position()));
                    Expression onLeft = ls == Side.LEFT ? operands[0] : operands[1];
                    Expression onRight = ls == Side.LEFT ? operands[1] : operands[0];
                    keys.add(onLeft, shift(onRight, 0, -lw));
                    continue;
                }
            }
            Expression condition = binder.condition(conjunct);
            Side side = side(condition, lw);
            if (type == PlanNode.JoinType.INNER) {
                filters.add(condition);
            } else if (side == Side.RIGHT && type == PlanNode.JoinType.LEFT) {
                // A condition on the side that is not preserved filters that input before the join.
                rightFilters.add(shift(condition, 0, -lw));
            } else if (side == Side.LEFT && type == PlanNode.JoinType.RIGHT) {
                leftFilters.add(condition);
            } else {
                // Any other condition decides which pairs match, and a row without a match is kept with NULLs.
                conditions.add(condition);
            }
        }
        if (type != PlanNode.JoinType.INNER && keys.leftKeys.isEmpty()) {
            // An outer join needs a key; the constant TRUE on both sides pairs every row with every other.
            Expression yes = new Expression.Literal(DataType.BOOLEAN, true);
            keys.add(yes, yes);
        }
        PlanNode l = Keys.extend(filtered(left.plan(), leftFilters), keys.leftExtras);
        PlanNode r = Keys.extend(filtered(right.plan(), rightFilters), keys.rightExtras);
        int offset = l.schema().size();
        Expression condition = conditions.isEmpty() ? null
                : shift(Expressions.conjunction(conditions), lw, offset - lw);
        PlanNode plan = keys.leftKeys.isEmpty()
                ? new PlanNode.Join(l, r, List.of(), List.of(), PlanNode.JoinType.CROSS)
                : new PlanNode.Join(l, r, keys.leftKeys, keys.rightKeys, type, condition);
        if (!filters.isEmpty()) {
            plan = new PlanNode.Filter(plan, shift(Expressions.conjunction(filters), lw, offset - lw));
        }
        List<Scope.Column> columns = new ArrayList<>(left.columns());
        columns.addAll(shift(right.columns(), offset));
        return new Relation(plan, columns);
    }

    /** A plan filtered by conditions, or the plan itself without any. */
    private static PlanNode filtered(PlanNode plan, List<Expression> conditions) {
        return conditions.isEmpty() ? plan : new PlanNode.Filter(plan, Expressions.conjunction(conditions));
    }

    /** Casts two comparable keys to their common type, as a join needs keys of the same type. */
    private static Expression[] sameType(Expression[] keys) {
        DataType t = TypeRules.common(keys[0].type(), keys[1].type());
        Expression[] result = new Expression[2];
        for (int i = 0; i < 2; i++) {
            result[i] = keys[i].type().equals(t) ? keys[i] : new Expression.Cast(keys[i], t);
        }
        return result;
    }

    /**
     * {@code USING} and {@code NATURAL}: equal columns of the same names. The
     * output has each pair once, at the place of the left column, as the
     * left value, the right value for a {@code RIGHT} join, and the first
     * that is not {@code NULL} for a {@code FULL} join; the two columns
     * remain reachable by qualified names.
     */
    private Relation using(FromItem.Join j, Relation left, Relation right, PlanNode.JoinType type) {
        Scope leftScope = left.scope();
        Scope rightScope = right.scope();
        List<String> names = new ArrayList<>();
        if (j.natural()) {
            for (Scope.Column c : left.columns()) {
                if (!c.qualifiedOnly() && rightScope.find(List.of(c.name()), j.position()) != null
                        && names.stream().noneMatch(n -> n.equalsIgnoreCase(c.name()))) {
                    names.add(c.name());
                }
            }
            if (names.isEmpty()) {
                throw new SqlException("No columns found to join on in NATURAL JOIN. Use CROSS JOIN if you intended"
                        + " for this to be a cross-product.", j.position());
            }
        } else {
            names.addAll(j.using());
        }
        int lw = left.plan().schema().size();
        Keys keys = new Keys(lw, right.plan().schema().size());
        ExpressionBinder binder = new ExpressionBinder(context, Scope.none(), Map.of(), null, null);
        Map<Scope.Column, Expression[]> pairs = new IdentityHashMap<>();
        Set<Scope.Column> usedRight = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : names) {
            Scope.Column lc = leftScope.find(List.of(name), j.position());
            if (lc == null) {
                throw new SqlException("Column \"" + name + "\" does not exist on left side of join!", j.position());
            }
            Scope.Column rc = rightScope.find(List.of(name), j.position());
            if (rc == null) {
                throw new SqlException("Column \"" + name + "\" does not exist on right side of join!", j.position());
            }
            Expression[] operands = sameType(binder.equality(ExpressionBinder.Bound.typed(lc.expression()),
                    ExpressionBinder.Bound.typed(rc.expression()), j.position()));
            keys.add(operands[0], operands[1]);
            pairs.put(lc, new Expression[] {lc.expression(), rc.expression(), operands[0], operands[1]});
            usedRight.add(rc);
        }
        PlanNode l = Keys.extend(left.plan(), keys.leftExtras);
        PlanNode r = Keys.extend(right.plan(), keys.rightExtras);
        int offset = l.schema().size();
        PlanNode plan = new PlanNode.Join(l, r, keys.leftKeys, keys.rightKeys, type);
        List<Scope.Column> columns = new ArrayList<>();
        for (Scope.Column c : left.columns()) {
            Expression[] p = pairs.get(c);
            if (p != null) {
                Expression value = switch (type) {
                    case RIGHT -> shift(p[1], 0, offset);
                    case FULL -> new Expression.Coalesce(List.of(p[2], shift(p[3], 0, offset)));
                    default -> p[0];
                };
                columns.add(new Scope.Column(null, c.name(), value, false));
                columns.add(c.hidden());
            } else {
                columns.add(c);
            }
        }
        for (Scope.Column c : right.columns()) {
            Scope.Column shifted = new Scope.Column(c.table(), c.name(), shift(c.expression(), 0, offset),
                    c.qualifiedOnly());
            columns.add(usedRight.contains(c) ? shifted.hidden() : shifted);
        }
        return new Relation(plan, columns);
    }

    // ---------------------------------------------------------------- SELECT

    /**
     * Binds a select. A subquery reaches the columns of the query around it
     * in its {@code WHERE} clause; the conditions there that do are left out
     * of the filter and tell the join of the subquery how to match its rows
     * to those of the query around it: by keys, which are added to its
     * output, and a condition. An aggregating subquery groups by its keys
     * as well. Without {@code GROUP BY}, it has a row for every row of the
     * query around it, even without a group: its output is then the
     * aggregation itself, and its value is computed after the join, where
     * a {@code count} without a group is 0.
     *
     * @param correlation the references to the query around a subquery, or {@code null}
     */
    private PlanNode select(QueryBody.Select s, List<OrderItem> orderBy, SqlExpr limit, SqlExpr offset,
            Map<String, Cte> ctes, Correlation correlation) {
        Relation from = s.from() == null
                ? new Relation(oneRow(), List.of())
                : from(s.from(), ctes);
        Scope scope = s.from() == null ? Scope.none() : from.scope();
        Scope whereScope = scope;
        if (correlation != null) {
            whereScope = scope.withOuter(correlation);
            scope = scope.withOuter(correlation.outsideWhere());
        }
        PlanNode plan = from.plan();
        List<Item> items = unnestStructs(expand(s.items(), scope), scope);
        if (items.isEmpty()) {
            throw new SqlException("SELECT list is empty after resolving * expressions!", s.position());
        }
        Map<String, SqlExpr> aliases = new HashMap<>();
        Map<String, Integer> aliasOutputs = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.alias() != null) {
                aliases.putIfAbsent(SqlTrees.key(item.alias()), item.expression());
                aliasOutputs.putIfAbsent(SqlTrees.key(item.alias()), i);
            }
        }
        for (int i = 0; i < items.size() && catalog.postgresNames(); i++) {
            // PostgreSQL groups and orders by the name of an output column without an alias as well, as count.
            Item item = items.get(i);
            if (item.alias() == null && item.expression() != null
                    && !(item.expression() instanceof SqlExpr.Identifier)) {
                aliases.putIfAbsent(SqlTrees.key(item.name()), item.expression());
                aliasOutputs.putIfAbsent(SqlTrees.key(item.name()), i);
            }
        }
        List<Conjunct> conjuncts = new ArrayList<>();
        if (s.where() != null) {
            plan = where(s.where(), plan, whereScope, aliases, ctes, correlation == null ? null : conjuncts);
        }
        Correlated correlated = conjuncts.isEmpty() ? null : Correlated.of(conjuncts);
        if (correlated != null && (limit != null || offset != null)) {
            throw new SqlException("Not supported: LIMIT or OFFSET in a correlated subquery",
                    (limit != null ? limit : offset).position());
        }
        if (correlated != null) {
            // The conditions of the join sit above the plan of the subquery, where a window would see too many rows.
            SqlExpr.Window w = firstWindow(s, items, orderBy);
            if (w != null) {
                throw new SqlException("Not supported: a window function in a correlated subquery", w.position());
            }
        }
        Windowing windowing = new Windowing(s.windows());
        boolean aggregates = items.stream().anyMatch(i -> i.expression() != null
                && SqlTrees.containsAggregate(i.expression()))
                || orderBy.stream().anyMatch(o -> !o.isAll() && SqlTrees.containsAggregate(o.expression()))
                || s.qualify() != null && SqlTrees.containsAggregate(s.qualify());
        // DuckDB ignores a GROUP BY ALL that finds nothing to group by in a select list without aggregates.
        boolean nothingToGroup = s.groupBy() != null && s.groupBy().all() && !aggregates && s.having() == null
                && items.stream().noneMatch(i -> i.column() != null
                        || SqlTrees.containsNameOutsideAggregates(i.expression()));
        boolean aggregate = s.groupBy() != null && !nothingToGroup || s.having() != null || aggregates;
        List<String> names = new ArrayList<>(items.stream().map(Item::name).toList());
        if (!aggregate) {
            List<Expression> outputs = new ArrayList<>();
            Map<String, SqlExpr> earlier = new HashMap<>();
            BitSet untyped = new BitSet();
            Unnesting unnesting = new Unnesting();
            String nested = "aggregate function calls cannot be nested";
            SubqueryJoiner subqueries = new SubqueryJoiner((q, c) -> query(q, ctes, c),
                    new ExpressionBinder(context, scope, Map.of(), nested, null));
            ExpressionBinder.Hook hook = windowing.hook(new ExpressionBinder(context, scope, earlier, nested, null),
                    unnesting.hook(new ExpressionBinder(context, scope, earlier, nested, null), null));
            for (Item item : items) {
                if (item.column() != null) {
                    outputs.add(item.column());
                } else {
                    ExpressionBinder.Bound b = new ExpressionBinder(context, scope, earlier, nested, hook, subqueries)
                            .bound(item.expression());
                    untyped.set(outputs.size(), b.isNull());
                    outputs.add(b.expression());
                }
                if (item.alias() != null) {
                    earlier.putIfAbsent(SqlTrees.key(item.alias()), item.expression());
                }
            }
            ExpressionBinder windowArguments = new ExpressionBinder(context, scope, aliases, null, null);
            Expression qualify = qualify(s, windowing, new ExpressionBinder(context, scope, aliases, null,
                    windowing.hook(windowArguments, null)));
            // A key of the order may hold a subquery, which is joined as those of the select list are.
            ExpressionBinder orderBinder = new ExpressionBinder(context, scope, aliases, null,
                    windowing.hook(windowArguments, unnesting.hook(windowArguments, null)), subqueries);
            List<Order> orders = orders(orderBy, outputs, aliasOutputs, orderBinder);
            int ordered = orders.size();
            orders.addAll(orders(distinctOn(s, correlated), outputs, aliasOutputs, orderBinder));
            plan = subqueries.attach(plan);
            outputs.replaceAll(subqueries::resolve);
            orders.replaceAll(o -> o.expression() == null ? o
                    : new Order(o.output(), subqueries.resolve(o.expression()), o.ascending(), o.nullsFirst()));
            plan = windowing.plan(plan, outputs, orders);
            if (qualify != null) {
                plan = new PlanNode.Filter(plan, windowing.resolve(qualify));
            }
            plan = unnesting.plan(plan, outputs, orders);
            if (correlated != null) {
                if (s.distinct() && correlation.kind() == Correlation.Kind.SCALAR) {
                    throw new SqlException("Not supported: DISTINCT in a correlated scalar subquery", s.position());
                }
                int n = outputs.size();
                List<Integer> keys = new ArrayList<>();
                for (Expression e : correlated.inner()) {
                    keys.add(correlatedOutput(outputs, names, n, e));
                }
                Expression condition = correlated.condition();
                if (condition != null) {
                    condition = Expressions.transform(condition, x -> x instanceof Expression.Column c
                            && c.index() < Correlation.PLACEHOLDER
                            ? new Expression.Column(correlatedOutput(outputs, names, n, x), c.type()) : x);
                }
                correlation.join(new Correlation.Join(keys, correlated.outer(), condition, null, n));
            }
            plan = finish(plan, outputs, names, s.distinct(), orders.subList(0, ordered),
                    orders.subList(ordered, orders.size()));
            untypedNulls.put(plan, untyped);
        } else {
            Scope rowScope = whereScope;
            Supplier<PlanNode> rows = () -> {
                PlanNode r = s.from() == null ? oneRow() : from(s.from(), ctes).plan();
                return s.where() == null ? r : where(s.where(), r, rowScope, aliases, ctes, null);
            };
            plan = aggregate(s, plan, rows, scope, items, aliases, aliasOutputs, orderBy, names, ctes, correlation,
                    correlated, windowing);
        }
        return limit(plan, limit, offset);
    }

    /**
     * The output column of an expression that the join of a correlated
     * subquery reads, added after the select list if it is not there yet.
     */
    private static int correlatedOutput(List<Expression> outputs, List<String> names, int n, Expression e) {
        int i = outputs.subList(n, outputs.size()).indexOf(e);
        if (i >= 0) {
            return n + i;
        }
        names.add("#corr" + (outputs.size() - n));
        outputs.add(e);
        return outputs.size() - 1;
    }

    /**
     * Filters the rows of a select by its {@code WHERE} clause. A condition
     * that is {@code [NOT] EXISTS} or {@code IN} with a subquery is a
     * {@code SEMI} or {@code ANTI} join right on the rows; the other
     * subqueries are joined after those, below the filter of the other
     * conditions.
     *
     * @param correlated where the conditions of a subquery that reference
     *        the query around it go, or {@code null} for a query that is no
     *        subquery
     */
    private PlanNode where(SqlExpr where, PlanNode rows, Scope scope, Map<String, SqlExpr> aliases,
            Map<String, Cte> ctes, List<Conjunct> correlated) {
        String error = "WHERE clause cannot contain aggregates!";
        SubqueryJoiner subqueries = new SubqueryJoiner((q, c) -> query(q, ctes, c),
                new ExpressionBinder(context, scope, Map.of(), error, null));
        ExpressionBinder binder = new ExpressionBinder(context, scope, aliases, error, null, subqueries);
        List<SqlExpr> conjuncts = SqlTrees.conjuncts(where);
        List<SqlExpr> rest = new ArrayList<>();
        for (SqlExpr conjunct : conjuncts) {
            if (!subqueries.filter(conjunct, binder)) {
                rest.add(conjunct);
            }
        }
        List<Expression> conditions = new ArrayList<>();
        if (correlated == null && rest.size() == conjuncts.size()) {
            conditions.add(binder.condition(where));
        } else {
            for (SqlExpr conjunct : rest) {
                Expression condition = binder.condition(conjunct);
                if (correlated != null && Correlation.reads(condition)) {
                    if (SqlTrees.containsSubquery(conjunct)) {
                        throw new SqlException("Not supported: a subquery in a condition that references the outer"
                                + " query", conjunct.position());
                    }
                    correlated.add(new Conjunct(conjunct, condition));
                } else {
                    conditions.add(condition);
                }
            }
        }
        PlanNode plan = subqueries.attach(rows);
        return conditions.isEmpty() ? plan
                : new PlanNode.Filter(plan, subqueries.resolve(Expressions.conjunction(conditions)));
    }

    /** Expands the stars of a select list and names its items. */
    private List<Item> expand(List<SelectItem> selectItems, Scope scope) {
        List<Item> items = new ArrayList<>();
        for (SelectItem it : selectItems) {
            switch (it) {
                case SelectItem.Star star -> {
                    List<Scope.Column> columns = scope.star(star.qualifier(), star.position());
                    for (String x : star.exclude()) {
                        if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(x))) {
                            throw new SqlException("Column \"" + x + "\" in EXCLUDE list not found in FROM clause",
                                    star.position());
                        }
                    }
                    for (SelectItem.Replacement r : star.replace()) {
                        if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(r.name()))) {
                            throw new SqlException("Column \"" + r.name()
                                    + "\" in REPLACE list not found in FROM clause", star.position());
                        }
                    }
                    Set<String> replaced = new HashSet<>();
                    for (Scope.Column c : columns) {
                        if (star.exclude().stream().anyMatch(x -> x.equalsIgnoreCase(c.name()))) {
                            continue;
                        }
                        SelectItem.Replacement replacement = star.replace().stream()
                                .filter(r -> r.name().equalsIgnoreCase(c.name())).findFirst().orElse(null);
                        if (replacement == null) {
                            items.add(new Item(null, c.expression(), c.name(), null, star.position()));
                        } else if (replaced.add(SqlTrees.key(replacement.name()))) {
                            // As in DuckDB, the first column of the name is replaced, and the others are left out.
                            items.add(new Item(replacement.expression(), null, replacement.name(), null,
                                    star.position()));
                        }
                    }
                }
                case SelectItem.ExpressionItem e -> items.add(new Item(e.expression(), null,
                        e.alias() != null ? e.alias() : catalog.postgresNames()
                                ? PgLabels.name(e.expression(), id -> name(id, scope)) : name(e.expression(), scope),
                        e.alias(), e.expression().position()));
            }
        }
        return items;
    }

    /** DuckDB's name of {@code count(*)}, with its filter. */
    private static String countStar(SqlExpr.FunctionCall f) {
        return "count_star()" + SqlPrinter.print(f).substring(SqlPrinter.print(f.withFilter(null)).length());
    }

    /** The name of an unaliased item, after DuckDB: a column keeps its name, other expressions their text. */
    private static String name(SqlExpr e, Scope scope) {
        if (e instanceof SqlExpr.Identifier id) {
            try {
                Scope.Column c = scope.find(id.parts(), id.position());
                return c != null ? c.name() : id.name();
            } catch (SqlException x) {
                return id.name();
            }
        }
        if (e instanceof SqlExpr.FunctionCall f && SqlTrees.key(f.name()).equals("count")
                && (f.star() || f.arguments().isEmpty())) {
            return countStar(f);
        }
        if (isGrouping(e)) {
            return groupingName((SqlExpr.FunctionCall) e);
        }
        if (e instanceof SqlExpr.Unary u && u.op() == SqlExpr.UnaryOp.MINUS && u.operand() instanceof SqlExpr.Literal l
                && l.kind() == SqlExpr.LiteralKind.NUMBER) {
            return "-" + l.text();
        }
        if (e instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.NUMBER
                && l.text().toLowerCase(java.util.Locale.ROOT).contains("e")) {
            // DuckDB names a number with an exponent by its value.
            return Casts.format(Double.parseDouble(l.text()), DataType.DOUBLE);
        }
        return SqlPrinter.print(e);
    }

    /**
     * Resolves {@code ORDER BY} of a select: a position, an alias of the
     * select list, or an expression, which is an output column if it equals
     * one.
     */
    private static List<Order> orders(List<OrderItem> items, List<Expression> outputs,
            Map<String, Integer> aliasOutputs, ExpressionBinder binder) {
        List<Order> orders = new ArrayList<>();
        for (OrderItem item : items) {
            if (item.isAll()) {
                for (int i = 0; i < outputs.size(); i++) {
                    orders.add(new Order(i, null, item.ascending(), item.nullsFirst()));
                }
                continue;
            }
            SqlExpr e = item.expression();
            checkOrderTerm(e, outputs.size());
            long position = SqlTrees.signedPosition(e);
            if (position >= 0) {
                orders.add(new Order(outputPosition(position, outputs.size(), e.position()), null, item.ascending(),
                        item.nullsFirst()));
                continue;
            }
            if (e instanceof SqlExpr.Identifier id && id.parts().size() == 1) {
                Integer output = aliasOutputs.get(SqlTrees.key(id.name()));
                if (output != null) {
                    orders.add(new Order(output, null, item.ascending(), item.nullsFirst()));
                    continue;
                }
            }
            Expression x = binder.bind(e);
            int output = outputs.indexOf(x);
            orders.add(output >= 0 ? new Order(output, null, item.ascending(), item.nullsFirst())
                    : new Order(-1, x, item.ascending(), item.nullsFirst()));
        }
        return orders;
    }

    /**
     * The expressions of {@code DISTINCT ON}, as the keys of an order;
     * rejected in a correlated subquery, whose join sits above them.
     */
    private static List<OrderItem> distinctOn(QueryBody.Select s, Correlated correlated) {
        if (!s.distinctOn().isEmpty() && correlated != null) {
            throw new SqlException("Not supported: DISTINCT ON in a correlated subquery", s.position());
        }
        return s.distinctOn().stream().map(e -> new OrderItem(e, true, false)).toList();
    }

    /**
     * Projects the outputs, then makes them distinct and sorts them. Without
     * {@code DISTINCT} a sort key may be any expression over the input; with
     * it, an expression over the outputs. With {@code DISTINCT ON}, the
     * first row of each value of its keys in the order stays, numbered by
     * {@code row_number()} over a window partitioned by them.
     */
    private static PlanNode finish(PlanNode input, List<Expression> outputs, List<String> names, boolean distinct,
            List<Order> orders, List<Order> distinctOn) {
        int n = outputs.size();
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            columns.add(new NamedExpression(names.get(i), outputs.get(i)));
        }
        List<Expression> extras = new ArrayList<>();
        List<SortKey> keys = new ArrayList<>();
        PlanNode plan;
        boolean numbered = false;
        if (distinct) {
            plan = distinct(new PlanNode.Project(input, columns));
            Schema schema = plan.schema();
            for (Order o : orders) {
                int column = o.output();
                if (column < 0) {
                    Expression over = over(o.expression(), outputs);
                    if (over == null) {
                        throw new SqlException("For SELECT DISTINCT, ORDER BY expressions must appear in select list",
                                Position.UNKNOWN);
                    }
                    column = over instanceof Expression.Column c ? c.index() : extra(extras, over, n);
                }
                keys.add(new SortKey(column, o.ascending(), o.nullsFirst()));
            }
            if (keys.isEmpty()) {
                return plan;
            }
            List<NamedExpression> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(new NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
            }
            plan = extras.isEmpty() ? plan : new PlanNode.Project(plan, withExtras(all, extras));
        } else {
            for (Order o : orders) {
                int column = o.output() >= 0 ? o.output() : extra(extras, o.expression(), n);
                keys.add(new SortKey(column, o.ascending(), o.nullsFirst()));
            }
            List<Integer> partition = new ArrayList<>();
            for (Order o : distinctOn) {
                partition.add(o.output() >= 0 ? o.output() : extra(extras, o.expression(), n));
            }
            plan = new PlanNode.Project(input, withExtras(columns, extras));
            if (!partition.isEmpty()) {
                int width = plan.schema().size();
                plan = new PlanNode.Window(plan, partition, keys, List.of(PlanNode.WindowCall.of(
                        PlanNode.WindowFunction.ROW_NUMBER, List.of(), "#distinct_on")));
                plan = new PlanNode.Filter(plan, new Expression.Comparison(Expression.ComparisonOp.EQ,
                        new Expression.Column(width, DataType.BIGINT), new Expression.Literal(DataType.BIGINT, 1L)));
                numbered = true;
            }
            if (keys.isEmpty() && !numbered) {
                return plan;
            }
        }
        if (!keys.isEmpty()) {
            plan = new PlanNode.Sort(plan, keys);
        }
        if (extras.isEmpty() && !numbered) {
            return plan;
        }
        Schema schema = plan.schema();
        List<NamedExpression> visible = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            visible.add(new NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
        }
        return new PlanNode.Project(plan, visible);
    }

    private static int extra(List<Expression> extras, Expression e, int width) {
        int i = extras.indexOf(e);
        if (i < 0) {
            extras.add(e);
            i = extras.size() - 1;
        }
        return width + i;
    }

    private static List<NamedExpression> withExtras(List<NamedExpression> columns, List<Expression> extras) {
        List<NamedExpression> result = new ArrayList<>(columns);
        for (int i = 0; i < extras.size(); i++) {
            result.add(new NamedExpression("#order" + i, extras.get(i)));
        }
        return result;
    }

    /** Rewrites an expression over the input as one over the outputs, or returns {@code null} if it reads more. */
    private static Expression over(Expression e, List<Expression> outputs) {
        int i = outputs.indexOf(e);
        if (i >= 0) {
            return new Expression.Column(i, e.type());
        }
        if (e instanceof Expression.Column) {
            return null;
        }
        List<Expression> children = new ArrayList<>();
        for (Expression c : e.children()) {
            Expression x = over(c, outputs);
            if (x == null) {
                return null;
            }
            children.add(x);
        }
        return Expressions.withChildren(e, children);
    }

    // ---------------------------------------------------------------- unnest

    /**
     * The {@code unnest} of a list in a select list and its {@code ORDER BY}:
     * the list is bound over the rows that reach the select list, and the
     * call stands for the column of its elements, which an
     * {@link PlanNode.Unnest} adds after the columns of those rows. DuckDB
     * unnests several lists of a select list side by side; FyrQL takes one
     * list, which may be unnested more than once.
     */
    private static final class Unnesting {

        /** The index of the column of the elements until the width of the rows is known. */
        private static final int PENDING = Integer.MAX_VALUE;

        private Expression list;

        /**
         * A hook that binds the calls of {@code unnest}, their arguments with
         * another binder, and leaves every other node to the next hook.
         */
        ExpressionBinder.Hook hook(ExpressionBinder arguments, ExpressionBinder.Hook next) {
            return e -> SqlTrees.isUnnest(e) ? call((SqlExpr.FunctionCall) e, arguments)
                    : next == null ? null : next.intercept(e);
        }

        private Expression call(SqlExpr.FunctionCall f, ExpressionBinder arguments) {
            Position p = f.position();
            if (f.filter() != null) {
                throw new SqlException(ExpressionBinder.scalarFilter("unnest"), p);
            }
            if (f.distinct() || f.star() || !f.orderBy().isEmpty() || f.arguments().size() != 1) {
                throw new SqlException("Wrong number of arguments for the function unnest", p);
            }
            SqlExpr argument = f.arguments().get(0);
            if (SqlTrees.containsUnnest(argument)) {
                throw new SqlException("Nested UNNEST calls are not supported - use UNNEST(x, recursive := true) to"
                        + " unnest multiple levels", p);
            }
            ExpressionBinder.Bound b = arguments.bound(argument);
            Expression l = b.isNull() ? new Expression.Literal(DataType.listOf(DataType.INTEGER), null)
                    : b.expression();
            if (l.type() instanceof DataType.Struct) {
                throw new SqlException("UNNEST() on a struct column can only be applied as the root element of a"
                        + " SELECT expression", p);
            }
            if (!(l.type() instanceof DataType.ListType t)) {
                throw new SqlException("UNNEST() can only be applied to lists, structs and NULL, not "
                        + l.type().sqlName(), p);
            }
            if (list == null) {
                list = l;
            } else if (!list.equals(l)) {
                throw new SqlException("Not supported: UNNEST of more than one list in a select list", p);
            }
            return new Expression.Column(PENDING, t.element());
        }

        /**
         * Unnests the list over the rows, if there is one, and makes the
         * outputs and the keys of the order read its elements from the
         * column after the rows.
         */
        PlanNode plan(PlanNode rows, List<Expression> outputs, List<Order> orders) {
            if (list == null) {
                return rows;
            }
            int width = rows.schema().size();
            outputs.replaceAll(e -> resolve(e, width));
            orders.replaceAll(o -> o.expression() == null ? o
                    : new Order(o.output(), resolve(o.expression(), width), o.ascending(), o.nullsFirst()));
            return new PlanNode.Unnest(rows, list, "unnest");
        }

        private static Expression resolve(Expression e, int width) {
            return Expressions.transform(e, x -> x instanceof Expression.Column c && c.index() == PENDING
                    ? new Expression.Column(width, c.type()) : x);
        }
    }

    /**
     * Replaces an item that is the {@code unnest} of a struct by one item
     * per field, named as the field; as in DuckDB, an alias of the item is
     * ignored. A struct that only an aggregate gives is not taken.
     */
    private List<Item> unnestStructs(List<Item> items, Scope scope) {
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            DataType.Struct struct = null;
            SqlExpr argument = null;
            if (item.expression() instanceof SqlExpr.FunctionCall f && SqlTrees.isUnnest(f)
                    && f.arguments().size() == 1 && !f.distinct() && !f.star() && f.orderBy().isEmpty()) {
                argument = f.arguments().get(0);
                if (!SqlTrees.containsAggregate(argument) && !SqlTrees.containsUnnest(argument)) {
                    try {
                        DataType t = new ExpressionBinder(context, scope, Map.of(), null, null).bind(argument).type();
                        struct = t instanceof DataType.Struct s ? s : null;
                    } catch (SqlException e) {
                        // Bound again with the item, where the error tells.
                    }
                }
            }
            if (struct == null) {
                result.add(item);
                continue;
            }
            Position p = item.position();
            for (DataType.Struct.Field field : struct.fields()) {
                SqlExpr name = new SqlExpr.Literal(SqlExpr.LiteralKind.STRING, field.name(), p);
                result.add(new Item(new SqlExpr.Index(argument, name, p), null, field.name(), null, p));
            }
        }
        return result;
    }

    // --------------------------------------------------------------- windows

    /**
     * The window functions of a select list, its {@code QUALIFY} clause and
     * its {@code ORDER BY}: a call is bound over the rows that reach the
     * select list and stands for the column that a {@link PlanNode.Window}
     * adds after them. A projection below the windows passes the rows on and
     * computes the other expressions the calls read after them. The calls
     * of one partition and order share a node, and the nodes follow one
     * another in the order in which their first calls appear.
     */
    private final class Windowing {

        /** The column of the first call until the width of the rows is known. */
        private static final int PLACEHOLDER = 1 << 27;

        /** A call with the partition and the order of its window, over the inputs. */
        private record Call(List<Integer> partition, List<SortKey> order, PlanNode.WindowCall call) {
        }

        /** The windows of the {@code WINDOW} clause, by {@link SqlTrees#key}. */
        private final Map<String, WindowSpec> named = new HashMap<>();
        /** The expressions the calls read; a call reads them by their index until the plan is made. */
        private final List<Expression> inputs = new ArrayList<>();
        private final List<Call> calls = new ArrayList<>();
        /** The column of each call in the plan, once it is made. */
        private int[] results;

        Windowing(List<QueryBody.NamedWindow> windows) {
            for (QueryBody.NamedWindow w : windows) {
                named.putIfAbsent(SqlTrees.key(w.name()), w.window());
            }
        }

        boolean isEmpty() {
            return calls.isEmpty();
        }

        /**
         * A hook that binds the window functions, the expressions they read
         * with another binder, and leaves every other node to the next hook.
         */
        ExpressionBinder.Hook hook(ExpressionBinder arguments, ExpressionBinder.Hook next) {
            return e -> e instanceof SqlExpr.Window w ? call(w, arguments) : next == null ? null : next.intercept(e);
        }

        private int input(Expression e) {
            int i = inputs.indexOf(e);
            if (i < 0) {
                inputs.add(e);
                i = inputs.size() - 1;
            }
            return i;
        }

        private Schema inputSchema() {
            return Schema.of(inputs.stream().map(Expression::type).toArray(DataType[]::new));
        }

        private Expression call(SqlExpr.Window w, ExpressionBinder arguments) {
            Position p = w.position();
            SqlExpr.FunctionCall f = w.call();
            List<SqlExpr> read = new ArrayList<>(f.arguments());
            f.orderBy().forEach(o -> read.add(o.expression()));
            if (w.filter() != null) {
                read.add(w.filter());
            }
            rejectWindows(read, "window function calls cannot be nested");
            WindowSpec spec = spec(w.over(), p);
            List<SqlExpr> definition = new ArrayList<>(spec.partitionBy());
            spec.orderBy().forEach(o -> definition.add(o.expression()));
            rejectWindows(definition, "window functions are not allowed in window definitions");
            List<Integer> partition = new ArrayList<>();
            for (SqlExpr e : spec.partitionBy()) {
                partition.add(input(arguments.bind(e)));
            }
            List<SortKey> order = new ArrayList<>();
            for (OrderItem o : spec.orderBy()) {
                order.add(new SortKey(input(arguments.bind(o.expression())), o.ascending(), o.nullsFirst()));
            }
            String name = SqlTrees.key(f.name());
            String outputName = SqlPrinter.print(w);
            PlanNode.WindowFunction function = function(name, p);
            PlanNode.WindowCall call;
            if (function == PlanNode.WindowFunction.AGGREGATE) {
                if (w.ignoreNulls()) {
                    throw new SqlException("RESPECT/IGNORE NULLS is not supported for windowed aggregates", p);
                }
                AggregateCall aggregate = aggregateCall(f, outputName, arguments, this::input, this::inputSchema,
                        true);
                int filter = w.filter() == null ? -1 : input(arguments.condition(w.filter()));
                PlanNode.Frame frame = frame(spec, order, p);
                call = ExpressionBinder.check(() -> new PlanNode.WindowCall(function, aggregate, List.of(), false,
                        filter, frame, outputName), p, null);
            } else {
                if (w.filter() != null) {
                    throw new SqlException("FILTER is not implemented for non-aggregate window functions!", p);
                }
                if (f.distinct()) {
                    throw new SqlException("DISTINCT is not implemented for non-aggregate window functions!", p);
                }
                if (f.star()) {
                    throw new SqlException("Function \"" + name + "\" cannot be applied to a star expression", p);
                }
                if (!f.orderBy().isEmpty()) {
                    throw new SqlException("Not supported: ORDER BY in the arguments of " + name, p);
                }
                if (w.ignoreNulls() && !function.takesValues()) {
                    throw new SqlException("Not supported: IGNORE NULLS for " + name, p);
                }
                List<Integer> columns = arguments(function, f, arguments);
                // The functions that take no frame ignore one; it is checked all the same.
                PlanNode.Frame given = frame(spec, order, p);
                PlanNode.Frame frame = function.usesFrame() ? given : PlanNode.Frame.DEFAULT;
                call = ExpressionBinder.check(() -> new PlanNode.WindowCall(function, null, columns, w.ignoreNulls(),
                        -1, frame, outputName), p, null);
            }
            PlanNode.WindowCall checked = call;
            Schema schema = inputSchema();
            ExpressionBinder.check(() -> {
                checked.check(schema, order);
                return checked;
            }, p, null);
            Call c = new Call(partition, order, call);
            int index = calls.indexOf(c);
            if (index < 0) {
                calls.add(c);
                index = calls.size() - 1;
            }
            return new Expression.Column(PLACEHOLDER + index, call.type(schema));
        }

        private static void rejectWindows(List<SqlExpr> expressions, String message) {
            for (SqlExpr e : expressions) {
                SqlExpr.Window nested = SqlTrees.window(e);
                if (nested != null) {
                    throw new SqlException(message, nested.position());
                }
            }
        }

        /** The window a window function names, from the {@code WINDOW} clause, or the one it has. */
        private WindowSpec spec(WindowSpec over, Position p) {
            if (over.base() == null) {
                return over;
            }
            WindowSpec spec = named.get(SqlTrees.key(over.base()));
            if (spec == null || spec.base() != null) {
                throw new SqlException("window \"" + over.base() + "\" does not exist", p);
            }
            return spec;
        }

        /** The function of a name; as in DuckDB, {@code first} and {@code last} over a window take values. */
        private PlanNode.WindowFunction function(String name, Position p) {
            if (name.equals("first") || name.equals("last")) {
                return name.equals("first") ? PlanNode.WindowFunction.FIRST_VALUE : PlanNode.WindowFunction.LAST_VALUE;
            }
            try {
                return PlanNode.WindowFunction.of(name);
            } catch (IllegalArgumentException notAWindowFunction) {
                if (SqlTrees.isAggregate(name)) {
                    return PlanNode.WindowFunction.AGGREGATE;
                }
                if (context.functionNames().contains(name)) {
                    throw new SqlException(name + " is not an aggregate function", p);
                }
                throw new SqlException("Aggregate Function with name " + name + " does not exist!", p);
            }
        }

        /**
         * The arguments of a window function other than an aggregate, as
         * DuckDB takes them: a count of {@code ntile}, an offset of
         * {@code lag} and {@code lead} and a position of {@code nth_value}
         * as an integer up to {@code BIGINT}, a default of {@code lag} and {@code lead} as a
         * value of the type of the first argument.
         */
        private List<Integer> arguments(PlanNode.WindowFunction function, SqlExpr.FunctionCall f,
                ExpressionBinder arguments) {
            Position p = f.position();
            int n = f.arguments().size();
            String upper = function.name();
            if (n < function.minArguments() || n > function.maxArguments()) {
                if (n > function.maxArguments() && function.minArguments() != function.maxArguments()) {
                    throw new SqlException("Incorrect number of parameters for function " + function.sqlName(), p);
                }
                int needed = n < function.minArguments() ? function.minArguments() : function.maxArguments();
                throw new SqlException(upper + " needs " + needed + " parameter" + (needed == 1 ? "" : "s") + ", got "
                        + n, p);
            }
            List<Integer> columns = new ArrayList<>();
            DataType value = null;
            for (int i = 0; i < n; i++) {
                SqlExpr a = f.arguments().get(i);
                ExpressionBinder.Bound b = arguments.bound(a);
                Expression e;
                if (function == PlanNode.WindowFunction.NTILE || i == 1) {
                    DataType count = b.type().isIntegral() && b.type() != DataType.HUGEINT && !b.isNull() ? b.type()
                            : DataType.BIGINT;
                    e = as(arguments, b, count, function, a);
                } else if (i == 2) {
                    e = as(arguments, b, value, function, a);
                } else {
                    e = b.expression();
                    value = e.type();
                }
                columns.add(input(e));
            }
            return columns;
        }

        /** An argument as a value of a type, cast if it has another; a parameter takes the type. */
        private static Expression as(ExpressionBinder binder, ExpressionBinder.Bound b, DataType type,
                PlanNode.WindowFunction function, SqlExpr a) {
            if (b.isNull()) {
                return binder.typed(b, type).expression();
            }
            if (b.type().equals(type)) {
                return b.expression();
            }
            if (!Casts.allowed(b.type(), type)) {
                throw new SqlException("No function matches " + function.sqlName() + " with an argument of type "
                        + b.type(), a.position());
            }
            return b.kind() == ExpressionBinder.Kind.STRING
                    ? Literals.lazily(b.literal(), type, a.position(), binder.zone())
                    : new Expression.Cast(b.expression(), type);
        }

        /** The frame of a window, its default if it has none. */
        private PlanNode.Frame frame(WindowSpec spec, List<SortKey> order, Position p) {
            WindowSpec.Frame f = spec.frame();
            if (f == null) {
                return PlanNode.Frame.DEFAULT;
            }
            PlanNode.FrameUnit unit = PlanNode.FrameUnit.valueOf(f.unit().name());
            DataType key = null;
            if (unit == PlanNode.FrameUnit.RANGE && (f.start().offset() != null || f.end().offset() != null)) {
                if (order.size() != 1) {
                    throw new SqlException("RANGE frames must have only one ORDER BY expression", p);
                }
                key = inputs.get(order.get(0).column()).type();
            }
            PlanNode.FrameBound start = bound(f.start(), unit, key, p);
            PlanNode.FrameBound end = bound(f.end(), unit, key, p);
            PlanNode.FrameExclusion exclusion = PlanNode.FrameExclusion.valueOf(f.exclusion().name());
            return ExpressionBinder.check(() -> new PlanNode.Frame(unit, start, end, exclusion), p, null);
        }

        /**
         * A bound of a frame. Its offset is a constant: a number of rows or
         * groups, as a {@code BIGINT}; for {@code RANGE}, a number of the type
         * of a numeric order key if an integer fits it, or an interval, or a
         * number of days, over a {@code DATE} or {@code TIMESTAMP} key.
         */
        private PlanNode.FrameBound bound(WindowSpec.Bound b, PlanNode.FrameUnit unit, DataType key, Position p) {
            PlanNode.BoundKind kind = PlanNode.BoundKind.valueOf(b.kind().name());
            SqlExpr x = b.offset();
            if (x == null) {
                return PlanNode.FrameBound.of(kind);
            }
            boolean temporal = key == DataType.DATE || key == DataType.TIMESTAMP || key == DataType.TIMESTAMPTZ;
            if (x instanceof SqlExpr.Interval i) {
                if (!temporal) {
                    throw new SqlException("Cannot use an INTERVAL as the offset of a " + unit + " frame"
                            + (key == null ? "" : " over an order key of type " + key), x.position());
                }
                PlanNode.FrameBound bound = intervalBound(kind, i);
                if (key == DataType.TIMESTAMPTZ && !PlanNode.Frame.isTimeUnit(bound.unit())
                        && Long.valueOf(0).equals(bound.offset().value())) {
                    // No time at all, the same in every unit.
                    bound = new PlanNode.FrameBound(kind, bound.offset(), PlanNode.IntervalUnit.SECOND);
                }
                if (key == DataType.TIMESTAMPTZ && !PlanNode.Frame.isTimeUnit(bound.unit())) {
                    throw new SqlException("Not supported: an offset of days, months or years in a RANGE frame"
                            + " over an order key of type " + key, x.position());
                }
                return bound;
            }
            if (SqlTrees.containsNameOutsideAggregates(x)) {
                throw new SqlException("Not supported: a frame offset that is not a constant", x.position());
            }
            SqlTrees.noParameter(x, "as the offset of a frame");
            ExpressionBinder.Bound v = new ExpressionBinder(context, Scope.none(), Map.of(),
                    "Frame offsets cannot contain aggregates", null).bound(x);
            if (v.isNull()) {
                throw new SqlException("Not supported: NULL as the offset of a frame", x.position());
            }
            DataType type;
            PlanNode.IntervalUnit days = null;
            if (unit != PlanNode.FrameUnit.RANGE) {
                type = DataType.BIGINT;
            } else if (key == DataType.DATE && v.type().isIntegral()) {
                // A date minus a number is a date that many days earlier.
                type = DataType.BIGINT;
                days = PlanNode.IntervalUnit.DAY;
            } else if (key.isNumeric() && (v.type().isNumeric() || v.kind() == ExpressionBinder.Kind.STRING)) {
                // As in key - offset, an integer takes the type of the key if it fits, and a string converts.
                boolean adapts = v.kind() == ExpressionBinder.Kind.INTEGER && key.isIntegral()
                        && Literals.fits(v.literal(), key);
                type = adapts || v.kind() == ExpressionBinder.Kind.STRING ? key : v.type();
            } else {
                throw new SqlException("Cannot use an offset of type " + v.type() + " in a RANGE frame over an order"
                        + " key of type " + key, x.position());
            }
            if (!v.type().equals(type) && !Casts.allowed(v.type(), type)) {
                throw new SqlException("Cannot use an offset of type " + v.type() + " in a " + unit + " frame",
                        x.position());
            }
            Expression e = v.type().equals(type) ? v.expression()
                    : v.kind() == ExpressionBinder.Kind.STRING
                    ? Literals.lazily(v.literal(), type, x.position(), context.zone())
                    : new Expression.Cast(v.expression(), type);
            Object value = Literals.evaluate(e, x.position());
            Expression.Literal offset = new Expression.Literal(type, value);
            PlanNode.IntervalUnit u = days;
            return ExpressionBinder.check(() -> new PlanNode.FrameBound(kind, offset, u), p, null);
        }

        /**
         * The offset of an interval of a single unit: a number of months,
         * days, or of the largest unit of time that divides the microseconds.
         */
        private static PlanNode.FrameBound intervalBound(PlanNode.BoundKind kind, SqlExpr.Interval i) {
            Interval value;
            try {
                value = Interval.parse(i.text());
            } catch (ConversionException | ArithmeticException e) {
                throw new SqlException(e.getMessage(), i.position());
            }
            long micros = value.micros();
            long count;
            PlanNode.IntervalUnit unit;
            if (value.days() == 0 && micros == 0) {
                count = value.months();
                unit = PlanNode.IntervalUnit.MONTH;
            } else if (value.months() == 0 && micros == 0) {
                count = value.days();
                unit = PlanNode.IntervalUnit.DAY;
            } else if (value.months() == 0 && value.days() == 0) {
                unit = micros % Dates.MICROS_PER_HOUR == 0 ? PlanNode.IntervalUnit.HOUR
                        : micros % Dates.MICROS_PER_MINUTE == 0 ? PlanNode.IntervalUnit.MINUTE
                        : micros % Dates.MICROS_PER_SECOND == 0 ? PlanNode.IntervalUnit.SECOND
                        : micros % 1000 == 0 ? PlanNode.IntervalUnit.MILLISECOND : PlanNode.IntervalUnit.MICROSECOND;
                count = micros / switch (unit) {
                    case HOUR -> Dates.MICROS_PER_HOUR;
                    case MINUTE -> Dates.MICROS_PER_MINUTE;
                    case SECOND -> Dates.MICROS_PER_SECOND;
                    case MILLISECOND -> 1000;
                    default -> 1;
                };
            } else {
                throw new SqlException("Not supported: an interval of more than one unit as the offset of a frame",
                        i.position());
            }
            return new PlanNode.FrameBound(kind, new Expression.Literal(DataType.BIGINT, count), unit);
        }

        /**
         * Adds the windows over the rows, if there are calls, and makes the
         * outputs and the keys of the order read the columns of the calls.
         */
        PlanNode plan(PlanNode rows, List<Expression> outputs, List<Order> orders) {
            if (calls.isEmpty()) {
                return rows;
            }
            Schema schema = rows.schema();
            int width = schema.size();
            int[] mapping = new int[inputs.size()];
            List<NamedExpression> projection = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                projection.add(new NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
            }
            for (int i = 0; i < inputs.size(); i++) {
                Expression e = inputs.get(i);
                if (e instanceof Expression.Column c && c.index() < width) {
                    mapping[i] = c.index();
                } else {
                    mapping[i] = projection.size();
                    projection.add(new NamedExpression("#window" + (projection.size() - width), e));
                }
            }
            PlanNode plan = projection.size() == width ? rows : new PlanNode.Project(rows, projection);
            results = new int[calls.size()];
            boolean[] placed = new boolean[calls.size()];
            for (int i = 0; i < calls.size(); i++) {
                if (placed[i]) {
                    continue;
                }
                Call first = calls.get(i);
                int start = plan.schema().size();
                List<PlanNode.WindowCall> node = new ArrayList<>();
                for (int j = i; j < calls.size(); j++) {
                    Call c = calls.get(j);
                    if (!placed[j] && c.partition().equals(first.partition()) && c.order().equals(first.order())) {
                        placed[j] = true;
                        results[j] = start + node.size();
                        node.add(remap(c.call(), mapping));
                    }
                }
                List<Integer> partition = first.partition().stream().map(c -> mapping[c]).toList();
                plan = new PlanNode.Window(plan, partition, remapKeys(first.order(), mapping), node);
            }
            outputs.replaceAll(this::resolve);
            orders.replaceAll(o -> o.expression() == null ? o
                    : new Order(o.output(), resolve(o.expression()), o.ascending(), o.nullsFirst()));
            return plan;
        }

        /** An expression that reads the calls, made to read their columns in the plan. */
        Expression resolve(Expression e) {
            return Expressions.transform(e, x -> x instanceof Expression.Column c && c.index() >= PLACEHOLDER
                    && c.index() < PLACEHOLDER + calls.size()
                    ? new Expression.Column(results[c.index() - PLACEHOLDER], c.type()) : x);
        }

        private static PlanNode.WindowCall remap(PlanNode.WindowCall c, int[] mapping) {
            AggregateCall a = c.aggregate();
            if (a != null) {
                a = a.remap(i -> mapping[i]);
            }
            List<Integer> arguments = c.arguments().stream().map(i -> mapping[i]).toList();
            return new PlanNode.WindowCall(c.function(), a, arguments, c.ignoreNulls(),
                    c.filter() < 0 ? -1 : mapping[c.filter()], c.frame(), c.name());
        }

        private static List<SortKey> remapKeys(List<SortKey> keys, int[] mapping) {
            List<SortKey> result = new ArrayList<>();
            for (SortKey k : keys) {
                result.add(new SortKey(mapping[k.column()], k.ascending(), k.nullsFirst()));
            }
            return result;
        }
    }

    /**
     * Binds {@code QUALIFY}, which filters the rows after the windows and
     * needs a window function in the select list or in itself.
     */
    private static Expression qualify(QueryBody.Select s, Windowing windowing, ExpressionBinder binder) {
        if (s.qualify() == null) {
            return null;
        }
        Expression condition = binder.condition(s.qualify());
        if (windowing.isEmpty()) {
            throw new SqlException("at least one window function must appear in the SELECT column or QUALIFY clause",
                    s.qualify().position());
        }
        return condition;
    }

    /** The first window function of the select list, the order or {@code QUALIFY}, or {@code null}. */
    private static SqlExpr.Window firstWindow(QueryBody.Select s, List<Item> items, List<OrderItem> orderBy) {
        List<SqlExpr> expressions = new ArrayList<>();
        for (Item item : items) {
            if (item.expression() != null) {
                expressions.add(item.expression());
            }
        }
        for (OrderItem o : orderBy) {
            if (!o.isAll()) {
                expressions.add(o.expression());
            }
        }
        if (s.qualify() != null) {
            expressions.add(s.qualify());
        }
        for (SqlExpr e : expressions) {
            SqlExpr.Window w = SqlTrees.window(e);
            if (w != null) {
                return w;
            }
        }
        return null;
    }

    // ----------------------------------------------------------- aggregation

    /**
     * The aggregates of a select: the grouped expressions, and the calls of
     * aggregates with the columns they read, which a projection below the
     * aggregation computes after the grouped expressions.
     */
    private final class Aggregation {
        final List<Expression> groups;
        final List<Expression> arguments = new ArrayList<>();
        final List<AggregateCall> calls = new ArrayList<>();
        /** The groups that each call of {@code grouping} reads, by the index of its placeholder among the calls. */
        final Map<Integer, List<Integer>> groupings = new HashMap<>();
        final ExpressionBinder argumentBinder;

        Aggregation(List<Expression> groups, ExpressionBinder argumentBinder) {
            this.groups = groups;
            this.argumentBinder = argumentBinder;
        }

        /** The column of the projection below the aggregation that computes an expression. */
        int input(Expression e) {
            int g = groups.indexOf(e);
            if (g >= 0) {
                return g;
            }
            int a = arguments.indexOf(e);
            if (a < 0) {
                arguments.add(e);
                a = arguments.size() - 1;
            }
            return groups.size() + a;
        }

        Schema inputSchema() {
            List<DataType> types = new ArrayList<>();
            for (Expression e : groups) {
                types.add(e.type());
            }
            for (Expression e : arguments) {
                types.add(e.type());
            }
            return Schema.of(types.toArray(DataType[]::new));
        }

        /** Binds a call of an aggregate and returns its column in the output of the aggregation. */
        Expression call(SqlExpr.FunctionCall f) {
            String name = SqlTrees.key(f.name());
            String outputName = f.star() || name.equals("count") && f.arguments().isEmpty() ? countStar(f)
                    : SqlPrinter.print(f);
            AggregateCall call = aggregateCall(f, outputName, argumentBinder, this::input, this::inputSchema,
                    false);
            int index = calls.indexOf(call);
            if (index < 0) {
                calls.add(call);
                index = calls.size() - 1;
            }
            return new Expression.Column(groups.size() + index, call.type(inputSchema()));
        }

        /**
         * A call of {@code grouping(a, ...)}: a placeholder among the calls,
         * which each grouping set replaces by the number whose bits, the
         * first argument the highest, tell the groups the set leaves out.
         */
        Expression grouping(SqlExpr.FunctionCall f, ExpressionBinder groupBinder) {
            if (f.arguments().isEmpty() || f.distinct() || f.star() || !f.orderBy().isEmpty() || f.filter() != null) {
                throw new SqlException("GROUPING takes the expressions of GROUP BY", f.position());
            }
            List<Integer> of = new ArrayList<>();
            for (SqlExpr a : f.arguments()) {
                int g = groups.indexOf(groupBinder.bind(a));
                if (g < 0) {
                    throw new SqlException("GROUPING child \"" + SqlPrinter.print(a) + "\" must be a grouping column",
                            a.position());
                }
                of.add(g);
            }
            AggregateCall call = new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, groupingName(f));
            int index = -1;
            for (Map.Entry<Integer, List<Integer>> e : groupings.entrySet()) {
                if (calls.get(e.getKey()).equals(call) && e.getValue().equals(of)) {
                    index = e.getKey();
                }
            }
            if (index < 0) {
                calls.add(call);
                index = calls.size() - 1;
                groupings.put(index, of);
            }
            return new Expression.Column(groups.size() + index, DataType.BIGINT);
        }

        /** The projection below the aggregation, which computes the groups and what the calls read. */
        PlanNode below(PlanNode input) {
            if (groups.isEmpty() && arguments.isEmpty()) {
                return input;
            }
            List<NamedExpression> columns = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                columns.add(new NamedExpression("#group" + i, groups.get(i)));
            }
            for (int i = 0; i < arguments.size(); i++) {
                columns.add(new NamedExpression("#argument" + i, arguments.get(i)));
            }
            return new PlanNode.Project(input, columns);
        }

        /** The projection below the aggregation and the aggregation itself. */
        PlanNode plan(PlanNode input) {
            List<Integer> groupColumns = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                groupColumns.add(i);
            }
            return new PlanNode.Aggregate(below(input), groupColumns, calls);
        }

        /**
         * The union of an aggregation per grouping set, each over its own
         * input of the projection below, in the columns of
         * {@link #plan(PlanNode)}: a group the set leaves out is
         * {@code NULL}, and each call of {@code grouping} the bits of the
         * groups it reads that the set leaves out.
         *
         * @param sets the groups of each set, ascending
         */
        PlanNode plan(List<PlanNode> inputs, List<List<Integer>> sets) {
            List<PlanNode> parts = new ArrayList<>();
            for (int s = 0; s < sets.size(); s++) {
                List<Integer> set = sets.get(s);
                PlanNode aggregate = new PlanNode.Aggregate(inputs.get(s), set, calls);
                Schema schema = aggregate.schema();
                List<NamedExpression> columns = new ArrayList<>();
                for (int g = 0; g < groups.size(); g++) {
                    int at = set.indexOf(g);
                    DataType type = groups.get(g).type();
                    columns.add(new NamedExpression("#group" + g, at >= 0 ? new Expression.Column(at, type)
                            : new Expression.Literal(type, null)));
                }
                for (int c = 0; c < calls.size(); c++) {
                    List<Integer> of = groupings.get(c);
                    Expression value;
                    if (of == null) {
                        value = new Expression.Column(set.size() + c, schema.type(set.size() + c));
                    } else {
                        long bits = 0;
                        for (int g : of) {
                            bits = bits << 1 | (set.contains(g) ? 0 : 1);
                        }
                        value = new Expression.Literal(DataType.BIGINT, bits);
                    }
                    columns.add(new NamedExpression(calls.get(c).name(), value));
                }
                parts.add(new PlanNode.Project(aggregate, columns));
            }
            return parts.size() == 1 ? parts.get(0) : new PlanNode.Union(parts);
        }
    }

    /**
     * Binds a call of an aggregate over the columns of an input that a
     * projection computes.
     *
     * @param binder the binder of the expressions the call reads
     * @param input gives the column of the input that computes an expression
     * @param schema gives the schema of the input with the columns given so far
     * @param window whether the call is over a window, where DuckDB takes a literal in its {@code ORDER BY}
     */
    private AggregateCall aggregateCall(SqlExpr.FunctionCall f, String outputName, ExpressionBinder binder,
            ToIntFunction<Expression> input, Supplier<Schema> schema, boolean window) {
        String name = SqlTrees.key(f.name());
        Position p = f.position();
        // The filter is bound first, as a column of the input like the arguments.
        int filter = f.filter() == null ? -1 : input.applyAsInt(binder.condition(f.filter()));
        if (name.equals("count") && (f.star() || f.arguments().isEmpty())) {
            if (f.distinct() || !f.orderBy().isEmpty()) {
                throw new SqlException("Not supported: DISTINCT or ORDER BY in count(*)", p);
            }
            return new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, outputName).withFilter(filter);
        }
        if (f.star()) {
            throw new SqlException("Function \"" + name + "\" cannot be applied to a star expression", p);
        }
        AggregateFunction function = AggregateFunction.of(name);
        int arity = function == AggregateFunction.STRING_AGG ? 2 : 1;
        if (f.arguments().isEmpty() || f.arguments().size() > arity) {
            throw new SqlException("Wrong number of arguments for the aggregate " + name, p);
        }
        ExpressionBinder.Bound bound = binder.bound(f.arguments().get(0));
        // As in DuckDB, a bare NULL is a BOOLEAN for bool_and and bool_or.
        Expression argument = bound.isNull() && (function == AggregateFunction.BOOL_AND
                || function == AggregateFunction.BOOL_OR) ? new Expression.Literal(DataType.BOOLEAN, null)
                : bound.expression();
        Expression instant = null;
        if (function == AggregateFunction.STRING_AGG && Casts.mentions(argument.type(), DataType.Scalar.TIMESTAMPTZ)) {
            // The text of an instant depends on the time zone, which only a cast holds; it is ordered by the instant.
            instant = argument;
            argument = new Expression.Cast(argument, DataType.VARCHAR);
        }
        List<SortKey> order = new ArrayList<>();
        int column = input.applyAsInt(argument);
        for (OrderItem o : f.orderBy()) {
            // DuckDB takes list(x ORDER BY x) for any x, a literal too.
            if (!window && (!f.name().equals("list") || f.orderBy().size() != 1
                    || !SqlPrinter.print(o.expression()).equals(SqlPrinter.print(f.arguments().get(0))))) {
                checkOrderLiteral(o.expression());
            }
            Expression key = binder.bind(o.expression());
            int keyColumn = input.applyAsInt(key);
            if (f.distinct() && keyColumn != column && !key.equals(instant)) {
                throw new SqlException("In a DISTINCT aggregate, ORDER BY expressions must appear in the argument"
                        + " list", p);
            }
            order.add(new SortKey(keyColumn, o.ascending(), o.nullsFirst()));
        }
        AggregateCall call = ExpressionBinder.check(() -> new AggregateCall(function, column, f.distinct(),
                outputName).withFilter(filter), p, null);
        if (f.arguments().size() == 2) {
            call = call.withSeparator(separator(f.arguments().get(1), argument.type()));
        }
        AggregateCall unordered = call;
        AggregateCall checked = ExpressionBinder.check(() -> unordered.withOrderBy(order), p, null);
        Schema columns = schema.get();
        return ExpressionBinder.check(() -> {
            checked.check(columns);
            return checked;
        }, p, "No function matches " + name + "(" + argument.type() + ")");
    }

    /**
     * Binds a select that aggregates. With grouping sets, or a call of
     * {@code grouping}, the aggregation is the union of one per set, see
     * {@link Aggregation#plan(List, List)}; the rows they read run once as a
     * common table if they compute something, see {@link #computes},
     * otherwise each set reads them bound anew.
     *
     * @param rows binds the rows of {@code FROM} and {@code WHERE} again
     */
    private PlanNode aggregate(QueryBody.Select s, PlanNode input, Supplier<PlanNode> rows, Scope scope,
            List<Item> items, Map<String, SqlExpr> aliases, Map<String, Integer> aliasOutputs,
            List<OrderItem> orderBy, List<String> names, Map<String, Cte> ctes, Correlation correlation,
            Correlated correlated, Windowing windowing) {
        ExpressionBinder groupBinder = new ExpressionBinder(context, scope, aliases,
                "GROUP BY clause cannot contain aggregates!", null);
        List<Expression> groups = new ArrayList<>();
        QueryBody.GroupBy groupBy = s.groupBy();
        if (groupBy != null && groupBy.all()) {
            for (Item item : items) {
                if (item.column() != null) {
                    addGroup(groups, item.column());
                } else if (!SqlTrees.containsAggregate(item.expression())) {
                    if (SqlTrees.containsNameOutsideAggregates(item.expression())) {
                        if (SqlTrees.window(item.expression()) != null) {
                            throw new SqlException("Cannot group on a window clause", item.position());
                        }
                        addGroup(groups, groupBinder.bind(item.expression()));
                    }
                } else if (SqlTrees.containsNameOutsideAggregates(item.expression())) {
                    throw new SqlException("Cannot mix aggregates with non-aggregated columns!", item.position());
                }
            }
        }
        // The group of each expression of GROUP BY, which the grouping sets index.
        List<Integer> groupOf = new ArrayList<>();
        if (groupBy != null && !groupBy.all()) {
            for (SqlExpr g : groupBy.expressions()) {
                if (g instanceof SqlExpr.Parameter p) {
                    throw new SqlException("Not supported: a parameter as a term of GROUP BY", p.position());
                }
                long position = SqlTrees.signedPosition(g);
                Expression bound;
                if (position != Long.MIN_VALUE) {
                    if (position < 1 || position > items.size()) {
                        throw new SqlException("GROUP BY term out of range - should be between 1 and " + items.size(),
                                g.position());
                    }
                    Item item = items.get((int) position - 1);
                    bound = item.column() != null ? item.column() : groupBinder.bind(item.expression());
                } else {
                    bound = groupBinder.bind(g);
                }
                addGroup(groups, bound);
                groupOf.add(groups.indexOf(bound));
            }
        }
        // A correlated subquery groups by its keys too; without GROUP BY, its value is computed after the join.
        boolean deferred = correlated != null && groupBy == null;
        List<Integer> correlatedGroups = new ArrayList<>();
        if (correlated != null) {
            for (Conjunct c : correlated.conditions()) {
                if (side(c.bound(), Correlation.PLACEHOLDER) != Side.RIGHT) {
                    throw new SqlException("Not supported: a correlated condition of an aggregating subquery that is"
                            + " not an equality", c.syntax().position());
                }
            }
            if (deferred && s.having() != null) {
                throw new SqlException("Not supported: HAVING in a correlated subquery without GROUP BY",
                        s.having().position());
            }
            if (deferred && correlation.kind() != Correlation.Kind.SCALAR) {
                throw new SqlException("Not supported: EXISTS or IN with a correlated subquery that aggregates"
                        + " without GROUP BY", s.position());
            }
            for (Expression e : correlated.inner()) {
                addGroup(groups, e);
                correlatedGroups.add(groups.indexOf(e));
            }
        }
        ExpressionBinder argumentBinder = new ExpressionBinder(context, scope, aliases,
                "aggregate function calls cannot be nested", null);
        Aggregation aggregation = new Aggregation(groups, argumentBinder);
        boolean bySets = groupBy != null && !groupBy.sets().isEmpty();
        ExpressionBinder.Hook hook = e -> {
            if (SqlTrees.isAggregate(e)) {
                return aggregation.call((SqlExpr.FunctionCall) e);
            }
            if (isGrouping(e)) {
                return aggregation.grouping((SqlExpr.FunctionCall) e, groupBinder);
            }
            if (e instanceof SqlExpr.Identifier id) {
                Scope.Resolved r = scope.resolve(id.parts(), id.position());
                if (r == null) {
                    return null;
                }
                if (r.fields().isEmpty()) {
                    return grouped(groups, r.column().expression(), String.join(".", id.parts()), id.position());
                }
                // A field is a group itself, or a field of a grouped column.
                int g = groups.indexOf(groupBinder.bind(e));
                if (g >= 0) {
                    return new Expression.Column(g, groups.get(g).type());
                }
                Expression column = grouped(groups, r.column().expression(), r.column().name(), id.position());
                return groupBinder.fields(ExpressionBinder.Bound.typed(column), r.column().name(), r.fields(),
                        id.position()).expression();
            }
            // With grouping sets, a constant that is a group is NULL where a set leaves it out, as in DuckDB.
            if ((!(e instanceof SqlExpr.Literal) || bySets) && !SqlTrees.containsAggregate(e)) {
                try {
                    int g = groups.indexOf(groupBinder.bind(e));
                    if (g >= 0) {
                        return new Expression.Column(g, groups.get(g).type());
                    }
                } catch (SqlException notAGroup) {
                    // Bound below, node by node, where the error names the column.
                }
            }
            return null;
        };
        List<Expression> outputs = new ArrayList<>();
        Map<String, SqlExpr> earlier = new HashMap<>();
        BitSet untyped = new BitSet();
        Unnesting unnesting = new Unnesting();
        // The subqueries are joined to the groups: their values are the same for every row of a group.
        SubqueryJoiner subqueries = new SubqueryJoiner((q, c) -> query(q, ctes, c),
                new ExpressionBinder(context, scope, Map.of(), null, hook));
        // The expressions a window function reads are those of the groups and the aggregates.
        ExpressionBinder.Hook itemHook = windowing.hook(new ExpressionBinder(context, scope, earlier, null, hook),
                unnesting.hook(new ExpressionBinder(context, scope, earlier, null, hook), hook));
        for (Item item : items) {
            if (item.column() != null) {
                outputs.add(grouped(groups, item.column(), item.name(), item.position()));
            } else {
                ExpressionBinder.Bound b = new ExpressionBinder(context, scope, earlier, null, itemHook, subqueries)
                        .bound(item.expression());
                untyped.set(outputs.size(), b.isNull());
                outputs.add(b.expression());
            }
            if (item.alias() != null) {
                earlier.putIfAbsent(SqlTrees.key(item.alias()), item.expression());
            }
        }
        SqlExpr.Window inHaving = s.having() == null ? null : SqlTrees.window(s.having());
        if (inHaving != null) {
            throw new SqlException("HAVING clause cannot contain window functions!", inHaving.position());
        }
        Expression having = s.having() != null
                ? new ExpressionBinder(context, scope, aliases, null, hook, subqueries).condition(s.having()) : null;
        ExpressionBinder post = new ExpressionBinder(context, scope, aliases, null, hook);
        Expression qualify = qualify(s, windowing, new ExpressionBinder(context, scope, aliases, null,
                windowing.hook(post, hook)));
        ExpressionBinder orderBinder = new ExpressionBinder(context, scope, aliases, null,
                windowing.hook(post, unnesting.hook(post, hook)));
        List<Order> orders = orders(orderBy, outputs, aliasOutputs, orderBinder);
        int ordered = orders.size();
        orders.addAll(orders(distinctOn(s, correlated), outputs, aliasOutputs, orderBinder));
        // Each set by the groups it reads; a grouping without sets has the one set of all groups.
        List<List<Integer>> sets = null;
        if (groupBy != null && !groupBy.sets().isEmpty()) {
            sets = new ArrayList<>();
            for (List<Integer> set : groupBy.sets()) {
                sets.add(set.stream().map(groupOf::get).distinct().sorted().toList());
            }
        } else if (!aggregation.groupings.isEmpty()) {
            sets = List.of(java.util.stream.IntStream.range(0, groups.size()).boxed().toList());
        }
        if (sets != null && correlated != null) {
            throw new SqlException("Not supported: grouping sets or GROUPING in a correlated subquery", s.position());
        }
        int shared = -1;
        PlanNode below = null;
        PlanNode aggregated;
        if (sets == null) {
            aggregated = aggregation.plan(input);
        } else {
            below = aggregation.below(input);
            List<PlanNode> inputs = new ArrayList<>();
            if (sets.size() > 1 && correlation == null && computes(below)) {
                shared = ++commonTables;
                for (int i = 0; i < sets.size(); i++) {
                    inputs.add(PlanNode.CommonTableScan.all(shared, GROUPING_SETS, below));
                }
            } else {
                inputs.add(below);
                for (int i = 1; i < sets.size(); i++) {
                    inputs.add(aggregation.below(rows.get()));
                }
            }
            aggregated = aggregation.plan(inputs, sets);
        }
        PlanNode plan = subqueries.attach(aggregated);
        outputs.replaceAll(subqueries::resolve);
        if (having != null) {
            plan = new PlanNode.Filter(plan, subqueries.resolve(having));
        }
        plan = windowing.plan(plan, outputs, orders);
        if (qualify != null) {
            plan = new PlanNode.Filter(plan, windowing.resolve(qualify));
        }
        if (deferred) {
            if (!subqueries.isEmpty() || unnesting.plan(plan, outputs, orders) != plan) {
                throw new SqlException("Not supported: a subquery or UNNEST in the select list of a correlated"
                        + " subquery without GROUP BY", s.position());
            }
            int width = groups.size();
            Expression value = Expressions.transform(outputs.get(0), x -> x instanceof Expression.Column c
                    && c.index() >= width && isCount(aggregation.calls.get(c.index() - width))
                    ? new Expression.Coalesce(List.of(c, new Expression.Literal(c.type(), 0L))) : x);
            correlation.join(new Correlation.Join(correlatedGroups, correlated.outer(), correlated.condition(), value,
                    outputs.size()));
            return plan;
        }
        plan = unnesting.plan(plan, outputs, orders);
        if (correlated != null) {
            int n = outputs.size();
            List<Integer> keys = new ArrayList<>();
            for (int g : correlatedGroups) {
                keys.add(correlatedOutput(outputs, names, n, new Expression.Column(g, groups.get(g).type())));
            }
            correlation.join(new Correlation.Join(keys, correlated.outer(), correlated.condition(), null, n));
        }
        PlanNode result = finish(plan, outputs, names, s.distinct(), orders.subList(0, ordered),
                orders.subList(ordered, orders.size()));
        if (shared >= 0) {
            result = new PlanNode.CommonTable(shared, GROUPING_SETS, below, result);
        }
        untypedNulls.put(result, untyped);
        return result;
    }

    /** The name of the common table of the rows that grouping sets read. */
    private static final String GROUPING_SETS = "#groupingsets";

    /** Tells whether an expression is a call of {@code grouping} or {@code grouping_id}. */
    private static boolean isGrouping(SqlExpr e) {
        return e instanceof SqlExpr.FunctionCall f && (SqlTrees.key(f.name()).equals("grouping")
                || SqlTrees.key(f.name()).equals("grouping_id"));
    }

    /** DuckDB's name of a call of {@code grouping} or {@code grouping_id}. */
    private static String groupingName(SqlExpr.FunctionCall f) {
        return "GROUPING(" + String.join(", ", f.arguments().stream().map(SqlPrinter::print).toList()) + ")";
    }

    private static boolean isCount(AggregateCall call) {
        return call.function() == AggregateFunction.COUNT || call.function() == AggregateFunction.COUNT_STAR;
    }

    private static void addGroup(List<Expression> groups, Expression e) {
        if (!groups.contains(e)) {
            groups.add(e);
        }
    }

    /** The column of the aggregation for a grouped expression of the input. */
    private static Expression grouped(List<Expression> groups, Expression e, String name, Position position) {
        int g = groups.indexOf(e);
        if (g < 0) {
            throw new SqlException("column \"" + name + "\" must appear in the GROUP BY clause or must be part of an"
                    + " aggregate function. Either add it to the GROUP BY list, or use \"ANY_VALUE(" + name
                    + ")\" if the exact value of \"" + name + "\" is not important.", position);
        }
        return new Expression.Column(g, e.type());
    }
}
