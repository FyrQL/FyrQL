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

import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.OrderItem;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.WindowSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The smaller variants of a query in SQL syntax, for the {@link Minimizer}:
 * without {@code ORDER BY}, {@code LIMIT}, a common table, a clause or an
 * item of the select list; one side of a join or a set operation in place
 * of both; an expression replaced by an operand or by {@code NULL}; fewer
 * rows of {@code VALUES}; a subquery in an expression replaced by a value of
 * the same meaning for some rows; a window function replaced by its call,
 * or without its frame, partition or order; a window of the {@code WINDOW}
 * clause written out where it is used; without {@code QUALIFY}; and the
 * same inside subqueries and common tables. Many variants do not bind; they fail to show the difference and
 * are passed over.
 */
final class QueryShrinker {

    private static final Position P = Position.UNKNOWN;
    private static final SqlExpr NULL = new SqlExpr.Literal(SqlExpr.LiteralKind.NULL, "NULL", P);

    private QueryShrinker() {
    }

    /** The smaller variants of a generated query; the order is compared only while the query keeps its order. */
    static List<SqlGenerator.Generated> candidates(SqlGenerator.Generated generated) {
        List<SqlGenerator.Generated> result = new ArrayList<>();
        for (Query q : query(generated.query())) {
            result.add(new SqlGenerator.Generated(q, generated.ordered() && q.orderBy().equals(
                    generated.query().orderBy())));
        }
        return result;
    }

    static List<Query> query(Query q) {
        List<Query> result = new ArrayList<>();
        if (!q.orderBy().isEmpty() && q.limit() == null && q.offset() == null) {
            result.add(new Query(q.with(), q.body(), List.of(), null, null));
        }
        if (q.limit() != null || q.offset() != null) {
            result.add(new Query(q.with(), q.body(), q.orderBy(), null, null));
        }
        for (int i = 0; i < q.with().size(); i++) {
            List<Query.CommonTable> with = new ArrayList<>(q.with());
            with.remove(i);
            result.add(new Query(with, q.body(), q.orderBy(), q.limit(), q.offset()));
        }
        for (QueryBody b : body(q.body())) {
            result.add(new Query(q.with(), b, q.orderBy(), q.limit(), q.offset()));
        }
        for (int i = 0; i < q.with().size(); i++) {
            Query.CommonTable table = q.with().get(i);
            for (Query smaller : query(table.query())) {
                List<Query.CommonTable> with = new ArrayList<>(q.with());
                with.set(i, new Query.CommonTable(table.name(), table.columns(), smaller, table.position()));
                result.add(new Query(with, q.body(), q.orderBy(), q.limit(), q.offset()));
            }
        }
        return result;
    }

    private static List<QueryBody> body(QueryBody body) {
        List<QueryBody> result = new ArrayList<>();
        switch (body) {
            case QueryBody.Select s -> select(s, result);
            case QueryBody.SetOperation s -> {
                result.add(s.left());
                result.add(s.right());
                for (QueryBody b : body(s.left())) {
                    result.add(new QueryBody.SetOperation(s.kind(), s.all(), b, s.right()));
                }
                for (QueryBody b : body(s.right())) {
                    result.add(new QueryBody.SetOperation(s.kind(), s.all(), s.left(), b));
                }
            }
            case QueryBody.Values v -> {
                for (int i = 0; i < v.rows().size() && v.rows().size() > 1; i++) {
                    List<List<SqlExpr>> rows = new ArrayList<>(v.rows());
                    rows.remove(i);
                    result.add(new QueryBody.Values(rows, v.position()));
                }
            }
            case QueryBody.Parenthesized p -> {
                for (Query q : query(p.query())) {
                    result.add(new QueryBody.Parenthesized(q));
                }
            }
        }
        return result;
    }

