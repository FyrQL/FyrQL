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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.StructPack;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Renders a plan as SQL in a dialect. Every node becomes a {@code SELECT}
 * over its inputs as subqueries, except a limit over a sort, which share
 * one, so that the order stays in the statement that is limited: MySQL may
 * ignore the {@code ORDER BY} of a derived table. The columns are
 * positional, named {@code c0}, {@code c1}, ... at every level, so that
 * column references of the plan map to names directly. Literals carry their
 * exact type, and a sum of integers is cast to {@code HUGEINT}, the type
 * FyrQL gives it. The result of the SQL, run in the dialect's engine, is the
 * result of the plan, as far as the dialect {@linkplain Dialect#supports
 * evaluates} the plan's expressions as FyrQL does; the dialect spells the
 * forms that differ.
 */
public final class SqlRenderer {

    private final Dialect dialect;
    private final Function<TableRef, String> tableNames;
    private int aliases;

    /**
     * @param dialect the dialect
     * @param tableNames how a table of the plan is called in the SQL, e.g.
     *        {@code "pg"."public"."orders"}, already quoted as needed
     */
    public SqlRenderer(Dialect dialect, Function<TableRef, String> tableNames) {
        this.dialect = dialect;
        this.tableNames = tableNames;
    }

    /** Renders a plan as one SQL statement. */
    public String render(PlanNode plan) {
        aliases = 0;
        return select(plan);
    }

    /** The positional name of a column. */
    public static String column(int index) {
        return "c" + index;
    }

    private String alias() {
        return "t" + aliases++;
    }

