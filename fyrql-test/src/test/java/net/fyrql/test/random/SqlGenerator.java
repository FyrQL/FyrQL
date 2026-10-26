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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.OrderItem;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlExpr.LiteralKind;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.WindowSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Generates queries as SQL syntax over the {@linkplain RandomTables random
 * tables}, with the forms that a plan does not have: names and their case,
 * aliases of tables and of the select list, {@code *} with
 * {@code EXCLUDE} and {@code REPLACE}, joins with {@code USING} and
 * {@code NATURAL}, subqueries in {@code FROM}, {@code WITH},
 * {@code VALUES}, {@code SELECT} without {@code FROM}, {@code DISTINCT},
 * {@code GROUP BY} with expressions, positions, aliases and {@code ALL},
 * {@code HAVING}, {@code ORDER BY} with positions and {@code ALL},
 * {@code LIMIT}, set operations, and literals in all their forms.
 * <p>
 * The select list may hold window functions, over windows of their own or
 * of the {@code WINDOW} clause, with {@code QUALIFY}; that of an
 * aggregation one over its groups and aggregates. A window either orders by
 * all columns in scope, or its calls give the same result to peers.
 * <p>
 * The {@code WHERE} clause may hold a subquery over a table: {@code EXISTS},
 * {@code IN}, or a comparison with an aggregate or with the first value in
 * order. Its own {@code WHERE} may compare its key with an integer column of
 * the query around it, and, where it does not aggregate, another of its
 * columns with one around it. {@code HAVING} may compare with an
 * uncorrelated aggregate, and the select list may hold a correlated one.
 * <p>
 * The expressions come from the {@link ExpressionGenerator} over the types
 * of the columns in scope, written with names by {@link SqlText}. The
 * columns of a subquery or a common table are learned by binding it in
 * FyrQL; one that does not bind is replaced by a table. The items of a
 * subquery are named, as the names of other expressions differ between the
 * engines. A {@code LIMIT} follows an order of all columns only, and the
 * aggregates whose result depends on the order of their values are ordered
 * by them and leave out floating-point values.
 * <p>
 * The base tables are those of the random tables or, for another source,
 * those it has, named without the source; a sample of their rows gives the
 * literals of conditions some of the time, so that they select some rows of
 * tables with values of their own. {@link #partition()} generates a query
 * for the metamorphic checks: one without order, limit and windows, and a
 * condition to partition it by.
 */
final class SqlGenerator {

    /** A generated query, and whether its {@code ORDER BY} orders all rows, so that the order is compared. */
    record Generated(Query query, boolean ordered) {
        String sql() {
            return SqlPrinter.print(query);
        }
    }

    /**
     * A column in scope: the name that qualifies it, {@code null} if it has
     * none, its name, its type, and values of it in its table, empty for a
     * computed one.
     */
    private record Column(String qualifier, String name, DataType type, List<Object> sample) {
        Column(String qualifier, String name, DataType type) {
            this(qualifier, name, type, List.of());
        }
    }

    /** An item of {@code FROM} with the columns it brings into scope. */
    private record Relation(FromItem item, List<Column> columns) {
    }

    /** An aggregate of a subquery and the type to compare it with. */
    private record Call(SqlExpr call, DataType type) {
    }

    /** A {@code SELECT} with the number of its output columns, {@code -1} where a star leaves it open. */
    private record Select(QueryBody.Select body, int width, List<String> aliases) {
    }

    private static final Position P = Position.UNKNOWN;
    private static final List<String> ALIASES = List.of("x", "y", "z", "k", "v", "A", "B", "total", "Val", "n");

    private final SplittableRandom random;
    private final FyrQL engine;
    private final Map<String, Schema> tables;
    private final Map<String, List<List<Object>>> samples = new LinkedHashMap<>();
    private final Map<String, List<Column>> ctes = new LinkedHashMap<>();
    private List<Query.CommonTable> with = List.of();
    private int names;

    /** A generator over the random tables, without samples. */
    SqlGenerator(SplittableRandom random, FyrQL engine) {
        this(random, engine, RandomTables.SCHEMAS, Map.of());
    }

    /**
     * @param engine the engine that binds what is generated, whose default source has the tables
     * @param tables the base tables by name
     * @param rows rows of the tables by name, whose values the literals take some of the time
     */
    SqlGenerator(SplittableRandom random, FyrQL engine, Map<String, Schema> tables, Map<String, List<Object[]>> rows) {
        this.random = random;
        this.engine = engine;
        this.tables = tables;
        for (Map.Entry<String, List<Object[]>> table : rows.entrySet()) {
            List<List<Object>> columns = new ArrayList<>();
            for (int c = 0; c < tables.get(table.getKey()).size(); c++) {
                Set<Object> values = new LinkedHashSet<>();
                for (Object[] row : table.getValue()) {
                    if (row[c] != null) {
                        values.add(row[c]);
                    }
                }
                columns.add(List.copyOf(values));
            }
            samples.put(table.getKey(), columns);
        }
    }

    /** The sampled values of a column of a base table, empty for none. */
    private List<Object> sample(String table, int column) {
        List<List<Object>> columns = samples.get(table);
        return columns == null ? List.of() : columns.get(column);
    }

    /** Generates expressions over columns in scope, with their samples. */
    private ExpressionGenerator generator(List<Column> scope) {
        return new ExpressionGenerator(random, scope.stream().map(Column::type).toList(),
                scope.stream().map(Column::sample).toList());
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    /** Generates a query. */
    Generated generate() {
        commonTables();
        int kind = random.nextInt(100);
        QueryBody body;
        int width;
        boolean distinct = false;
        if (kind < 6) {
            QueryBody.Values v = values();
            body = v;
            width = v.rows().get(0).size();
        } else if (kind < 18) {
            body = setOperation();
            width = -1;
        } else {
            Select s = select(2, false, true);
            body = s.body();
            width = s.width();
            distinct = s.body().distinct();
        }
        if (width < 0) {
            Schema schema = schema(new Query(with, body, List.of(), null, null));
            width = schema == null ? -1 : schema.size();
        }
        List<OrderItem> order = new ArrayList<>();
        boolean total = false;
        int o = random.nextInt(100);
        if (o < 30) {
            order.add(OrderItem.all(chance(70), chance(30)));
            total = true;
        } else if (o < 50 && width > 0) {
            List<Integer> positions = new ArrayList<>();
            for (int i = 1; i <= width; i++) {
                positions.add(i);
            }
            Collections.shuffle(positions, new java.util.Random(random.nextLong()));
            for (int i : positions) {
                order.add(new OrderItem(number(i), chance(70), chance(30)));
            }
            total = true;
        } else if (o < 60 && width > 0 && !distinct) {
            order.add(new OrderItem(number(random.nextInt(1, width + 1)), chance(70), chance(30)));
        }
        SqlExpr limit = null;
        SqlExpr offset = null;
        if (total && chance(40)) {
            limit = chance(70) ? number(random.nextInt(0, 12))
                    : new SqlExpr.Binary(SqlExpr.BinaryOp.ADD, number(random.nextInt(0, 5)), number(1), P);
            if (chance(30)) {
                offset = number(random.nextInt(0, 6));
            }
        }
        return new Generated(new Query(with, body, order, limit, offset), total);
    }

    /** Draws the common tables of a query, sometimes none. */
    private void commonTables() {
        ctes.clear();
        with = new ArrayList<>();
        if (chance(20)) {
            List<Query.CommonTable> tables = new ArrayList<>();
            for (int i = random.nextInt(1, 3); i > 0; i--) {
                String name = "w" + tables.size();
                Query q = Query.of(select(1, true, false).body());
                List<String> renamed = new ArrayList<>();
                List<Column> columns = columns(q, null);
                if (columns == null) {
                    continue;
                }
                if (chance(30)) {
                    for (int c = 0; c < columns.size() && c < 2; c++) {
                        renamed.add("c" + c);
                    }
                }
                List<Column> visible = new ArrayList<>();
                for (int c = 0; c < columns.size(); c++) {
                    visible.add(new Column(null, c < renamed.size() ? renamed.get(c) : columns.get(c).name(),
                            columns.get(c).type()));
                }
                tables.add(new Query.CommonTable(name, renamed, q, P));
                with = List.copyOf(tables);
                ctes.put(name, visible);
            }
        }
    }

    private static SqlExpr number(long n) {
        return new SqlExpr.Literal(LiteralKind.NUMBER, Long.toString(n), P);
    }

    /** The columns a query gives, learned by binding it in FyrQL, or {@code null} if it does not bind. */
    private Schema schema(Query query) {
        try {
            return engine.plan(SqlPrinter.print(query)).schema();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Column> columns(Query query, String qualifier) {
        Query withCtes = with.isEmpty() ? query : new Query(with, query.body(), query.orderBy(), query.limit(),
                query.offset());
        Schema schema = schema(withCtes);
        if (schema == null) {
            return null;
        }
        List<Column> columns = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            columns.add(new Column(qualifier, f.name(), f.type()));
        }
        return columns;
    }

    // ---------------------------------------------------------------- FROM

    private Relation relation(int depth) {
        int kind = random.nextInt(100);
        // Often, so that a common table is read more than once and runs once.
        if (kind < 25 && !ctes.isEmpty()) {
            String name = pick(new ArrayList<>(ctes.keySet()));
            String alias = chance(50) ? "c" + names++ : null;
            List<Column> columns = new ArrayList<>();
            for (Column c : ctes.get(name)) {
                columns.add(new Column(alias != null ? alias : name, c.name(), c.type(), c.sample()));
            }
            return new Relation(new FromItem.TableName(List.of(name), alias(alias), P), columns);
        }
        if (kind < 30 && depth > 0) {
            String alias = "s" + names++;
            Query inner = new Query(List.of(), select(depth - 1, true, false).body(), List.of(), null, null);
            List<Column> columns = columns(inner, alias);
            if (columns != null) {
                return new Relation(new FromItem.Subquery(inner, alias(alias), P), columns);
            }
        }
        if (kind < 38) {
            QueryBody.Values v = values();
            String alias = "v" + names++;
            List<String> columnNames = new ArrayList<>();
            for (int i = 0; i < v.rows().get(0).size(); i++) {
                columnNames.add("c" + i);
            }
            Query inner = Query.of(v);
            List<Column> columns = columns(inner, alias);
            if (columns != null) {
                List<Column> renamed = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    renamed.add(new Column(alias, columnNames.get(i), columns.get(i).type()));
                }
                return new Relation(new FromItem.Subquery(inner, new FromItem.Alias(alias, columnNames), P), renamed);
            }
        }
        if (kind < 41) {
            // A series of a few numbers, its column named after the function or by the alias.
            String function = chance(50) ? "range" : "generate_series";
            long start = random.nextInt(4);
            List<SqlExpr> arguments = new ArrayList<>(List.of(number(start), number(start + random.nextInt(6))));
            if (chance(30)) {
                arguments.add(number(1 + random.nextInt(2)));
            }
            String alias = chance(50) ? "r" + names++ : null;
            String column = alias != null && chance(50) ? "i" : function;
            FromItem.Alias spelled = alias == null ? null
                    : new FromItem.Alias(alias, column.equals(function) ? List.of() : List.of(column));
            return new Relation(new FromItem.TableFunctionCall(function, arguments, Map.of(), spelled, P),
                    List.of(new Column(alias != null ? alias : function, column, DataType.BIGINT)));
        }
        String table = pick(new ArrayList<>(tables.keySet()));
        String alias = chance(50) ? (chance(20) ? "T" : "t") + names++ : null;
        String spelled = chance(15) ? table.toUpperCase(Locale.ROOT) : table;
        List<Column> columns = new ArrayList<>();
        List<Schema.Field> fields = tables.get(table).fields();
        for (int i = 0; i < fields.size(); i++) {
            Schema.Field f = fields.get(i);
            String name = chance(10) ? f.name().toUpperCase(Locale.ROOT) : f.name();
            columns.add(new Column(alias != null ? alias : spelled, name, f.type(), sample(table, i)));
        }
        return new Relation(new FromItem.TableName(List.of(spelled), alias(alias), P), columns);
    }

    private static FromItem.Alias alias(String name) {
        return name == null ? null : new FromItem.Alias(name, List.of());
    }

    private Relation from(int depth) {
        Relation left = relation(depth);
        for (int joins = chance(40) ? random.nextInt(1, 3) : 0; joins > 0; joins--) {
            left = join(left, relation(depth));
        }
        return left;
    }

    private Relation join(Relation left, Relation right) {
        Set<String> common = new LinkedHashSet<>();
        for (Column l : left.columns()) {
            for (Column r : right.columns()) {
                if (l.qualifier() != null && r.qualifier() != null && l.name().equalsIgnoreCase(r.name())) {
                    common.add(l.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        int k = random.nextInt(100);
        FromItem.JoinKind kind = k < 40 ? FromItem.JoinKind.INNER : k < 60 ? FromItem.JoinKind.LEFT
                : k < 70 ? FromItem.JoinKind.RIGHT : k < 80 ? FromItem.JoinKind.FULL : FromItem.JoinKind.CROSS;
        List<Column> columns = new ArrayList<>(left.columns());
        columns.addAll(right.columns());
        if (left.columns().isEmpty() || right.columns().isEmpty()) {
            kind = FromItem.JoinKind.CROSS;
        }
        if (kind == FromItem.JoinKind.CROSS) {
            return new Relation(new FromItem.Join(kind, left.item(), right.item(), null, List.of(), false, P), columns);
        }
        int c = random.nextInt(100);
        if (!common.isEmpty() && c < 35) {
            boolean natural = c < 10;
            List<String> using = new ArrayList<>();
            for (String name : common) {
                if (natural || using.isEmpty() || chance(40)) {
                    using.add(name);
                }
            }
            if (natural) {
                using = new ArrayList<>(common);
            }
            List<Column> merged = new ArrayList<>();
            for (String name : using) {
                Column same = null;
                for (Column l : left.columns()) {
                    if (l.name().equalsIgnoreCase(name)) {
                        same = l;
                    }
                }
                merged.add(new Column(null, name, same.type(), same.sample()));
            }
            merged.addAll(columns);
            return new Relation(new FromItem.Join(kind, left.item(), right.item(), null, natural ? List.of() : using,
                    natural, P), merged);
        }
        // ON: an equality of two columns, sometimes of one type, and another condition.
        Column l = pick(left.columns().stream().filter(x -> x.qualifier() != null).toList().isEmpty() ? left.columns()
                : left.columns().stream().filter(x -> x.qualifier() != null).toList());
        List<Column> same = right.columns().stream().filter(x -> x.type().equals(l.type()) && x.qualifier() != null)
                .toList();
        Column r = !same.isEmpty() && chance(80) ? pick(same) : pick(right.columns());
        SqlExpr on = new SqlExpr.Binary(SqlExpr.BinaryOp.EQUAL, reference(l, columns), reference(r, columns), P);
        if (chance(30)) {
            List<Column> side = kind == FromItem.JoinKind.INNER || chance(20) ? columns
                    : chance(50) ? left.columns() : right.columns();
            ExpressionGenerator g = generator(side);
            SqlText text = new SqlText(random, references(side, columns));
            on = new SqlExpr.Binary(SqlExpr.BinaryOp.AND, on, text.of(g.condition(1)), P);
        }
        return new Relation(new FromItem.Join(kind, left.item(), right.item(), on, List.of(), false, P), columns);
    }

    // ---------------------------------------------------------------- names

    /** The syntax that names a column among those in scope: unqualified where that is unique. */
    private SqlExpr reference(Column c, List<Column> scope) {
        if (c.qualifier() == null) {
            return new SqlExpr.Identifier(List.of(c.name()), P);
        }
        int same = 0;
        for (Column other : scope) {
            if (other.name().equalsIgnoreCase(c.name())) {
                same++;
            }
        }
        boolean merged = scope.stream().anyMatch(o -> o.qualifier() == null && o.name().equalsIgnoreCase(c.name()));
        if (same == 1 && !merged && chance(70)) {
            return new SqlExpr.Identifier(List.of(c.name()), P);
        }
        return new SqlExpr.Identifier(List.of(c.qualifier(), c.name()), P);
    }

    private List<SqlExpr> references(List<Column> columns, List<Column> scope) {
        List<SqlExpr> result = new ArrayList<>();
        for (Column c : columns) {
            result.add(reference(c, scope));
        }
        return result;
    }

    // -------------------------------------------------------------- SELECT

    /**
     * Generates a {@code SELECT}.
     *
     * @param depth how deep subqueries may nest
     * @param named whether every expression item gets an alias, as in a subquery
     * @param aggregates whether it may aggregate
     */
    private Select select(int depth, boolean named, boolean aggregates) {
        Relation from = chance(92) ? from(depth) : null;
        List<Column> scope = from == null ? List.of() : from.columns();
        ExpressionGenerator g = generator(scope);
        SqlText text = new SqlText(random, references(scope, scope));
        SqlExpr where = from != null && chance(45) ? text.of(g.condition(2)) : null;
        if (from != null && chance(20)) {
            SqlExpr subquery = subqueryCondition(scope, g, text);
            where = where == null ? subquery : new SqlExpr.Binary(chance(25) ? SqlExpr.BinaryOp.OR
                    : SqlExpr.BinaryOp.AND, where, subquery, P);
        }
        if (from != null && chance(aggregates ? 35 : 20)) {
            return aggregate(from, scope, g, text, where, named);
        }
        List<SelectItem> items = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        int width = 0;
        if (from != null && chance(12)) {
            items.add(star(from));
            width = -1;
        }
        for (int i = items.isEmpty() ? random.nextInt(1, 6) : random.nextInt(0, 3); i > 0; i--) {
            Expression e = !scope.isEmpty() && chance(35) ? g.leaf() : g.any(3);
            SqlExpr x = text.of(e);
            boolean plain = x instanceof SqlExpr.Identifier;
            String alias = named && !plain || chance(30) ? pick(ALIASES) : null;
            items.add(new SelectItem.ExpressionItem(x, alias));
            aliases.add(alias);
            if (width >= 0) {
                width++;
            }
        }
        List<QueryBody.NamedWindow> windowClause = new ArrayList<>();
        SqlExpr qualify = null;
        if (from != null && chance(15)) {
            Map<String, Spec> specs = new LinkedHashMap<>();
            List<SqlExpr> windows = new ArrayList<>();
            for (int i = random.nextInt(1, 3); i > 0; i--) {
                SqlExpr w = window(scope, g, text, windowClause, specs);
                windows.add(w);
                String alias = named || chance(30) ? pick(ALIASES) : null;
                items.add(new SelectItem.ExpressionItem(w, alias));
                aliases.add(alias);
                if (width >= 0) {
                    width++;
                }
            }
            if (chance(25)) {
                qualify = new SqlExpr.Binary(comparison(), pick(windows), number(random.nextInt(0, 4)), P);
            }
        }
        if (from != null && chance(10)) {
            Relation inner = inner();
            SqlExpr x = new SqlExpr.ScalarSubquery(aggregating(inner, innerAggregate(inner),
                    innerWhere(inner, scope, true)), P);
            String alias = named || chance(30) ? pick(ALIASES) : null;
            items.add(new SelectItem.ExpressionItem(x, alias));
            aliases.add(alias);
            if (width >= 0) {
                width++;
            }
        }
        boolean distinct = chance(10);
        return new Select(new QueryBody.Select(distinct, items, from == null ? null : from.item(), where, null, null,
                windowClause, qualify, P), width, aliases);
    }

    private SelectItem star(Relation from) {
        List<String> qualifiers = from.columns().stream().map(Column::qualifier).filter(q -> q != null).distinct()
                .toList();
        List<String> qualifier = chance(25) && !qualifiers.isEmpty() ? List.of(pick(qualifiers)) : List.of();
        List<String> exclude = new ArrayList<>();
        List<SelectItem.Replacement> replace = new ArrayList<>();
        if (chance(30)) {
            Column c = pick(from.columns());
            if (chance(50)) {
                exclude.add(c.name());
            } else {
                ExpressionGenerator g = new ExpressionGenerator(random, List.of(c.type()));
                SqlText text = new SqlText(random, List.of(new SqlExpr.Identifier(c.qualifier() == null
                        ? List.of(c.name()) : List.of(c.qualifier(), c.name()), P)));
                replace.add(new SelectItem.Replacement(text.of(g.any(2)), c.name()));
            }
        }
        return new SelectItem.Star(qualifier, exclude, replace, P);
    }

    private Select aggregate(Relation from, List<Column> scope, ExpressionGenerator g, SqlText text, SqlExpr where,
            boolean named) {
        List<SqlExpr> groups = new ArrayList<>();
        for (int i = random.nextInt(0, 3); i > 0; i--) {
            groups.add(text.of(chance(70) && !scope.isEmpty() ? g.leaf() : g.any(1)));
        }
        List<SelectItem> items = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (SqlExpr group : groups) {
            String alias = named && !(group instanceof SqlExpr.Identifier) || chance(30) ? pick(ALIASES) : null;
            items.add(new SelectItem.ExpressionItem(group, alias));
            aliases.add(alias);
        }
        List<SqlExpr> calls = new ArrayList<>();
        for (int i = random.nextInt(groups.isEmpty() ? 1 : 0, 4); i > 0; i--) {
            SqlExpr call = aggregateCall(g, text);
            calls.add(call);
            if (chance(15)) {
                call = new SqlExpr.Binary(chance(50) ? SqlExpr.BinaryOp.ADD : SqlExpr.BinaryOp.MULTIPLY, call,
                        new SqlExpr.Literal(LiteralKind.NUMBER, Integer.toString(random.nextInt(1, 4)), P), P);
            }
            items.add(new SelectItem.ExpressionItem(call, named || chance(30) ? "a" + i : null));
            aliases.add(null);
        }
        if (items.isEmpty()) {
            items.add(new SelectItem.ExpressionItem(countStar(), named ? "n" : null));
            calls.add(countStar());
        }
        QueryBody.GroupBy groupBy = null;
        if (!groups.isEmpty() || chance(10)) {
            int form = random.nextInt(100);
            if (form < 30 || groups.isEmpty()) {
                groupBy = new QueryBody.GroupBy(true, List.of());
            } else if (form < 55) {
                List<SqlExpr> positions = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) {
                    positions.add(number(i + 1));
                }
                groupBy = new QueryBody.GroupBy(false, positions);
            } else if (form >= 85) {
                groupBy = new QueryBody.GroupBy(false, groups, groupingSets(groups.size()));
                if (chance(30)) {
                    items.add(new SelectItem.ExpressionItem(new SqlExpr.FunctionCall(chance(70) ? "grouping"
                            : "grouping_id", groups, P), named || chance(30) ? "g" + names++ : null));
                    aliases.add(null);
                }
            } else if (form < 70 && aliases.subList(0, groups.size()).stream().allMatch(a -> a != null)) {
                List<SqlExpr> byAlias = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) {
                    byAlias.add(new SqlExpr.Identifier(List.of(aliases.get(i)), P));
                }
                groupBy = new QueryBody.GroupBy(false, byAlias);
            } else {
                groupBy = new QueryBody.GroupBy(false, groups);
            }
        }
        SqlExpr having = null;
        if (chance(25) && !calls.isEmpty()) {
            having = new SqlExpr.Binary(pick(List.of(SqlExpr.BinaryOp.GREATER, SqlExpr.BinaryOp.LESS_EQUAL,
                    SqlExpr.BinaryOp.NOT_EQUAL)), pick(calls), number(random.nextInt(0, 4)), P);
        }
        if (chance(15)) {
            Relation inner = inner();
            Call c = innerAggregate(inner);
            SqlExpr left = chance(60) || calls.isEmpty() ? countStar() : pick(calls);
            SqlExpr compared = new SqlExpr.Binary(comparison(), left, new SqlExpr.ScalarSubquery(aggregating(inner,
                    c, innerWhere(inner, List.of(), true)), P), P);
            having = having == null ? compared : new SqlExpr.Binary(SqlExpr.BinaryOp.AND, having, compared, P);
        }
        if (chance(15) && (groupBy == null || !groupBy.all())) {
            List<SqlExpr> keys = new ArrayList<>(groups);
            keys.addAll(calls);
            items.add(new SelectItem.ExpressionItem(windowOverGroups(keys, groups), named || chance(30)
                    ? "r" + names++ : null));
            aliases.add(null);
        }
        QueryBody.Select body = new QueryBody.Select(chance(5), items, from.item(), where, groupBy, having, P);
        return new Select(body, items.size(), aliases);
    }

    /** The sets of ROLLUP, of CUBE, or of some of the subsets, over a number of groups. */
    private List<List<Integer>> groupingSets(int n) {
        List<List<Integer>> sets = new ArrayList<>();
        int form = random.nextInt(3);
        for (int mask = (1 << n) - 1; mask >= 0; mask--) {
            List<Integer> set = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << (n - 1 - i))) != 0) {
                    set.add(i);
                }
            }
            // ROLLUP keeps the prefixes, CUBE every subset, GROUPING SETS some.
            boolean prefix = set.equals(java.util.stream.IntStream.range(0, set.size()).boxed().toList());
            if (form == 0 ? prefix : form == 1 || chance(60)) {
                sets.add(set);
            }
        }
        return sets.isEmpty() ? List.of(List.of()) : sets;
    }

    // ------------------------------------------------------------ partitions

    /** The forms of ternary logic partitioning, by the shape of the query that is partitioned. */
    enum Kind {
        /** A select list over rows, partitioned in {@code WHERE}. */
        WHERE,
        /** The same with {@code DISTINCT}, whose parts are united without duplicates. */
        DISTINCT,
        /** An aggregation, partitioned in {@code HAVING} by a condition over its groups and aggregates. */
        HAVING,
        /**
         * An aggregation of aggregates that combine, partitioned in {@code WHERE}; the aggregates of the parts are
         * aggregated again. Its groups are named {@code g0}, {@code g1}, its aggregates {@code a0}, {@code a1}.
         */
        AGGREGATE,
        /**
         * A select list over an inner join, partitioned in its {@code ON}: the parts join {@code ON c AND p},
         * {@code ON c AND NOT p} and {@code ON c AND p IS NULL}; a cross join is one {@code ON true}.
         */
        JOIN_ON,
        /**
         * A select list over a subquery with window functions {@code w0} and {@code w1}, partitioned in
         * {@code WHERE} over the columns of the subquery: a condition must not move below the windows.
         */
        WINDOW
    }

    /**
     * A query to partition: its common tables, its {@code SELECT} without
     * order, limit and windows, the condition that partitions it, and a
     * condition over the rows of its {@code FROM} for NoREC, which is the
     * partitioning one but where that is over groups.
     */
    record Partition(Kind kind, List<Query.CommonTable> with, QueryBody.Select base, SqlExpr predicate,
            SqlExpr rows) {
        Query query() {
            return new Query(with, base, List.of(), null, null);
        }
    }

    /**
     * Generates a query to partition: in {@link Kind#WHERE} form 30 times in
     * a hundred, {@link Kind#DISTINCT} 12, {@link Kind#HAVING} 18,
     * {@link Kind#AGGREGATE} 20, {@link Kind#JOIN_ON} 10 and
     * {@link Kind#WINDOW} 10. A query that does not bind is drawn again, up
     * to five times.
     */
    Partition partition() {
        Partition last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int k = random.nextInt(100);
            Kind kind = k < 30 ? Kind.WHERE : k < 42 ? Kind.DISTINCT : k < 60 ? Kind.HAVING : k < 80 ? Kind.AGGREGATE
                    : k < 90 ? Kind.JOIN_ON : Kind.WINDOW;
            Partition p = partition(kind);
            if (p != null) {
                if (schema(p.query()) != null) {
                    return p;
                }
                last = p;
            }
        }
        return last != null ? last : partition(Kind.WHERE);
    }

    /** A query of a kind to partition, or {@code null} where an aggregation does not bind. */
    private Partition partition(Kind kind) {
        commonTables();
        Relation from = switch (kind) {
            case JOIN_ON -> innerJoin();
            case WINDOW -> windowed();
            default -> from(2);
        };
        if (from == null) {
            return null;
        }
        List<Column> scope = from.columns();
        ExpressionGenerator g = generator(scope);
        SqlText text = new SqlText(random, references(scope, scope));
        SqlExpr where = chance(30) ? text.of(g.condition(2)) : null;
        if (chance(15)) {
            SqlExpr subquery = subqueryCondition(scope, g, text);
            where = where == null ? subquery : new SqlExpr.Binary(chance(25) ? SqlExpr.BinaryOp.OR
                    : SqlExpr.BinaryOp.AND, where, subquery, P);
        }
        SqlExpr rows = text.of(g.condition(2));
        return switch (kind) {
            case WHERE, DISTINCT, JOIN_ON, WINDOW -> {
                List<SelectItem> items = new ArrayList<>();
                if (chance(10)) {
                    items.add(star(from));
                }
                for (int i = items.isEmpty() ? random.nextInt(1, 6) : random.nextInt(0, 3); i > 0; i--) {
                    Expression e = chance(35) ? g.leaf() : g.any(3);
                    items.add(new SelectItem.ExpressionItem(text.of(e), chance(30) ? pick(ALIASES) : null));
                }
                if (chance(10)) {
                    Relation inner = inner();
                    items.add(new SelectItem.ExpressionItem(new SqlExpr.ScalarSubquery(aggregating(inner,
                            innerAggregate(inner), innerWhere(inner, scope, true)), P), null));
                }
                yield new Partition(kind, with, new QueryBody.Select(kind == Kind.DISTINCT, items, from.item(), where,
                        null, null, P), rows, rows);
            }
            case HAVING -> having(from, g, text, where, rows);
            case AGGREGATE -> combining(from, g, text, where, rows);
        };
    }

    /**
     * An inner join of two relations {@code ON} an equality of two columns,
     * mostly of one type, or {@code ON true} for a cross join, whose
     * condition a partition of {@link Kind#JOIN_ON} extends.
     */
    private Relation innerJoin() {
        Relation left = relation(1);
        Relation right = relation(1);
        List<Column> columns = new ArrayList<>(left.columns());
        columns.addAll(right.columns());
        SqlExpr on;
        if (left.columns().isEmpty() || right.columns().isEmpty() || chance(20)) {
            on = new SqlExpr.Literal(LiteralKind.BOOLEAN, "true", P);
        } else {
            Column l = pick(left.columns());
            List<Column> same = right.columns().stream().filter(x -> x.type().equals(l.type())).toList();
            Column r = !same.isEmpty() && chance(80) ? pick(same) : pick(right.columns());
            on = new SqlExpr.Binary(SqlExpr.BinaryOp.EQUAL, reference(l, columns), reference(r, columns), P);
        }
        return new Relation(new FromItem.Join(FromItem.JoinKind.INNER, left.item(), right.item(), on, List.of(),
                false, P), columns);
    }

    /**
     * A subquery over a relation with its columns and one or two window
     * functions {@code w0} and {@code w1} over them, for
     * {@link Kind#WINDOW}; {@code null} if it does not bind.
     */
    private Relation windowed() {
        Relation over = relation(0);
        List<Column> scope = over.columns();
        if (scope.isEmpty()) {
            return null;
        }
        ExpressionGenerator g = generator(scope);
        SqlText text = new SqlText(random, references(scope, scope));
        List<SelectItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Column c : scope) {
            if (seen.add(c.name().toLowerCase(Locale.ROOT))) {
                items.add(new SelectItem.ExpressionItem(reference(c, scope), null));
            }
        }
        List<QueryBody.NamedWindow> named = new ArrayList<>();
        Map<String, Spec> specs = new LinkedHashMap<>();
        for (int i = 0, n = random.nextInt(1, 3); i < n; i++) {
            items.add(new SelectItem.ExpressionItem(window(scope, g, text, named, specs), "w" + i));
        }
        String alias = "s" + names++;
        Query inner = new Query(List.of(), new QueryBody.Select(false, items, over.item(), null, null, null, named,
                null, List.of(), P), List.of(), null, null);
        List<Column> columns = columns(inner, alias);
        return columns == null ? null : new Relation(new FromItem.Subquery(inner, alias(alias), P), columns);
    }

    /** A grouping key: mostly a column, else a shallow expression. */
    private SqlExpr groupKey(ExpressionGenerator g, SqlText text) {
        return text.of(chance(70) ? g.leaf() : g.any(1));
    }

    /**
     * An aggregation with up to two groups, one to three aggregates and
     * sometimes a {@code HAVING} of its own, partitioned by a condition over
     * its groups and aggregates, whose types are learned by binding it.
     */
    private Partition having(Relation from, ExpressionGenerator g, SqlText text, SqlExpr where, SqlExpr rows) {
        List<SqlExpr> keys = new ArrayList<>();
        int groups = random.nextInt(0, 3);
        for (int i = groups; i > 0; i--) {
            keys.add(groupKey(g, text));
        }
        for (int i = random.nextInt(1, 4); i > 0; i--) {
            keys.add(aggregateCall(g, text));
        }
        List<SelectItem> items = new ArrayList<>();
        for (SqlExpr key : keys) {
            items.add(new SelectItem.ExpressionItem(key, null));
        }
        QueryBody.GroupBy groupBy = groups == 0 ? null : chance(30) ? new QueryBody.GroupBy(true, List.of())
                : new QueryBody.GroupBy(false, keys.subList(0, groups));
        Schema schema = schema(new Query(with, new QueryBody.Select(false, items, from.item(), where, groupBy, null,
                P), List.of(), null, null));
        if (schema == null) {
            return null;
        }
        ExpressionGenerator over = new ExpressionGenerator(random, schema.types());
        SqlExpr predicate = new SqlText(random, keys).of(over.condition(2));
        SqlExpr having = chance(30) ? new SqlExpr.Binary(pick(List.of(SqlExpr.BinaryOp.GREATER,
                SqlExpr.BinaryOp.LESS_EQUAL, SqlExpr.BinaryOp.NOT_EQUAL)), pick(keys.subList(groups, keys.size())),
                number(random.nextInt(0, 4)), P) : null;
        return new Partition(Kind.HAVING, with, new QueryBody.Select(false, items, from.item(), where, groupBy, having,
                P), predicate, rows);
    }

    /**
     * An aggregation with up to two groups {@code g0} and {@code g1} and one
     * to four aggregates {@code a0} to {@code a3}, whose values over the
     * parts of a partition combine into their value over all rows.
     */
    private Partition combining(Relation from, ExpressionGenerator g, SqlText text, SqlExpr where, SqlExpr predicate) {
        List<SelectItem> items = new ArrayList<>();
        List<SqlExpr> groups = new ArrayList<>();
        for (int i = random.nextInt(0, 3); i > 0; i--) {
            SqlExpr key = groupKey(g, text);
            items.add(new SelectItem.ExpressionItem(key, "g" + groups.size()));
            groups.add(key);
        }
        for (int i = 0, n = random.nextInt(1, 5); i < n; i++) {
            items.add(new SelectItem.ExpressionItem(combinable(g, text), "a" + i));
        }
        QueryBody.GroupBy groupBy = groups.isEmpty() ? null : chance(30) ? new QueryBody.GroupBy(true, List.of())
                : new QueryBody.GroupBy(false, groups);
        return new Partition(Kind.AGGREGATE, with, new QueryBody.Select(false, items, from.item(), where, groupBy,
                null, P), predicate, predicate);
    }

    /**
     * An aggregate whose values over the parts of a partition combine into
     * its value over all rows: a count or a sum of an exact type, which add
     * up, {@code min}, {@code max}, {@code bool_and} or {@code bool_or}. No
     * intervals for {@code min} and {@code max}, whose equal values differ
     * in their parts, so that the one taken depends on the order of rows.
     */
    private SqlExpr combinable(ExpressionGenerator g, SqlText text) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Expression argument = chance(60) ? g.leaf() : g.any(2);
            DataType t = argument.type();
            SqlExpr x = text.of(argument);
            switch (random.nextInt(6)) {
                case 0 -> {
                    return countStar();
                }
                case 1 -> {
                    return call("count", List.of(x));
                }
                case 2 -> {
                    if (t.isIntegral() || t instanceof DataType.Decimal) {
                        return call("sum", List.of(x));
                    }
                }
                case 3, 4 -> {
                    if (ExpressionGenerator.plain(t) && t != DataType.INTERVAL) {
                        return call(chance(50) ? "min" : "max", List.of(x));
                    }
                }
                default -> {
                    if (t == DataType.BOOLEAN) {
                        return call(chance(50) ? "bool_and" : "bool_or", List.of(x));
                    }
                }
            }
        }
        return countStar();
    }

    private static SqlExpr.FunctionCall countStar() {
        return new SqlExpr.FunctionCall("count", List.of(), false, true, List.of(), P);
    }

    /**
     * An aggregate over a generated argument of a type it takes, ordered by it where the order matters, now and
     * then with a filter.
     */
    private SqlExpr aggregateCall(ExpressionGenerator g, SqlText text) {
        SqlExpr.FunctionCall call = unfilteredCall(g, text);
        return chance(20) ? call.withFilter(text.of(g.condition(1))) : call;
    }

    private SqlExpr.FunctionCall unfilteredCall(ExpressionGenerator g, SqlText text) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Expression argument = chance(60) ? g.leaf() : g.any(2);
            DataType t = argument.type();
            boolean floating = t == DataType.FLOAT || t == DataType.DOUBLE;
            boolean plain = ExpressionGenerator.plain(t);
            boolean distinct = chance(20);
            SqlExpr x = text.of(argument);
            List<OrderItem> own = List.of(new OrderItem(x, chance(60), chance(30)));
            switch (random.nextInt(12)) {
                case 0 -> {
                    return countStar();
                }
                case 1 -> {
                    return new SqlExpr.FunctionCall("count", List.of(x), distinct, false, List.of(), P);
                }
                case 2, 3 -> {
                    if (t.isIntegral() || t instanceof DataType.Decimal) {
                        return new SqlExpr.FunctionCall(chance(50) ? "sum" : "avg", List.of(x), distinct, false,
                                List.of(), P);
                    }
                }
                case 4, 5 -> {
                    if (plain) {
                        return new SqlExpr.FunctionCall(chance(50) ? "min" : "max", List.of(x), distinct, false,
                                List.of(), P);
                    }
                }
                case 6 -> {
                    if (t == DataType.BOOLEAN) {
                        return new SqlExpr.FunctionCall(chance(50) ? "bool_and" : "bool_or", List.of(x), false, false,
                                List.of(), P);
                    }
                }
                case 7, 8 -> {
                    if (plain && !floating) {
                        SqlExpr separator = SqlText.string(pick(List.of(",", "", " | ", "-")));
                        return new SqlExpr.FunctionCall(chance(70) ? "string_agg" : "listagg", List.of(x, separator),
                                distinct, false, own, P);
                    }
                }
                case 9 -> {
                    // DuckDB's JDBC driver does not read a list of intervals, and may crash on a large one.
                    if (plain && !floating && t != DataType.INTERVAL) {
                        return new SqlExpr.FunctionCall("list", List.of(x), distinct, false, own, P);
                    }
                }
                default -> {
                    if (plain && !floating) {
                        return new SqlExpr.FunctionCall(pick(List.of("first", "last", "any_value", "arbitrary")),
                                List.of(x), false, false, own, P);
                    }
                }
            }
        }
        return countStar();
    }

    // ------------------------------------------------------------- windows

    /**
     * A window in the making: the spec written after {@code OVER}, whether
     * its order is total, and the type of its only order key, {@code null}
     * if it has none or several.
     */
    private record Spec(WindowSpec over, boolean total, DataType key) {
    }

    /**
     * A call of a window function over the columns in scope, over a window
     * of its own, one of the {@code WINDOW} clause, or a new one there.
     * Either the order of the window is total, by all columns in scope, so
     * that every function and frame has one result, or it has one key at
     * most, whose peers the call takes together: a ranking with peers, or
     * an aggregate over a {@code RANGE} or {@code GROUPS} frame, which takes
     * or leaves out whole groups of peers.
     *
     * @param named the windows of the {@code WINDOW} clause, to which this one may add
     * @param specs the specs of those windows, by name
     */
    private SqlExpr.Window window(List<Column> scope, ExpressionGenerator g, SqlText text,
            List<QueryBody.NamedWindow> named, Map<String, Spec> specs) {
        Spec spec;
        WindowSpec over;
        if (!specs.isEmpty() && chance(40)) {
            String name = pick(new ArrayList<>(specs.keySet()));
            spec = specs.get(name);
            over = WindowSpec.named(name);
        } else {
            spec = spec(scope, g, text);
            over = spec.over();
            if (chance(25)) {
                String name = "win" + names++;
                named.add(new QueryBody.NamedWindow(name, spec.over(), P));
                specs.put(name, spec);
                over = WindowSpec.named(name);
            }
        }
        if (!spec.total()) {
            if (chance(30)) {
                String f = pick(List.of("rank", "dense_rank", "percent_rank", "cume_dist"));
                return new SqlExpr.Window(call(f, List.of()), over, false, null, P);
            }
            return windowAggregate(g, text, over, false);
        }
        Expression value = chance(60) && !scope.isEmpty() ? g.leaf() : g.any(2);
        SqlExpr x = text.of(value);
        boolean ignoreNulls = chance(20);
        return switch (random.nextInt(9)) {
            case 0 -> new SqlExpr.Window(call(pick(List.of("row_number", "rank", "dense_rank", "percent_rank",
                    "cume_dist")), List.of()), over, false, null, P);
            case 1 -> new SqlExpr.Window(call("ntile", List.of(count(g, text, 1))), over, false, null, P);
            case 2, 3 -> {
                List<SqlExpr> arguments = new ArrayList<>(List.of(x));
                if (chance(70)) {
                    arguments.add(count(g, text, 0));
                    if (chance(50)) {
                        arguments.add(text.of(g.like(value.type(), 1)));
                    }
                }
                yield new SqlExpr.Window(call(chance(50) ? "lag" : "lead", arguments), over, ignoreNulls, null, P);
            }
            case 4 -> new SqlExpr.Window(call(chance(50) ? "first_value" : "last_value", List.of(x)), over,
                    ignoreNulls, null, P);
            case 5 -> new SqlExpr.Window(call("nth_value", List.of(x, count(g, text, 1))), over, ignoreNulls, null,
                    P);
            default -> windowAggregate(g, text, over, true);
        };
    }

    private static SqlExpr.FunctionCall call(String name, List<SqlExpr> arguments) {
        return new SqlExpr.FunctionCall(name, arguments, false, false, List.of(), P);
    }

    /** A count, an offset or a position: mostly a small number from the least on, else an expression. */
    private SqlExpr count(ExpressionGenerator g, SqlText text, int least) {
        if (chance(75)) {
            return number(random.nextInt(least, 4));
        }
        return text.of(g.like(chance(50) ? DataType.INTEGER : DataType.BIGINT, 1));
    }

    /** A window over the columns in scope, with a partition, an order and a frame, each of which may be absent. */
    private Spec spec(List<Column> scope, ExpressionGenerator g, SqlText text) {
        List<SqlExpr> partition = new ArrayList<>();
        for (int i = random.nextInt(0, 3); i > 0 && !scope.isEmpty(); i--) {
            partition.add(text.of(chance(70) ? g.leaf() : g.any(1)));
        }
        boolean total = chance(60);
        List<OrderItem> order = new ArrayList<>();
        DataType key = null;
        if (total) {
            List<Column> columns = new ArrayList<>(scope);
            Collections.shuffle(columns, new java.util.Random(random.nextLong()));
            for (Column c : columns) {
                order.add(new OrderItem(reference(c, scope), chance(60), chance(30)));
            }
            key = columns.size() == 1 ? columns.get(0).type() : null;
        } else if (chance(85)) {
            Expression e = chance(70) && !scope.isEmpty() ? g.leaf() : g.any(1);
            if (ExpressionGenerator.plain(e.type())) {
                order.add(new OrderItem(text.of(e), chance(60), chance(30)));
                key = e.type();
            }
        }
        return new Spec(new WindowSpec(null, partition, order, frame(total, key)), total, key);
    }

    /**
     * A frame, or {@code null} for the default one: rows, groups of peers or
     * a range of values of the only order key, with offsets mostly small and
     * positive, and sometimes rows left out. Without a total order, there
     * are no frames of rows.
     */
    private WindowSpec.Frame frame(boolean total, DataType key) {
        if (chance(25)) {
            return null;
        }
        WindowSpec.FrameUnit unit = total ? pick(List.of(WindowSpec.FrameUnit.values()))
                : pick(List.of(WindowSpec.FrameUnit.RANGE, WindowSpec.FrameUnit.GROUPS));
        boolean offsets = unit != WindowSpec.FrameUnit.RANGE
                || key != null && (key.isNumeric() || key == DataType.DATE || key == DataType.TIMESTAMP
                        || key == DataType.TIMESTAMPTZ);
        for (int attempt = 0; attempt < 10; attempt++) {
            WindowSpec.BoundKind start = pick(List.of(WindowSpec.BoundKind.UNBOUNDED_PRECEDING,
                    WindowSpec.BoundKind.PRECEDING, WindowSpec.BoundKind.CURRENT_ROW, WindowSpec.BoundKind.FOLLOWING));
            WindowSpec.BoundKind end = pick(List.of(WindowSpec.BoundKind.PRECEDING, WindowSpec.BoundKind.CURRENT_ROW,
                    WindowSpec.BoundKind.FOLLOWING, WindowSpec.BoundKind.UNBOUNDED_FOLLOWING));
            if (start.ordinal() > end.ordinal() || !offsets && (start.hasOffset() || end.hasOffset())) {
                continue;
            }
            WindowSpec.Exclusion exclusion = chance(70) ? WindowSpec.Exclusion.NO_OTHERS
                    : pick(List.of(WindowSpec.Exclusion.values()));
            return new WindowSpec.Frame(unit, bound(start, unit, key), bound(end, unit, key), exclusion);
        }
        return null;
    }

    /** A bound with an offset where its kind has one: a number, or an interval over a date or a timestamp. */
    private WindowSpec.Bound bound(WindowSpec.BoundKind kind, WindowSpec.FrameUnit unit, DataType key) {
        if (!kind.hasOffset()) {
            return WindowSpec.Bound.of(kind);
        }
        int n = chance(5) ? -1 : random.nextInt(0, 4);
        SqlExpr offset;
        if (unit != WindowSpec.FrameUnit.RANGE) {
            offset = chance(10) ? new SqlExpr.Literal(LiteralKind.NUMBER, n + ".5", P) : number(n);
        } else if (key == DataType.DATE && chance(50)) {
            offset = number(n * random.nextInt(1, 40));
        } else if (key == DataType.DATE || key == DataType.TIMESTAMP || key == DataType.TIMESTAMPTZ) {
            // An instant only by the units of time, which are the same in every time zone.
            String u = key == DataType.DATE ? pick(List.of("DAY", "MONTH", "YEAR", "HOUR"))
                    : key == DataType.TIMESTAMPTZ ? pick(List.of("HOUR", "MINUTE", "SECOND"))
                    : pick(List.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND"));
            long count = u.equals("YEAR") || u.equals("MONTH") ? n : n * random.nextLong(1, 100);
            offset = new SqlExpr.Interval(Long.toString(count), u, P);
        } else if (!key.isIntegral() && chance(50)) {
            offset = new SqlExpr.Literal(LiteralKind.NUMBER, n + "." + random.nextInt(1, 10), P);
        } else {
            offset = number(n);
        }
        return new WindowSpec.Bound(kind, offset);
    }

    /**
     * An aggregate over a window, sometimes with a filter. The aggregates
     * whose result depends on the order of their values are ordered by
     * them, but for {@code first} and {@code last} over a total order,
     * which DuckDB takes as {@code first_value} and {@code last_value}.
     */
    private SqlExpr.Window windowAggregate(ExpressionGenerator g, SqlText text, WindowSpec over, boolean total) {
        SqlExpr.FunctionCall call = null;
        for (int attempt = 0; attempt < 20 && call == null; attempt++) {
            Expression argument = chance(60) ? g.leaf() : g.any(2);
            DataType t = argument.type();
            boolean floating = t == DataType.FLOAT || t == DataType.DOUBLE;
            boolean plain = ExpressionGenerator.plain(t);
            boolean distinct = chance(20);
            SqlExpr x = text.of(argument);
            List<OrderItem> own = List.of(new OrderItem(x, chance(60), chance(30)));
            switch (random.nextInt(11)) {
                case 0 -> call = countStar();
                case 1 -> call = new SqlExpr.FunctionCall("count", List.of(x), distinct, false, List.of(), P);
                case 2, 3 -> {
                    if (t.isIntegral() || t instanceof DataType.Decimal) {
                        call = new SqlExpr.FunctionCall(chance(50) ? "sum" : "avg", List.of(x), distinct, false,
                                List.of(), P);
                    }
                }
                case 4, 5 -> {
                    if (plain) {
                        call = new SqlExpr.FunctionCall(chance(50) ? "min" : "max", List.of(x), distinct, false,
                                List.of(), P);
                    }
                }
                case 6 -> {
                    if (t == DataType.BOOLEAN) {
                        call = call(chance(50) ? "bool_and" : "bool_or", List.of(x));
                    }
                }
                case 7 -> {
                    if (plain && !floating) {
                        SqlExpr separator = SqlText.string(pick(List.of(",", "", "-")));
                        call = new SqlExpr.FunctionCall(chance(70) ? "string_agg" : "listagg", List.of(x, separator),
                                distinct, false, own, P);
                    }
                }
                case 8 -> {
                    // DuckDB's JDBC driver does not read a list of intervals, and may crash on a large one.
                    if (plain && !floating && t != DataType.INTERVAL) {
                        call = new SqlExpr.FunctionCall("list", List.of(x), distinct, false, own, P);
                    }
                }
                case 9 -> {
                    if (plain && !floating) {
                        call = new SqlExpr.FunctionCall(chance(50) ? "any_value" : "arbitrary", List.of(x), false,
                                false, own, P);
                    }
                }
                default -> {
                    if (total) {
                        call = call(chance(50) ? "first" : "last", List.of(x));
                    }
                }
            }
        }
        if (call == null) {
            call = countStar();
        }
        SqlExpr filter = chance(20) ? text.of(g.condition(1)) : null;
        return new SqlExpr.Window(call, over, false, filter, P);
    }

    /**
     * A window function over the groups and aggregates of an aggregation,
     * which the order of peers does not change: a ranking ordered by one of
     * them, or an aggregate of one over the default frame.
     *
     * @param keys the groups and aggregates
     * @param groups the groups, by which it may be partitioned
     */
    private SqlExpr.Window windowOverGroups(List<SqlExpr> keys, List<SqlExpr> groups) {
        List<SqlExpr> partition = !groups.isEmpty() && chance(40) ? List.of(pick(groups)) : List.of();
        List<OrderItem> order = chance(70) ? List.of(new OrderItem(pick(keys), chance(60), chance(30))) : List.of();
        WindowSpec over = new WindowSpec(null, partition, order, null);
        if (!order.isEmpty() && chance(50)) {
            String f = pick(List.of("rank", "dense_rank", "percent_rank", "cume_dist"));
            return new SqlExpr.Window(call(f, List.of()), over, false, null, P);
        }
        SqlExpr.FunctionCall call = chance(50) ? call(chance(50) ? "sum" : "count", List.of(countStar()))
                : call(chance(50) ? "min" : "max", List.of(pick(keys)));
        return new SqlExpr.Window(call, over, false, null, P);
    }

    // ---------------------------------------------------------- subqueries

    /**
     * A table or, at times, a common table under a fresh alias, as the
     * {@code FROM} of a subquery, with its columns qualified by the alias.
     */
    private Relation inner() {
        String alias = "b" + names++;
        List<Column> columns = new ArrayList<>();
        if (!ctes.isEmpty() && chance(20)) {
            String name = pick(new ArrayList<>(ctes.keySet()));
            for (Column c : ctes.get(name)) {
                columns.add(new Column(alias, c.name(), c.type(), c.sample()));
            }
            return new Relation(new FromItem.TableName(List.of(name), alias(alias), P), columns);
        }
        String table = pick(new ArrayList<>(tables.keySet()));
        List<Schema.Field> fields = tables.get(table).fields();
        for (int i = 0; i < fields.size(); i++) {
            columns.add(new Column(alias, fields.get(i).name(), fields.get(i).type(), sample(table, i)));
        }
        return new Relation(new FromItem.TableName(List.of(table), alias(alias), P), columns);
    }

    /** A qualified name, which a subquery cannot take for one of its own columns. */
    private static SqlExpr qualified(Column c) {
        return new SqlExpr.Identifier(List.of(c.qualifier(), c.name()), P);
    }

    private SqlExpr.BinaryOp comparison() {
        return pick(List.of(SqlExpr.BinaryOp.EQUAL, SqlExpr.BinaryOp.NOT_EQUAL, SqlExpr.BinaryOp.LESS,
                SqlExpr.BinaryOp.LESS_EQUAL, SqlExpr.BinaryOp.GREATER, SqlExpr.BinaryOp.GREATER_EQUAL));
    }

    /**
     * The {@code WHERE} of a subquery, {@code null} for none: an equality of
     * its key {@code k} with an integer column around it, where it does not
     * aggregate a comparison of one of its columns with one around it, and a
     * condition over its own columns.
     *
     * @param outer the columns of the query around it, none for an uncorrelated subquery
     */
    private SqlExpr innerWhere(Relation inner, List<Column> outer, boolean aggregates) {
        List<SqlExpr> conjuncts = new ArrayList<>();
        List<Column> integers = outer.stream().filter(c -> c.qualifier() != null && c.type().isIntegral()).toList();
        if (!integers.isEmpty() && chance(65)) {
            List<Column> keys = integers.stream().filter(c -> c.name().equalsIgnoreCase("k")).toList();
            Column o = !keys.isEmpty() && chance(80) ? pick(keys) : pick(integers);
            conjuncts.add(new SqlExpr.Binary(SqlExpr.BinaryOp.EQUAL, qualified(inner.columns().get(0)), qualified(o),
                    P));
        }
        if (!aggregates && chance(30)) {
            Column i = pick(inner.columns());
            List<Column> same = outer.stream().filter(c -> c.qualifier() != null && c.type().equals(i.type()))
                    .toList();
            if (!same.isEmpty()) {
                conjuncts.add(new SqlExpr.Binary(pick(List.of(SqlExpr.BinaryOp.NOT_EQUAL, SqlExpr.BinaryOp.LESS,
                        SqlExpr.BinaryOp.GREATER_EQUAL)), qualified(i), qualified(pick(same)), P));
            }
        }
        if (chance(40)) {
            ExpressionGenerator g = generator(inner.columns());
            SqlText text = new SqlText(random, inner.columns().stream().map(SqlGenerator::qualified).toList());
            conjuncts.add(text.of(g.condition(1)));
        }
        Collections.shuffle(conjuncts, new java.util.Random(random.nextLong()));
        SqlExpr where = null;
        for (SqlExpr c : conjuncts) {
            where = where == null ? c : new SqlExpr.Binary(SqlExpr.BinaryOp.AND, where, c, P);
        }
        return where;
    }

    /** An aggregate over a column of a subquery, or a count of its rows. */
    private Call innerAggregate(Relation inner) {
        Column c = pick(inner.columns());
        List<SqlExpr> argument = List.of(qualified(c));
        int kind = random.nextInt(10);
        if (kind < 2) {
            return new Call(countStar(), DataType.BIGINT);
        }
        if (kind < 4) {
            return new Call(new SqlExpr.FunctionCall("count", argument, chance(20), false, List.of(), P),
                    DataType.BIGINT);
        }
        if (kind < 7 && (c.type().isIntegral() || c.type() instanceof DataType.Decimal)) {
            return new Call(new SqlExpr.FunctionCall(chance(50) ? "sum" : "avg", argument, false, false, List.of(), P),
                    c.type());
        }
        return new Call(new SqlExpr.FunctionCall(chance(50) ? "min" : "max", argument, false, false, List.of(), P),
                c.type());
    }

    /** A subquery of one aggregate over a table. */
    private static Query aggregating(Relation inner, Call call, SqlExpr where) {
        return Query.of(new QueryBody.Select(false, List.of(new SelectItem.ExpressionItem(call.call(), null)),
                inner.item(), where, null, null, P));
    }

    /**
     * A condition with a subquery over a table: {@code [NOT] EXISTS},
     * {@code [NOT] IN}, or a comparison with an aggregate or with the first
     * value of a column in order.
     *
     * @param outer the columns of the query around it
     * @param g generates expressions over those columns
     * @param text writes them
     */
    private SqlExpr subqueryCondition(List<Column> outer, ExpressionGenerator g, SqlText text) {
        Relation inner = inner();
        int form = random.nextInt(100);
        if (form < 35) {
            SelectItem item = chance(25) ? new SelectItem.Star(List.of(), List.of(), List.of(), P)
                    : new SelectItem.ExpressionItem(number(1), null);
            return new SqlExpr.Exists(Query.of(new QueryBody.Select(false, List.of(item), inner.item(),
                    innerWhere(inner, outer, false), null, null, P)), chance(40), P);
        }
        if (form >= 60 && form < 80) {
            Call call = innerAggregate(inner);
            return new SqlExpr.Binary(comparison(), text.of(g.like(call.type(), 1)), new SqlExpr.ScalarSubquery(
                    aggregating(inner, call, innerWhere(inner, outer, true)), P), P);
        }
        Column c = pick(inner.columns());
        SqlExpr value = text.of(g.like(c.type(), 1));
        if (form < 60 && chance(15) && inner.columns().size() > 1) {
            // A row of two values; DuckDB compares no row with a correlated subquery.
            Column d = pick(inner.columns());
            Query q = Query.of(new QueryBody.Select(chance(20), List.of(new SelectItem.ExpressionItem(qualified(c),
                    null), new SelectItem.ExpressionItem(qualified(d), null)), inner.item(),
                    innerWhere(inner, List.of(), false), null, null, P));
            return new SqlExpr.InQuery(new SqlExpr.Row(List.of(value, text.of(g.like(d.type(), 1))), P), q,
                    chance(40), P);
        }
        if (form < 60) {
            Query q = Query.of(new QueryBody.Select(chance(20), List.of(new SelectItem.ExpressionItem(qualified(c),
                    null)), inner.item(), innerWhere(inner, outer, false), null, null, P));
            return chance(25) ? new SqlExpr.Quantified(comparison(), value, q, chance(40), P)
                    : new SqlExpr.InQuery(value, q, chance(40), P);
        }
        // A LIMIT keeps the subquery uncorrelated, and the order of the only column makes the first row unique.
        Query first = new Query(List.of(), new QueryBody.Select(false, List.of(new SelectItem.ExpressionItem(
                qualified(c), null)), inner.item(), innerWhere(inner, List.of(), false), null, null, P),
                List.of(OrderItem.all(chance(50), chance(30))), number(1), null);
        return new SqlExpr.Binary(comparison(), value, new SqlExpr.ScalarSubquery(first, P), P);
    }

    // ------------------------------------------------- VALUES and set operations

    private QueryBody.Values values() {
        int width = random.nextInt(1, 4);
        List<DataType> types = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            types.add(pick(ExpressionGenerator.TYPES));
        }
        ExpressionGenerator literals = new ExpressionGenerator(random, List.of());
        SqlText text = new SqlText(random, List.of());
        List<List<SqlExpr>> rows = new ArrayList<>();
        for (int r = random.nextInt(1, 5); r > 0; r--) {
            List<SqlExpr> row = new ArrayList<>();
            for (DataType t : types) {
                // Sometimes an integer where the column holds decimals, to unite the two.
                DataType type = t instanceof DataType.Decimal && chance(20) ? DataType.INTEGER : t;
                row.add(text.of(literals.literal(type)));
            }
            rows.add(row);
        }
        return new QueryBody.Values(rows, P);
    }

    private QueryBody setOperation() {
        int width = random.nextInt(1, 4);
        QueryBody left = branch(width, null);
        QueryBody right = branch(width, types(left));
        QueryBody.SetKind kind = pick(List.of(QueryBody.SetKind.values()));
        return new QueryBody.SetOperation(kind, chance(50), left, right);
    }

    private List<DataType> types(QueryBody body) {
        Schema schema = schema(new Query(with, body, List.of(), null, null));
        return schema == null ? null : schema.types();
    }

    /** One side of a set operation: a {@code SELECT} of a number of items, of types close to the given ones. */
    private QueryBody branch(int width, List<DataType> like) {
        Relation from = from(1);
        List<Column> scope = from.columns();
        ExpressionGenerator g = generator(scope);
        SqlText text = new SqlText(random, references(scope, scope));
        List<SelectItem> items = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            Expression e = like != null && i < like.size() ? g.like(like.get(i), 2) : chance(50) ? g.leaf() : g.any(2);
            items.add(new SelectItem.ExpressionItem(text.of(e), chance(30) ? pick(ALIASES) : null));
        }
        SqlExpr where = chance(40) ? text.of(g.condition(2)) : null;
        return new QueryBody.Select(chance(10), items, from.item(), where, null, null, P);
    }
}