    private static void select(QueryBody.Select s, List<QueryBody> result) {
        if (s.where() != null) {
            result.add(with(s, s.distinct(), s.items(), s.from(), null, s.groupBy(), s.having()));
        }
        if (s.having() != null) {
            result.add(with(s, s.distinct(), s.items(), s.from(), s.where(), s.groupBy(), null));
        }
        if (s.groupBy() != null) {
            result.add(with(s, s.distinct(), s.items(), s.from(), s.where(), null, s.having()));
        }
        if (s.distinct()) {
            result.add(with(s, false, s.items(), s.from(), s.where(), s.groupBy(), s.having()));
        }
        for (int i = 0; i < s.items().size() && s.items().size() > 1; i++) {
            List<SelectItem> items = new ArrayList<>(s.items());
            items.remove(i);
            result.add(with(s, s.distinct(), items, s.from(), s.where(), s.groupBy(), s.having()));
        }
        if (s.from() != null) {
            for (FromItem f : from(s.from())) {
                result.add(with(s, s.distinct(), s.items(), f, s.where(), s.groupBy(), s.having()));
            }
        }
        for (int i = 0; i < s.items().size(); i++) {
            if (s.items().get(i) instanceof SelectItem.ExpressionItem item) {
                for (SqlExpr e : expression(item.expression())) {
                    List<SelectItem> items = new ArrayList<>(s.items());
                    items.set(i, new SelectItem.ExpressionItem(e, item.alias()));
                    result.add(with(s, s.distinct(), items, s.from(), s.where(), s.groupBy(), s.having()));
                }
            }
        }
        if (s.where() != null) {
            for (SqlExpr e : expression(s.where())) {
                result.add(with(s, s.distinct(), s.items(), s.from(), e, s.groupBy(), s.having()));
            }
        }
        if (s.having() != null) {
            for (SqlExpr e : expression(s.having())) {
                result.add(with(s, s.distinct(), s.items(), s.from(), s.where(), s.groupBy(), e));
            }
        }
        for (int i = 0; i < s.windows().size(); i++) {
            QueryBody.NamedWindow window = s.windows().get(i);
            List<QueryBody.NamedWindow> windows = new ArrayList<>(s.windows());
            windows.remove(i);
            List<SelectItem> items = new ArrayList<>();
            for (SelectItem item : s.items()) {
                items.add(item instanceof SelectItem.ExpressionItem x
                        ? new SelectItem.ExpressionItem(inline(x.expression(), window), x.alias()) : item);
            }
            result.add(new QueryBody.Select(s.distinct(), items, s.from(), s.where(), s.groupBy(), s.having(),
                    windows, s.qualify() == null ? null : inline(s.qualify(), window), P));
        }
        if (s.qualify() != null) {
            result.add(new QueryBody.Select(s.distinct(), s.items(), s.from(), s.where(), s.groupBy(), s.having(),
                    s.windows(), null, P));
            for (SqlExpr e : expression(s.qualify())) {
                result.add(new QueryBody.Select(s.distinct(), s.items(), s.from(), s.where(), s.groupBy(), s.having(),
                        s.windows(), e, P));
            }
        }
    }

    /** The select with other clauses, keeping its {@code WINDOW} and {@code QUALIFY}. */
    private static QueryBody.Select with(QueryBody.Select s, boolean distinct, List<SelectItem> items, FromItem from,
            SqlExpr where, QueryBody.GroupBy groupBy, SqlExpr having) {
        return new QueryBody.Select(distinct, items, from, where, groupBy, having, s.windows(), s.qualify(), P);
    }

    /** An expression with a window of the {@code WINDOW} clause written out wherever it names the window. */
    private static SqlExpr inline(SqlExpr e, QueryBody.NamedWindow window) {
        if (e instanceof SqlExpr.Window w) {
            return window.name().equals(w.over().base())
                    ? new SqlExpr.Window(w.call(), window.window(), w.ignoreNulls(), w.filter(), P) : w;
        }
        List<SqlExpr> children = new ArrayList<>();
        for (SqlExpr c : children(e)) {
            children.add(inline(c, window));
        }
        return rebuild(e, children);
    }