    private String select(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> scan(s);
            case PlanNode.Remote r -> select(r.logical());
            case PlanNode.Filter f -> {
                String t = alias();
                yield "SELECT * FROM (" + select(f.input()) + ") " + t + " WHERE " + expression(f.condition(), t);
            }
            case PlanNode.Project p -> {
                String t = alias();
                StringBuilder b = new StringBuilder("SELECT ");
                for (int i = 0; i < p.columns().size(); i++) {
                    b.append(i > 0 ? ", " : "").append(expression(p.columns().get(i).expression(), t)).append(" AS ")
                            .append(column(i));
                }
                yield b.append(" FROM (").append(select(p.input())).append(") ").append(t).toString();
            }
            case PlanNode.Join j -> join(j);
            case PlanNode.Aggregate a -> aggregate(a);
            case PlanNode.Sort s -> sort(s);
            case PlanNode.Limit l when l.input() instanceof PlanNode.Sort s ->
                    // The order stays in the statement that is limited: MySQL may ignore that of a derived table.
                    sort(s) + " " + dialect.limit(l.count(), l.offset());
            case PlanNode.Limit l -> {
                String t = alias();
                yield "SELECT * FROM (" + select(l.input()) + ") " + t + " " + dialect.limit(l.count(), l.offset());
            }
            case PlanNode.Union u -> {
                String t = alias();
                StringBuilder b = new StringBuilder("SELECT * FROM (");
                for (int i = 0; i < u.inputs().size(); i++) {
                    b.append(i > 0 ? " UNION ALL " : "").append(operand(u.inputs().get(i)));
                }
                yield b.append(") ").append(t).toString();
            }
            case PlanNode.Unnest u -> {
                String t = alias();
                int width = u.input().schema().size();
                StringBuilder b = new StringBuilder("SELECT ");
                for (int i = 0; i < width; i++) {
                    b.append(t).append('.').append(column(i)).append(" AS ").append(column(i)).append(", ");
                }
                yield b.append(dialect.unnest(expression(u.list(), t))).append(" AS ").append(column(width))
                        .append(" FROM (").append(select(u.input())).append(") ").append(t).toString();
            }
            case PlanNode.Window w -> window(w);
            case PlanNode.Values v -> values(v);
            case PlanNode.SetOperation s -> {
                String t = alias();
                yield "SELECT * FROM (" + operand(s.left()) + " " + dialect.setOperation(s.kind(), s.all()) + " "
                        + operand(s.right()) + ") " + t;
            }
        };
    }

    private String sort(PlanNode.Sort s) {
        String t = alias();
        StringBuilder b = new StringBuilder("SELECT * FROM (").append(select(s.input())).append(") ").append(t)
                .append(" ORDER BY ");
        for (int i = 0; i < s.keys().size(); i++) {
            b.append(i > 0 ? ", " : "").append(orderKey(s.keys().get(i), t));
        }
        return b.toString();
    }

    /**
     * An operand of a set operation, as one {@code SELECT} without
     * parentheses: a sort or a limit goes into a subquery. JSqlParser fails
     * on a long first operand in parentheses.
     */
    private String operand(PlanNode node) {
        String sql = select(node);
        return node instanceof PlanNode.Sort || node instanceof PlanNode.Limit
                ? "SELECT * FROM (" + sql + ") " + alias() : sql;
    }

    private String values(PlanNode.Values v) {
        Schema schema = v.schema();
        StringBuilder b = new StringBuilder("SELECT ");
        if (schema.size() == 0) {
            // SQL has no select list without columns; a projection above reads none of this placeholder.
            if (v.rows().isEmpty()) {
                return "SELECT NULL AS " + column(0) + " WHERE false";
            }
            String t = alias();
            b.append("NULL AS ").append(column(0)).append(" FROM (VALUES ");
            for (int r = 0; r < v.rows().size(); r++) {
                b.append(r > 0 ? ", " : "").append(dialect.valuesRow(List.of("NULL")));
            }
            return b.append(") ").append(dialect.valuesAlias(t, 1)).toString();
        }
        if (v.rows().isEmpty()) {
            for (int i = 0; i < schema.size(); i++) {
                b.append(i > 0 ? ", " : "").append(dialect.literal(null, schema.type(i))).append(" AS ").append(column(i));
            }
            return b.append(" WHERE false").toString();
        }
        String t = alias();
        for (int i = 0; i < schema.size(); i++) {
            b.append(i > 0 ? ", " : "").append(dialect.valuesColumn(t, i)).append(" AS ").append(column(i));
        }
        b.append(" FROM (VALUES ");
        for (int r = 0; r < v.rows().size(); r++) {
            List<String> literals = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                literals.add(dialect.literal(v.rows().get(r)[c], schema.type(c)));
            }
            b.append(r > 0 ? ", " : "").append(dialect.valuesRow(literals));
        }
        return b.append(") ").append(dialect.valuesAlias(t, schema.size())).toString();
    }

    /**
     * A scan; the fields it reads and what its connector has taken over are
     * rendered as the plan above a plain scan.
     */
    private String scan(PlanNode.Scan s) {
        if (!s.pushed().isEmpty() || s.readsFields()) {
            return select(s.logical());
        }
        StringBuilder b = new StringBuilder("SELECT ");
        for (int i = 0; i < s.columns().size(); i++) {
            b.append(i > 0 ? ", " : "").append(dialect.quoteIdentifier(s.tableSchema().name(s.columns().get(i))))
                    .append(" AS ").append(column(i));
        }
        return b.append(" FROM ").append(tableNames.apply(s.table())).toString();
    }

    /**
     * Renders a scan with what its source takes over as one flat statement
     * over the table, for the connector that sends it to the source:
     * {@code SELECT t."a", t."b" FROM table t WHERE ... ORDER BY ... LIMIT n}.
     * The columns of the result are those of the scan, in order, under
     * their names in the table; a scan of no columns selects a constant,
     * which the reader skips. Unlike {@link #render}, which nests a
     * {@code SELECT} per node, the statement reads the table itself, as a
     * database plans it best.
     * <p>
     * A scan with an aggregation selects its groups and aggregates instead,
     * {@code SELECT t."a", count(*) FROM table t WHERE ... GROUP BY t."a"};
     * a sum of integers is cast to {@code HUGEINT}, the type FyrQL gives it,
     * so that the database neither overflows nor answers in a type of its
     * own choosing.
     *
     * @throws IllegalArgumentException if the scan carries an aggregation
     *         together with an order or a limit, or an aggregation without
     *         groups and calls, or reads a field inside a column, or the
     *         dialect cannot write a part of it
     */
    public String renderScan(PlanNode.Scan s) {
        if (s.readsFields()) {
            throw new IllegalArgumentException("A scan of fields inside columns is not rendered flat");
        }
        PlanNode.Pushed pushed = s.pushed();
        String t = "t";
        IntFunction<String> columns = i -> t + "." + dialect.quoteIdentifier(s.tableSchema().name(s.columns().get(i)));
        StringBuilder b = new StringBuilder("SELECT ");
        if (pushed.aggregation().isPresent()) {
            if (!pushed.order().isEmpty() || pushed.limit().isPresent()) {
                throw new IllegalArgumentException("A scan with an aggregation and an order or a limit is not "
                        + "rendered flat");
            }
            b.append(aggregation(pushed.aggregation().get(), s.columnSchema(), columns));
        } else if (s.columns().isEmpty()) {
            b.append('1');
        }
        for (int i = 0; pushed.aggregation().isEmpty() && i < s.columns().size(); i++) {
            b.append(i > 0 ? ", " : "").append(columns.apply(i));
        }
        b.append(" FROM ").append(tableNames.apply(s.table())).append(' ').append(t);
        if (!pushed.filters().isEmpty()) {
            b.append(" WHERE ").append(expression(pushed.condition(), columns));
        }
        List<Integer> groups = pushed.aggregation().map(PlanNode.Aggregation::groupColumns).orElse(List.of());
        for (int i = 0; i < groups.size(); i++) {
            b.append(i == 0 ? " GROUP BY " : ", ").append(columns.apply(groups.get(i)));
        }
        for (int i = 0; i < pushed.order().size(); i++) {
            PlanNode.SortKey k = pushed.order().get(i);
            b.append(i == 0 ? " ORDER BY " : ", ")
                    .append(dialect.orderKey(columns.apply(k.column()), k.ascending(), k.nullsFirst()));
        }
        if (pushed.limit().isPresent()) {
            b.append(' ').append(dialect.limit(pushed.limit().getAsLong(), 0));
        }
        return b.toString();
    }

    /** The select list of an aggregation of a flat scan: the groups, then the calls. */
    private String aggregation(PlanNode.Aggregation a, Schema input, IntFunction<String> columns) {
        if (a.groupColumns().isEmpty() && a.calls().isEmpty()) {
            throw new IllegalArgumentException("An aggregation without groups and calls is not rendered flat");
        }
        List<String> items = new ArrayList<>();
        for (int g : a.groupColumns()) {
            items.add(columns.apply(g));
        }
        for (PlanNode.AggregateCall call : a.calls()) {
            String argument = call.function() == PlanNode.AggregateFunction.COUNT_STAR ? "*"
                    : columns.apply(call.column());
            String separator = call.function() == PlanNode.AggregateFunction.STRING_AGG
                    ? dialect.literal(call.separator(), DataType.VARCHAR) : null;
            List<String> orderKeys = new ArrayList<>();
            for (PlanNode.SortKey k : call.orderBy()) {
                orderKeys.add(dialect.orderKey(columns.apply(k.column()), k.ascending(), k.nullsFirst()));
            }
            String rendered = dialect.aggregate(call.function(), call.distinct(), argument, separator, orderKeys);
            if (call.function() == PlanNode.AggregateFunction.SUM && call.type(input) == DataType.HUGEINT) {
                rendered = dialect.cast(rendered, DataType.HUGEINT, false);
            }
            items.add(rendered);
        }
        return String.join(", ", items);
    }

    private String join(PlanNode.Join j) {
        if (!dialect.supportsJoin(j.type())) {
            throw new IllegalArgumentException(dialect.name() + " cannot render a " + j.type() + " join");
        }
        String l = alias();
        String r = alias();
        StringBuilder b = new StringBuilder("SELECT ");
        int width = j.left().schema().size();
        boolean exists = (j.type() == PlanNode.JoinType.SEMI || j.type() == PlanNode.JoinType.ANTI)
                && !dialect.hasSemiJoinKeywords();
        for (int i = 0; i < width; i++) {
            b.append(i > 0 ? ", " : "").append(l).append('.').append(column(i)).append(" AS ").append(column(i));
        }
        if (j.type().keepsRight()) {
            for (int i = 0; i < j.right().schema().size(); i++) {
                b.append(", ").append(r).append('.').append(column(i)).append(" AS ").append(column(width + i));
            }
        }
        b.append(" FROM (").append(select(j.left())).append(") ").append(l);
        String right = " (" + select(j.right()) + ") " + r;
        List<String> on = new ArrayList<>();
        for (int i = 0; i < j.leftKeys().size(); i++) {
            on.add(l + "." + column(j.leftKeys().get(i)) + " = " + r + "." + column(j.rightKeys().get(i)));
        }
        if (j.condition() != null) {
            on.add(expression(j.condition(), (IntFunction<String>) i -> i < width ? l + "." + column(i)
                    : r + "." + column(i - width)));
        }
        if (exists) {
            // A right row that matches is one for which the keys are equal and the condition is true.
            b.append(" WHERE ").append(j.type() == PlanNode.JoinType.ANTI ? "NOT " : "").append("EXISTS (SELECT 1 FROM")
                    .append(right).append(on.isEmpty() ? "" : " WHERE " + String.join(" AND ", on)).append(')');
        } else {
            b.append(' ').append(dialect.joinKeyword(j.type())).append(right);
            if (j.type() != PlanNode.JoinType.CROSS) {
                b.append(" ON ").append(on.isEmpty() ? dialect.literal(true, DataType.BOOLEAN)
                        : String.join(" AND ", on));
            }
        }
        return b.toString();
    }

    private String aggregate(PlanNode.Aggregate a) {
        String t = alias();
        StringBuilder b = new StringBuilder("SELECT ");
        List<String> groups = new ArrayList<>();
        int out = 0;
        for (int g : a.groupColumns()) {
            String ref = t + "." + column(g);
            groups.add(ref);
            b.append(out > 0 ? ", " : "").append(ref).append(" AS ").append(column(out++));
        }
        for (PlanNode.AggregateCall call : a.aggregates()) {
            String argument = call.function() == PlanNode.AggregateFunction.COUNT_STAR ? "*"
                    : t + "." + column(call.column());
            String separator = call.function() == PlanNode.AggregateFunction.STRING_AGG
                    ? dialect.literal(call.separator(), DataType.VARCHAR) : null;
            List<String> orderKeys = new ArrayList<>();
            for (PlanNode.SortKey k : call.orderBy()) {
                orderKeys.add(orderKey(k, t));
            }
            String rendered = dialect.aggregate(call.function(), call.distinct(), argument, separator, orderKeys);
            if (call.function() == PlanNode.AggregateFunction.SUM && call.type(a.input().schema()) == DataType.HUGEINT) {
                rendered = dialect.cast(rendered, DataType.HUGEINT, false);
            }
            b.append(out > 0 ? ", " : "").append(rendered).append(" AS ").append(column(out++));
        }
        b.append(" FROM (").append(select(a.input())).append(") ").append(t);
        if (!groups.isEmpty()) {
            b.append(" GROUP BY ").append(String.join(", ", groups));
        }
        return b.toString();
    }

    /**
     * A window: the columns of the input, then one call per column, each
     * with the partition, the order and, if the function uses one, the
     * whole frame; a sum of integers is cast to {@code HUGEINT}.
     */
    private String window(PlanNode.Window w) {
        String t = alias();
        Schema input = w.input().schema();
        StringBuilder b = new StringBuilder("SELECT ");
        for (int i = 0; i < input.size(); i++) {
            b.append(t).append('.').append(column(i)).append(" AS ").append(column(i)).append(", ");
        }
        List<String> partition = new ArrayList<>();
        for (int c : w.partition()) {
            partition.add(t + "." + column(c));
        }
        List<String> order = new ArrayList<>();
        for (PlanNode.SortKey k : w.order()) {
            order.add(orderKey(k, t));
        }
        for (int i = 0; i < w.calls().size(); i++) {
            PlanNode.WindowCall call = w.calls().get(i);
            String rendered;
            if (call.aggregate() != null) {
                PlanNode.AggregateCall a = call.aggregate();
                List<String> orderKeys = new ArrayList<>();
                for (PlanNode.SortKey k : a.orderBy()) {
                    orderKeys.add(orderKey(k, t));
                }
                rendered = dialect.aggregate(a.function(), a.distinct(),
                        a.function() == PlanNode.AggregateFunction.COUNT_STAR ? "*" : t + "." + column(a.column()),
                        a.function() == PlanNode.AggregateFunction.STRING_AGG
                                ? dialect.literal(a.separator(), DataType.VARCHAR) : null, orderKeys);
            } else {
                List<String> arguments = new ArrayList<>();
                for (int c : call.arguments()) {
                    arguments.add(t + "." + column(c));
                }
                rendered = dialect.windowFunction(call.function(), arguments, call.ignoreNulls());
            }
            rendered = dialect.window(rendered, call.filter() < 0 ? null : t + "." + column(call.filter()), partition,
                    order, call.function().usesFrame() ? dialect.frame(call.frame()) : null);
            if (call.aggregate() != null && call.aggregate().function() == PlanNode.AggregateFunction.SUM
                    && call.type(input) == DataType.HUGEINT) {
                rendered = dialect.cast(rendered, DataType.HUGEINT, false);
            }
            b.append(i > 0 ? ", " : "").append(rendered).append(" AS ").append(column(input.size() + i));
        }
        return b.append(" FROM (").append(select(w.input())).append(") ").append(t).toString();
    }

    private String orderKey(PlanNode.SortKey k, String input) {
        return dialect.orderKey(input + "." + column(k.column()), k.ascending(), k.nullsFirst());
    }

    /**
     * Renders an expression over the columns of an input alias; without an
     * alias, a column is written as {@code #index}, which is no SQL.
     */
    public String expression(Expression e, String input) {
        return expression(e, (IntFunction<String>) i -> input == null ? "#" + i : input + "." + column(i));
    }

    /** Renders an expression whose columns are written as the function says. */
    public String expression(Expression e, IntFunction<String> columns) {
        return switch (e) {
            case Expression.Literal l -> dialect.literal(l.value(), l.type());
            case Expression.Column c -> columns.apply(c.index());
            case Expression.Arithmetic a -> dialect.arithmetic(a.op(), expression(a.left(), columns),
                    expression(a.right(), columns));
            case Expression.Negate n -> "(-" + expression(n.operand(), columns) + ")";
            case Expression.Comparison c -> "(" + expression(c.left(), columns) + " " + c.op().symbol() + " "
                    + expression(c.right(), columns) + ")";
            case Expression.And a -> "(" + expression(a.left(), columns) + " AND " + expression(a.right(), columns) + ")";
            case Expression.Or o -> "(" + expression(o.left(), columns) + " OR " + expression(o.right(), columns) + ")";
            case Expression.Not n -> "(NOT " + expression(n.operand(), columns) + ")";
            case Expression.IsNull n -> "(" + expression(n.operand(), columns) + (n.negated() ? " IS NOT NULL)" : " IS NULL)");
            case Expression.Between b -> "(" + expression(b.value(), columns) + " BETWEEN " + expression(b.low(), columns)
                    + " AND " + expression(b.high(), columns) + ")";
            case Expression.In in -> {
                StringBuilder b = new StringBuilder("(").append(expression(in.value(), columns)).append(" IN (");
                for (int i = 0; i < in.list().size(); i++) {
                    b.append(i > 0 ? ", " : "").append(expression(in.list().get(i), columns));
                }
                yield b.append("))").toString();
            }
            case Expression.Case c -> {
                StringBuilder b = new StringBuilder("(CASE");
                for (Expression.When w : c.whens()) {
                    b.append(" WHEN ").append(expression(w.condition(), columns)).append(" THEN ")
                            .append(expression(w.result(), columns));
                }
                yield b.append(" ELSE ").append(expression(c.otherwise(), columns)).append(" END)").toString();
            }
            case Expression.If i -> "(CASE WHEN " + expression(i.condition(), columns) + " THEN "
                    + expression(i.whenTrue(), columns) + " ELSE " + expression(i.whenFalse(), columns) + " END)";
            case Expression.Coalesce c -> {
                List<String> operands = new ArrayList<>();
                for (Expression operand : c.operands()) {
                    operands.add(expression(operand, columns));
                }
                yield dialect.coalesce(operands);
            }
            case Expression.Cast c -> dialect.cast(expression(c.operand(), columns), c.target(), c.safe());
            case Expression.Like l -> dialect.like(expression(l.string(), columns), expression(l.pattern(), columns),
                    l.caseInsensitive());
            case Expression.IsDistinct d -> dialect.isDistinct(expression(d.left(), columns),
                    expression(d.right(), columns), d.negated());
            case Expression.Call c -> {
                if (c.function().name().equals("||")) {
                    yield dialect.concat(expression(c.arguments().get(0), columns),
                            expression(c.arguments().get(1), columns));
                }
                if (c.function() instanceof StructPack pack) {
                    // The names of the fields are no arguments of the call.
                    StringBuilder b = new StringBuilder("{");
                    for (int i = 0; i < pack.names().size(); i++) {
                        b.append(i > 0 ? ", " : "").append(dialect.literal(pack.names().get(i), DataType.VARCHAR))
                                .append(": ").append(expression(c.arguments().get(i), columns));
                    }
                    yield b.append('}').toString();
                }
                StringBuilder b = new StringBuilder(dialect.functionName(c.function().name())).append('(');
                for (int i = 0; i < c.arguments().size(); i++) {
                    b.append(i > 0 ? ", " : "").append(expression(c.arguments().get(i), columns));
                }
                yield b.append(')').toString();
            }
        };
    }

    /** Renders a {@code CREATE TABLE} statement for a schema. */
    public String createTable(String tableName, Schema schema) {
        StringBuilder b = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
        for (int i = 0; i < schema.size(); i++) {
            b.append(i > 0 ? ", " : "").append(dialect.quoteIdentifier(schema.name(i))).append(' ')
                    .append(dialect.typeName(schema.type(i))).append(schema.nullable(i) ? "" : " NOT NULL");
        }
        return b.append(')').toString();
    }

    /** Renders an {@code INSERT} of rows of Java values. */
    public String insert(String tableName, Schema schema, List<Object[]> rows) {
        StringBuilder b = new StringBuilder("INSERT INTO ").append(tableName).append(" VALUES ");
        for (int r = 0; r < rows.size(); r++) {
            b.append(r > 0 ? ", (" : "(");
            for (int c = 0; c < schema.size(); c++) {
                b.append(c > 0 ? ", " : "").append(dialect.literal(rows.get(r)[c], schema.type(c)));
            }
            b.append(')');
        }
        return b.toString();
    }

    /** Returns the dialect. */
    public Dialect dialect() {
        return dialect;
    }
}
