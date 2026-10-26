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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.SqlExpr;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Turns the subqueries of the expressions of one clause into joins with the
 * rows the clause reads. While the expressions are bound, each subquery is
 * bound on its own and stands for a placeholder column; {@link #attach}
 * then joins the subqueries to the rows, which only appends columns, and
 * {@link #resolve} points the placeholders to them:
 * <ul>
 * <li>A scalar subquery is a {@code SINGLE} join, whose right column is the
 * value.</li>
 * <li>{@code x [NOT] IN (subquery)} is a {@code MARK} join on {@code x},
 * whose mark has the three-valued result of {@code IN}; a row
 * {@code (a, b) [NOT] IN (subquery)} is one on all its values, whose mark is
 * {@code NULL} if no row matches and a value of either side is
 * {@code NULL}, as in DuckDB.</li>
 * <li>{@code x = ANY (subquery)} is {@code x IN (subquery)}, and
 * {@code x op ALL (subquery)} is {@code NOT (x op2 ANY (subquery))} with
 * the opposite comparison {@code op2}. Any other
 * {@code x op ANY (subquery)} is a {@code SINGLE} join with the count of
 * the rows, of the values that are not {@code NULL}, and their least and
 * greatest, which tell the three-valued result.</li>
 * <li>{@code [NOT] EXISTS (subquery)} is a {@code MARK} join with the first
 * row of the subquery.</li>
 * </ul>
 * A condition of a {@code WHERE} clause that is {@code EXISTS},
 * {@code NOT EXISTS} or {@code IN} itself filters the rows with a
 * {@code SEMI} or {@code ANTI} join instead; see {@link #filter}.
 * <p>
 * A subquery that references the rows of the clause, see
 * {@link Correlation}, is joined by the keys and the condition its binding
 * finds: the conditions of its {@code WHERE} clause that read those rows.
 * It is joined as a whole then, as each row of the clause sees other rows
 * of it. A correlated {@code x [NOT] IN} that is a value takes two
 * {@code MARK} joins: one for the rows equal to {@code x}, one for those
 * that make the comparison {@code NULL}; so does a correlated
 * {@code x op ANY}, the first for the rows for which the comparison is
 * true. As in DuckDB, a row is not compared with a correlated subquery.
 */
final class SubqueryJoiner implements ExpressionBinder.Subqueries {

    /** The index of the first placeholder column, far beyond any real one. */
    private static final int PLACEHOLDER = 1 << 30;
    /**
     * The index at which the columns of a subquery start in the condition
     * and the value of its join until it is attached: above the columns of
     * the rows, below the placeholders of a {@link Correlation}.
     */
    private static final int RIGHT = 1 << 28;

    /**
     * A subquery to join.
     *
     * @param leftKeys the keys over the rows
     * @param rightKeys the key columns of the subquery
     * @param condition a condition over the rows and the subquery, whose
     *        columns start at {@link #RIGHT}, or {@code null}
     * @param value the value, the mark or columns of the subquery from {@link #RIGHT} on
     * @param offset the column of the first right column once joined, or -1 before
     */
    private record Join(PlanNode plan, JoinType type, List<Expression> leftKeys, List<Integer> rightKeys,
            Expression condition, Expression value, int offset) {
        Join attached(int at) {
            return new Join(plan, type, leftKeys, rightKeys, condition, value, at);
        }
    }

    /**
     * A bound subquery, with the keys and the condition of its join if it is
     * correlated.
     *
     * @param leftKeys the keys over the rows
     * @param rightKeys the key columns of the subquery
     * @param condition a condition over the rows and the subquery, whose
     *        columns start at {@link #RIGHT}, or {@code null}
     * @param value the value of a scalar subquery over its columns from {@link #RIGHT} on
     * @param columns the number of columns of its select list
     */
    private record Bound(PlanNode plan, boolean correlated, List<Expression> leftKeys, List<Integer> rightKeys,
            Expression condition, Expression value, int columns) {
    }

    private final BiFunction<Query, Correlation, PlanNode> queries;
    private final ExpressionBinder outer;
    private final List<Join> joins = new ArrayList<>();
    /** The value of each subquery bound so far, as the same node of the syntax tree may be bound again. */
    private final Map<SqlExpr, Expression> values = new IdentityHashMap<>();
    /** Each subquery bound so far. */
    private final Map<SqlExpr, Bound> bound = new IdentityHashMap<>();

    /**
     * @param queries binds a subquery in the scope of the query of the
     *        clause, with the references to the rows of the clause
     * @param outer binds the names of the clause, which a subquery may
     *        reference
     */
    SubqueryJoiner(BiFunction<Query, Correlation, PlanNode> queries, ExpressionBinder outer) {
        this.queries = queries;
        this.outer = outer;
    }

    @Override
    public Expression exists(SqlExpr.Exists e) {
        Expression value = values.get(e);
        if (value == null) {
            Bound b = bind(e, e.query(), Correlation.Kind.EXISTS);
            Expression mark = add(b.correlated() ? b : first(b), JoinType.MARK, List.of(), List.of(), null);
            value = new Expression.Coalesce(List.of(mark, no()));
            value = e.negated() ? new Expression.Not(value) : value;
            values.put(e, value);
        }
        return value;
    }

    @Override
    public Expression in(SqlExpr.InQuery e, List<ExpressionBinder.Bound> value, ExpressionBinder binder) {
        Expression result = values.get(e);
        if (result == null) {
            for (ExpressionBinder.Bound v : value) {
                checkValue(v.expression(), e.position());
            }
            Bound b = keyed(e, e.query(), value, binder);
            Expression x = b.leftKeys().get(0);
            if (!b.correlated()) {
                result = add(b, JoinType.MARK, List.of(), List.of(), null);
            } else {
                // TRUE if a row equals x, else NULL if a row or x is NULL, else FALSE; per row of the clause.
                Expression equal = add(b, JoinType.MARK, List.of(), List.of(), null);
                Expression unknown = add(unkeyed(b), JoinType.MARK, List.of(), List.of(),
                        new Expression.Or(new Expression.IsNull(right(b, 0), false), new Expression.IsNull(x, false)));
                result = new Expression.Case(List.of(
                        new Expression.When(new Expression.Coalesce(List.of(equal, no())), yes()),
                        new Expression.When(new Expression.Coalesce(List.of(unknown, no())),
                                new Expression.Literal(DataType.BOOLEAN, null))),
                        no());
            }
            result = e.negated() ? new Expression.Not(result) : result;
            values.put(e, result);
        }
        return result;
    }

    @Override
    public Expression quantified(SqlExpr.Quantified e, ExpressionBinder.Bound value, ExpressionBinder binder) {
        Expression result = values.get(e);
        if (result == null) {
            SqlExpr.InQuery in = asIn(e);
            if (in != null) {
                result = in(in, List.of(value), binder);
            } else if (e.all()) {
                result = new Expression.Not(quantified(new SqlExpr.Quantified(opposite(e.op()), e.value(), e.query(),
                        false, e.position()), value, binder));
            } else {
                result = any(e, value, binder);
            }
            values.put(e, result);
        }
        return result;
    }

    /** {@code x = ANY (S)} as {@code x IN (S)} and {@code x <> ALL (S)} as {@code x NOT IN (S)}, else {@code null}. */
    private static SqlExpr.InQuery asIn(SqlExpr.Quantified e) {
        if (e.op() == SqlExpr.BinaryOp.EQUAL && !e.all() || e.op() == SqlExpr.BinaryOp.NOT_EQUAL && e.all()) {
            return new SqlExpr.InQuery(e.value(), e.query(), e.all(), e.position());
        }
        return null;
    }

    /** The comparison that is true where one is false, and the other way round. */
    private static SqlExpr.BinaryOp opposite(SqlExpr.BinaryOp op) {
        return switch (op) {
            case EQUAL -> SqlExpr.BinaryOp.NOT_EQUAL;
            case NOT_EQUAL -> SqlExpr.BinaryOp.EQUAL;
            case LESS -> SqlExpr.BinaryOp.GREATER_EQUAL;
            case LESS_EQUAL -> SqlExpr.BinaryOp.GREATER;
            case GREATER -> SqlExpr.BinaryOp.LESS_EQUAL;
            case GREATER_EQUAL -> SqlExpr.BinaryOp.LESS;
            default -> throw new IllegalArgumentException("No comparison: " + op);
        };
    }

    private static Expression compare(SqlExpr.BinaryOp op, Expression x, Expression y) {
        Expression.ComparisonOp c = switch (op) {
            case EQUAL -> Expression.ComparisonOp.EQ;
            case NOT_EQUAL -> Expression.ComparisonOp.NE;
            case LESS -> Expression.ComparisonOp.LT;
            case LESS_EQUAL -> Expression.ComparisonOp.LE;
            case GREATER -> Expression.ComparisonOp.GT;
            case GREATER_EQUAL -> Expression.ComparisonOp.GE;
            default -> throw new IllegalArgumentException("No comparison: " + op);
        };
        return new Expression.Comparison(c, x, y);
    }

    /**
     * {@code x op ANY (S)} for an operator other than {@code =}: true if the
     * comparison is true for a row, else {@code NULL} if it is {@code NULL}
     * for one, else false. Over an uncorrelated subquery, from the count of
     * its rows {@code n}, of its values {@code nn}, and their least and
     * greatest: {@code CASE WHEN n = 0 THEN false WHEN x op least (or
     * greatest) THEN true WHEN x IS NULL OR nn < n THEN NULL ELSE false END}.
     */
    private Expression any(SqlExpr.Quantified e, ExpressionBinder.Bound value, ExpressionBinder binder) {
        checkValue(value.expression(), e.position());
        Bound b = keyed(e, e.query(), List.of(value), binder);
        Expression x = b.leftKeys().get(0);
        Expression y = right(b, 0);
        if (b.correlated()) {
            Bound u = unkeyed(b);
            Expression match = add(u, JoinType.MARK, List.of(), List.of(), compare(e.op(), x, y));
            Expression unknown = add(u, JoinType.MARK, List.of(), List.of(),
                    new Expression.Or(new Expression.IsNull(y, false), new Expression.IsNull(x, false)));
            return new Expression.Case(List.of(
                    new Expression.When(new Expression.Coalesce(List.of(match, no())), yes()),
                    new Expression.When(new Expression.Coalesce(List.of(unknown, no())),
                            new Expression.Literal(DataType.BOOLEAN, null))),
                    no());
        }
        DataType t = y.type();
        PlanNode counts = new PlanNode.Aggregate(b.plan(), List.of(), List.of(
                new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false, "n"),
                new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT, 0, false, "nn"),
                new PlanNode.AggregateCall(PlanNode.AggregateFunction.MIN, 0, false, "least"),
                new PlanNode.AggregateCall(PlanNode.AggregateFunction.MAX, 0, false, "greatest")));
        Expression n = new Expression.Column(RIGHT, DataType.BIGINT);
        Expression nn = new Expression.Column(RIGHT + 1, DataType.BIGINT);
        Expression least = new Expression.Column(RIGHT + 2, t);
        Expression greatest = new Expression.Column(RIGHT + 3, t);
        Expression holds = switch (e.op()) {
            case GREATER, GREATER_EQUAL -> compare(e.op(), x, least);
            case LESS, LESS_EQUAL -> compare(e.op(), x, greatest);
            default -> new Expression.Or(compare(e.op(), x, least), compare(e.op(), x, greatest));
        };
        Expression zero = new Expression.Literal(DataType.BIGINT, 0L);
        Expression result = new Expression.Case(List.of(
                new Expression.When(new Expression.Comparison(Expression.ComparisonOp.EQ, n, zero), no()),
                new Expression.When(holds, yes()),
                new Expression.When(new Expression.Or(new Expression.IsNull(x, false),
                        new Expression.Comparison(Expression.ComparisonOp.LT, nn, n)),
                        new Expression.Literal(DataType.BOOLEAN, null))),
                no());
        return add(new Bound(counts, false, List.of(), List.of(), null, result, 4), JoinType.SINGLE, List.of(),
                List.of(), null);
    }

    @Override
    public Expression scalar(SqlExpr.ScalarSubquery e) {
        Expression value = values.get(e);
        if (value == null) {
            Bound b = oneColumn(bind(e, e.query(), Correlation.Kind.SCALAR), e.position());
            value = add(b, JoinType.SINGLE, List.of(), List.of(), null);
            values.put(e, value);
        }
        return value;
    }

    /**
     * Takes a condition of {@code WHERE} that is {@code EXISTS},
     * {@code NOT EXISTS} or {@code IN} with a subquery as a {@code SEMI} or
     * {@code ANTI} join, which keeps the rows for which it is true; like
     * {@code WHERE}, it drops a row for which {@code IN} is {@code NULL}.
     * {@code NOT IN} is {@code NULL} for a row whose value is {@code NULL}
     * or that the subquery does not have when the subquery has a
     * {@code NULL}; so a correlated one is an {@code ANTI} join with the
     * rows that are equal or {@code NULL}, and an uncorrelated one is left
     * to the {@code MARK} join, as it would have no keys.
     *
     * @return whether the condition is taken
     */
    boolean filter(SqlExpr condition, ExpressionBinder binder) {
        switch (condition) {
            case SqlExpr.Exists e -> {
                Bound b = bind(e, e.query(), Correlation.Kind.EXISTS);
                add(b.correlated() ? b : first(b), e.negated() ? JoinType.ANTI : JoinType.SEMI, List.of(), List.of(),
                        null);
                return true;
            }
            case SqlExpr.Quantified e when asIn(e) != null -> {
                return filter(asIn(e), binder);
            }
            case SqlExpr.Quantified e -> {
                // A correlated one is a join by the keys of its correlation, true for a row where the comparison is.
                ExpressionBinder.Bound value = binder.bound(e.value());
                checkValue(value.expression(), e.position());
                Bound b = keyed(e, e.query(), List.of(value), binder);
                if (!b.correlated()) {
                    return false;
                }
                Expression x = b.leftKeys().get(0);
                Expression y = right(b, 0);
                if (!e.all()) {
                    add(unkeyed(b), JoinType.SEMI, List.of(), List.of(), compare(e.op(), x, y));
                } else {
                    add(unkeyed(b), JoinType.ANTI, List.of(), List.of(), new Expression.Or(new Expression.Or(
                            compare(opposite(e.op()), x, y), new Expression.IsNull(y, false)),
                            new Expression.IsNull(x, false)));
                }
                return true;
            }
            case SqlExpr.InQuery e -> {
                List<ExpressionBinder.Bound> value = binder.values(e.value());
                for (ExpressionBinder.Bound v : value) {
                    checkValue(v.expression(), e.position());
                }
                Bound b = keyed(e, e.query(), value, binder);
                if (!e.negated()) {
                    add(b, JoinType.SEMI, List.of(), List.of(), null);
                    return true;
                }
                if (!b.correlated()) {
                    return false;
                }
                Expression x = b.leftKeys().get(0);
                Expression y = right(b, 0);
                add(unkeyed(b), JoinType.ANTI, List.of(), List.of(), new Expression.Or(new Expression.Or(
                        new Expression.Comparison(Expression.ComparisonOp.EQ, x, y),
                        new Expression.IsNull(y, false)), new Expression.IsNull(x, false)));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Tells whether there are subqueries to join. */
    boolean isEmpty() {
        return joins.isEmpty();
    }

    /**
     * Joins the subqueries that are not yet joined to the rows, in the
     * order they were bound.
     */
    PlanNode attach(PlanNode rows) {
        PlanNode plan = rows;
        for (int i = 0; i < joins.size(); i++) {
            Join j = joins.get(i);
            if (j.offset() >= 0) {
                continue;
            }
            int width = plan.schema().size();
            List<Integer> leftKeys = new ArrayList<>();
            List<Expression> extras = new ArrayList<>();
            for (Expression k : j.leftKeys()) {
                Expression key = resolve(k);
                if (key instanceof Expression.Column c) {
                    leftKeys.add(c.index());
                } else {
                    int e = extras.indexOf(key);
                    if (e < 0) {
                        extras.add(key);
                        e = extras.size() - 1;
                    }
                    leftKeys.add(width + e);
                }
            }
            if (!extras.isEmpty()) {
                plan = Plans.withKeyColumns(plan, extras);
            }
            int offset = plan.schema().size();
            Expression condition = j.condition() == null ? null : at(resolve(j.condition()), offset);
            plan = new PlanNode.Join(plan, j.plan(), leftKeys, j.rightKeys(), j.type(), condition);
            joins.set(i, j.attached(offset));
        }
        return plan;
    }

    /** Points the placeholders of an expression to the columns of the joined subqueries. */
    Expression resolve(Expression e) {
        return Expressions.transform(e, x -> {
            if (x instanceof Expression.Column c && c.index() >= PLACEHOLDER
                    && c.index() < PLACEHOLDER + joins.size()) {
                Join j = joins.get(c.index() - PLACEHOLDER);
                if (j.offset() < 0) {
                    throw new IllegalStateException("A subquery is not joined yet");
                }
                // A value may read the rows, and so subqueries joined before.
                return resolve(at(j.value(), j.offset()));
            }
            return x;
        });
    }

    /**
     * Adds a join of a bound subquery, with more keys and a condition
     * besides its own, and returns the placeholder of its value: the mark,
     * or the value of a scalar subquery.
     */
    private Expression add(Bound b, JoinType type, List<Expression> leftKeys, List<Integer> rightKeys,
            Expression condition) {
        List<Expression> left = new ArrayList<>(leftKeys);
        left.addAll(b.leftKeys());
        List<Integer> right = new ArrayList<>(rightKeys);
        right.addAll(b.rightKeys());
        Expression all = b.condition() == null ? condition
                : condition == null ? b.condition() : new Expression.And(b.condition(), condition);
        Expression value = type == JoinType.SINGLE ? b.value()
                : new Expression.Column(RIGHT, DataType.BOOLEAN);
        joins.add(new Join(b.plan(), type, left, right, all, value, -1));
        return new Expression.Column(PLACEHOLDER + joins.size() - 1, value.type());
    }

    /** Binds a subquery once, and finds how to join it if it is correlated. */
    private Bound bind(SqlExpr e, Query q, Correlation.Kind kind) {
        Bound b = bound.get(e);
        if (b != null) {
            return b;
        }
        Correlation correlation = new Correlation(outer, kind);
        PlanNode plan = queries.apply(q, correlation);
        Schema schema = plan.schema();
        Correlation.Join j = correlation.join();
        if (j == null && correlation.isCorrelated()) {
            throw new IllegalStateException("A correlated subquery without a join");
        }
        if (j == null) {
            b = new Bound(plan, false, List.of(), List.of(), null, new Expression.Column(RIGHT, schema.type(0)),
                    schema.size());
        } else {
            List<Expression> leftKeys = j.outerKeys().stream().map(correlation::outer).toList();
            Expression condition = j.condition() == null ? null : right(correlation, j.condition());
            Expression value = j.value() == null ? new Expression.Column(RIGHT, schema.type(0))
                    : right(correlation, j.value());
            b = new Bound(plan, true, leftKeys, j.innerKeys(), condition, value, j.columns());
        }
        bound.put(e, b);
        return b;
    }

    /**
     * An expression over the columns of a subquery and the placeholders of
     * its correlation as one over the rows and the subquery, whose columns
     * start at {@link #RIGHT}.
     */
    private static Expression right(Correlation correlation, Expression e) {
        Expression shifted = Expressions.transform(e, x -> x instanceof Expression.Column c
                && c.index() < Correlation.PLACEHOLDER ? new Expression.Column(RIGHT + c.index(), c.type()) : x);
        return correlation.outer(shifted);
    }

    /** A column of a bound subquery in a condition of its join. */
    private static Expression right(Bound b, int column) {
        return new Expression.Column(RIGHT + column, b.plan().schema().type(column));
    }

    /** Places the columns of a subquery in a condition or a value at the column where its join puts them. */
    private static Expression at(Expression e, int offset) {
        return Expressions.transform(e, x -> x instanceof Expression.Column c && c.index() >= RIGHT
                && c.index() < Correlation.PLACEHOLDER ? new Expression.Column(c.index() - RIGHT + offset, c.type())
                : x);
    }

    /**
     * The subquery of {@code IN}, {@code ANY} or {@code ALL}, with each of
     * its columns cast to the type in which it is compared with its value,
     * as for {@code =}, and the values, cast to those types, as its first
     * keys. Unlike {@code =}, and as in DuckDB, the two types must have a
     * {@linkplain TypeRules#common common type}, unless the value is a
     * string literal or a {@code NULL}.
     */
    private Bound keyed(SqlExpr e, Query query, List<ExpressionBinder.Bound> values, ExpressionBinder binder) {
        Bound b = bind(e, query, Correlation.Kind.IN);
        int k = values.size();
        if (k == 1) {
            oneColumn(b, e.position());
        } else if (b.columns() != k) {
            throw new SqlException("Subquery returns " + b.columns() + " columns - expected " + k, e.position());
        } else if (b.correlated()) {
            throw new SqlException("Correlated IN/ANY/ALL with multiple columns not yet supported", e.position());
        }
        PlanNode plan = b.plan();
        Schema schema = plan.schema();
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
        }
        boolean cast = false;
        List<Expression> leftKeys = new ArrayList<>();
        List<Integer> rightKeys = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            ExpressionBinder.Bound value = values.get(i);
            int c = i;
            boolean nullColumn = plan instanceof PlanNode.Project p && p.columns().get(c).expression()
                    instanceof Expression.Literal l && l.value() == null;
            if (value.kind() != ExpressionBinder.Kind.STRING && !value.isNull() && !nullColumn) {
                try {
                    TypeRules.common(value.type(), schema.type(i));
                } catch (IllegalArgumentException x) {
                    throw new SqlException("Cannot compare values of type " + (value.kind()
                            == ExpressionBinder.Kind.INTEGER ? "INTEGER_LITERAL" : value.type().sqlName()) + " and "
                            + schema.type(i).sqlName() + " in IN/ANY/ALL clause - an explicit cast is required",
                            e.position());
                }
            }
            Expression column = new Expression.Column(i, schema.type(i));
            // A NULL selected by the subquery takes the type of the value, as a bare NULL does.
            Expression[] operands = binder.equality(value, new ExpressionBinder.Bound(column, nullColumn
                    ? ExpressionBinder.Kind.NULL : ExpressionBinder.Kind.TYPED), e.position());
            DataType t = TypeRules.common(operands[0].type(), operands[1].type());
            Expression right = as(operands[1], t);
            if (!right.equals(column)) {
                columns.set(i, new NamedExpression(schema.name(i), right));
                cast = true;
            }
            leftKeys.add(as(operands[0], t));
            rightKeys.add(i);
        }
        if (cast) {
            plan = new PlanNode.Project(plan, columns);
        }
        leftKeys.addAll(b.leftKeys());
        rightKeys.addAll(b.rightKeys());
        return new Bound(plan, b.correlated(), leftKeys, rightKeys, b.condition(), b.value(), b.columns());
    }

    /** The subquery of {@code IN}, {@code ANY} or {@code ALL} of one value without its first key, the value. */
    private static Bound unkeyed(Bound b) {
        return new Bound(b.plan(), b.correlated(), b.leftKeys().subList(1, b.leftKeys().size()),
                b.rightKeys().subList(1, b.rightKeys().size()), b.condition(), b.value(), b.columns());
    }

    /**
     * Rejects a value of {@code IN}, {@code ANY} or {@code ALL} that
     * references the query around the query of the clause, whose join would
     * need both.
     */
    private static void checkValue(Expression value, Position position) {
        if (Correlation.reads(value)) {
            throw new SqlException("Not supported: IN with a subquery whose value references the outer query",
                    position);
        }
    }

    private static Expression as(Expression e, DataType type) {
        return e.type().equals(type) ? e : new Expression.Cast(e, type);
    }

    private static Expression yes() {
        return new Expression.Literal(DataType.BOOLEAN, true);
    }

    private static Expression no() {
        return new Expression.Literal(DataType.BOOLEAN, false);
    }

    /** Checks that a subquery that stands for a value has one column. */
    private static Bound oneColumn(Bound b, Position position) {
        int n = b.columns();
        if (n != 1) {
            throw new SqlException("Subquery returns " + n + " columns - expected 1", position);
        }
        return b;
    }

    /** The first row of an uncorrelated subquery, all that {@code EXISTS} needs. */
    private static Bound first(Bound b) {
        return new Bound(new PlanNode.Limit(b.plan(), 1, 0), false, b.leftKeys(), b.rightKeys(), b.condition(),
                b.value(), b.columns());
    }
}