    private static List<FromItem> from(FromItem item) {
        List<FromItem> result = new ArrayList<>();
        switch (item) {
            case FromItem.Join j -> {
                result.add(j.left());
                result.add(j.right());
                if (j.kind() != FromItem.JoinKind.INNER && j.kind() != FromItem.JoinKind.CROSS && j.on() != null) {
                    result.add(new FromItem.Join(FromItem.JoinKind.INNER, j.left(), j.right(), j.on(), j.using(),
                            j.natural(), j.position()));
                }
                for (FromItem f : from(j.left())) {
                    result.add(new FromItem.Join(j.kind(), f, j.right(), j.on(), j.using(), j.natural(), j.position()));
                }
                for (FromItem f : from(j.right())) {
                    result.add(new FromItem.Join(j.kind(), j.left(), f, j.on(), j.using(), j.natural(), j.position()));
                }
                if (j.on() != null) {
                    for (SqlExpr e : expression(j.on())) {
                        result.add(new FromItem.Join(j.kind(), j.left(), j.right(), e, j.using(), j.natural(),
                                j.position()));
                    }
                }
            }
            case FromItem.Subquery s -> {
                for (Query q : query(s.query())) {
                    result.add(new FromItem.Subquery(q, s.alias(), s.position()));
                }
            }
            default -> {
            }
        }
        return result;
    }

    /** The smaller variants of an expression: an operand, {@code NULL}, or the same with a smaller operand. */
    static List<SqlExpr> expression(SqlExpr e) {
        List<SqlExpr> result = new ArrayList<>();
        List<SqlExpr> children = children(e);
        result.addAll(children);
        if (!(e instanceof SqlExpr.Literal)) {
            result.add(NULL);
        }
        // A subquery may go as a whole, or it may shrink itself.
        switch (e) {
            case SqlExpr.Exists x -> {
                result.add(new SqlExpr.Literal(SqlExpr.LiteralKind.BOOLEAN, "true", P));
                for (Query q : query(x.query())) {
                    result.add(new SqlExpr.Exists(q, x.negated(), P));
                }
            }
            case SqlExpr.InQuery in -> {
                result.add(new SqlExpr.IsNull(in.value(), true, P));
                for (Query q : query(in.query())) {
                    result.add(new SqlExpr.InQuery(in.value(), q, in.negated(), P));
                }
            }
            case SqlExpr.ScalarSubquery s -> {
                for (Query q : query(s.query())) {
                    result.add(new SqlExpr.ScalarSubquery(q, P));
                }
            }
            // A window function may go for its call without the window, or lose a part of its window.
            case SqlExpr.Window w -> {
                result.add(w.call());
                WindowSpec over = w.over();
                if (over.base() == null) {
                    if (over.frame() != null) {
                        result.add(new SqlExpr.Window(w.call(), new WindowSpec(null, over.partitionBy(),
                                over.orderBy(), null), w.ignoreNulls(), w.filter(), P));
                    }
                    if (!over.partitionBy().isEmpty()) {
                        result.add(new SqlExpr.Window(w.call(), new WindowSpec(null, List.of(), over.orderBy(),
                                over.frame()), w.ignoreNulls(), w.filter(), P));
                    }
                    if (!over.orderBy().isEmpty()) {
                        result.add(new SqlExpr.Window(w.call(), new WindowSpec(null, over.partitionBy(), List.of(),
                                over.frame()), w.ignoreNulls(), w.filter(), P));
                    }
                }
                if (w.filter() != null) {
                    result.add(new SqlExpr.Window(w.call(), over, w.ignoreNulls(), null, P));
                }
            }
            default -> {
            }
        }
        for (int i = 0; i < children.size(); i++) {
            for (SqlExpr smaller : expression(children.get(i))) {
                List<SqlExpr> changed = new ArrayList<>(children);
                changed.set(i, smaller);
                try {
                    result.add(rebuild(e, changed));
                } catch (IllegalArgumentException x) {
                    // A form that needs another kind of operand.
                }
            }
        }
        return result;
    }

    private static List<SqlExpr> children(SqlExpr e) {
        List<SqlExpr> c = new ArrayList<>();
        switch (e) {
            case SqlExpr.Literal l -> {
            }
            case SqlExpr.Identifier i -> {
            }
            case SqlExpr.Binary b -> {
                c.add(b.left());
                c.add(b.right());
            }
            case SqlExpr.Unary u -> c.add(u.operand());
            case SqlExpr.Between b -> {
                c.add(b.value());
                c.add(b.low());
                c.add(b.high());
            }
            case SqlExpr.InList in -> {
                c.add(in.value());
                c.addAll(in.list());
            }
            case SqlExpr.InQuery in -> c.add(in.value());
            case SqlExpr.Exists x -> {
            }
            case SqlExpr.ScalarSubquery s -> {
            }
            case SqlExpr.Like l -> {
                c.add(l.value());
                c.add(l.pattern());
            }
            case SqlExpr.IsNull n -> c.add(n.operand());
            case SqlExpr.IsDistinct d -> {
                c.add(d.left());
                c.add(d.right());
            }
            case SqlExpr.Case cs -> {
                if (cs.operand() != null) {
                    c.add(cs.operand());
                }
                for (SqlExpr.When w : cs.whens()) {
                    c.add(w.condition());
                    c.add(w.result());
                }
                if (cs.otherwise() != null) {
                    c.add(cs.otherwise());
                }
            }
            case SqlExpr.Cast cast -> c.add(cast.operand());
            case SqlExpr.FunctionCall f -> {
                c.addAll(f.arguments());
                for (OrderItem o : f.orderBy()) {
                    c.add(o.expression());
                }
            }
            case SqlExpr.Index x -> {
                c.add(x.base());
                c.add(x.index());
            }
            case SqlExpr.StructValue s -> c.addAll(s.values());
            case SqlExpr.Interval i -> {
            }
            case SqlExpr.Window w -> c.addAll(w.operands());
        }
        return c;
    }

    private static SqlExpr rebuild(SqlExpr e, List<SqlExpr> c) {
        return switch (e) {
            case SqlExpr.Literal l -> l;
            case SqlExpr.Identifier i -> i;
            case SqlExpr.Binary b -> new SqlExpr.Binary(b.op(), c.get(0), c.get(1), P);
            case SqlExpr.Unary u -> new SqlExpr.Unary(u.op(), c.get(0), P);
            case SqlExpr.Between b -> new SqlExpr.Between(c.get(0), c.get(1), c.get(2), b.negated(), P);
            case SqlExpr.InList in -> new SqlExpr.InList(c.get(0), c.subList(1, c.size()), in.negated(), P);
            case SqlExpr.InQuery in -> new SqlExpr.InQuery(c.get(0), in.query(), in.negated(), P);
            case SqlExpr.Exists x -> x;
            case SqlExpr.ScalarSubquery s -> s;
            case SqlExpr.Like l -> new SqlExpr.Like(c.get(0), c.get(1), l.caseInsensitive(), l.negated(), P);
            case SqlExpr.IsNull n -> new SqlExpr.IsNull(c.get(0), n.negated(), P);
            case SqlExpr.IsDistinct d -> new SqlExpr.IsDistinct(c.get(0), c.get(1), d.negated(), P);
            case SqlExpr.Case cs -> {
                int k = 0;
                SqlExpr operand = cs.operand() != null ? c.get(k++) : null;
                List<SqlExpr.When> whens = new ArrayList<>();
                for (int i = 0; i < cs.whens().size(); i++) {
                    whens.add(new SqlExpr.When(c.get(k++), c.get(k++)));
                }
                yield new SqlExpr.Case(operand, whens, cs.otherwise() != null ? c.get(k) : null, P);
            }
            case SqlExpr.Cast cast -> new SqlExpr.Cast(c.get(0), cast.type(), cast.safe(), P);
            case SqlExpr.FunctionCall f -> {
                int n = f.arguments().size();
                List<OrderItem> order = new ArrayList<>();
                for (int i = 0; i < f.orderBy().size(); i++) {
                    OrderItem o = f.orderBy().get(i);
                    order.add(new OrderItem(c.get(n + i), o.ascending(), o.nullsFirst()));
                }
                yield new SqlExpr.FunctionCall(f.name(), c.subList(0, n), f.distinct(), f.star(), order, P);
            }
            case SqlExpr.Index x -> new SqlExpr.Index(c.get(0), c.get(1), P);
            case SqlExpr.StructValue s -> new SqlExpr.StructValue(s.names(), c, P);
            case SqlExpr.Interval i -> i;
            case SqlExpr.Window w -> w.withOperands(c);
        };
    }
}
