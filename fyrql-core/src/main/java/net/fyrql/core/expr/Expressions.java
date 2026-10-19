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

package net.fyrql.core.expr;

import net.fyrql.core.expr.Expression.ArithmeticOp;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.function.StructExtract;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Short forms for building expressions, and helpers over expression trees. */
public final class Expressions {

    private static final FunctionRegistry STANDARD = FunctionRegistry.standard();

    private Expressions() {
    }

    /** Returns the registry of the built-in functions. */
    public static FunctionRegistry functions() {
        return STANDARD;
    }

    public static Expression col(int index, DataType type) {
        return new Expression.Column(index, type);
    }

    /** Returns a reference to a column of a schema. */
    public static Expression col(Schema schema, int index) {
        return new Expression.Column(index, schema.type(index));
    }

    public static Expression lit(DataType type, Object value) {
        return new Expression.Literal(type, value);
    }

    public static Expression nullOf(DataType type) {
        return new Expression.Literal(type, null);
    }

    public static Expression lit(boolean value) {
        return lit(DataType.BOOLEAN, value);
    }

    public static Expression lit(byte value) {
        return lit(DataType.TINYINT, value);
    }

    public static Expression lit(short value) {
        return lit(DataType.SMALLINT, value);
    }

    public static Expression lit(int value) {
        return lit(DataType.INTEGER, value);
    }

    public static Expression lit(long value) {
        return lit(DataType.BIGINT, value);
    }

    public static Expression lit(float value) {
        return lit(DataType.FLOAT, value);
    }

    public static Expression lit(double value) {
        return lit(DataType.DOUBLE, value);
    }

    public static Expression lit(String value) {
        return lit(DataType.VARCHAR, value);
    }

    /** A decimal literal with the precision of its digits and its own scale, as DuckDB types a literal. */
    public static Expression lit(BigDecimal value) {
        BigDecimal v = value.scale() < 0 ? value.setScale(0) : value;
        return lit(DataType.decimal(Math.max(v.precision(), v.scale() + 1), v.scale()), v);
    }

    public static Expression date(int days) {
        return lit(DataType.DATE, days);
    }

    public static Expression add(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.ADD, l, r);
    }

    public static Expression sub(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.SUBTRACT, l, r);
    }

    public static Expression mul(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.MULTIPLY, l, r);
    }

    public static Expression div(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.DIVIDE, l, r);
    }

    public static Expression intDiv(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.INT_DIVIDE, l, r);
    }

    public static Expression rem(Expression l, Expression r) {
        return new Expression.Arithmetic(ArithmeticOp.REMAINDER, l, r);
    }

    public static Expression neg(Expression e) {
        return new Expression.Negate(e);
    }

    public static Expression cmp(ComparisonOp op, Expression l, Expression r) {
        return new Expression.Comparison(op, l, r);
    }

    public static Expression eq(Expression l, Expression r) {
        return cmp(ComparisonOp.EQ, l, r);
    }

    public static Expression ne(Expression l, Expression r) {
        return cmp(ComparisonOp.NE, l, r);
    }

    public static Expression lt(Expression l, Expression r) {
        return cmp(ComparisonOp.LT, l, r);
    }

    public static Expression le(Expression l, Expression r) {
        return cmp(ComparisonOp.LE, l, r);
    }

    public static Expression gt(Expression l, Expression r) {
        return cmp(ComparisonOp.GT, l, r);
    }

    public static Expression ge(Expression l, Expression r) {
        return cmp(ComparisonOp.GE, l, r);
    }

    public static Expression and(Expression l, Expression r) {
        return new Expression.And(l, r);
    }

    public static Expression or(Expression l, Expression r) {
        return new Expression.Or(l, r);
    }

    public static Expression not(Expression e) {
        return new Expression.Not(e);
    }

    public static Expression isNull(Expression e) {
        return new Expression.IsNull(e, false);
    }

    public static Expression isNotNull(Expression e) {
        return new Expression.IsNull(e, true);
    }

    public static Expression between(Expression v, Expression lo, Expression hi) {
        return new Expression.Between(v, lo, hi);
    }

    public static Expression in(Expression v, Expression... list) {
        return new Expression.In(v, Arrays.asList(list));
    }

    public static Expression.When when(Expression condition, Expression result) {
        return new Expression.When(condition, result);
    }

    public static Expression caseWhen(List<Expression.When> whens, Expression otherwise) {
        return new Expression.Case(whens, otherwise);
    }

    public static Expression iff(Expression condition, Expression whenTrue, Expression whenFalse) {
        return new Expression.If(condition, whenTrue, whenFalse);
    }

    public static Expression coalesce(Expression... operands) {
        return new Expression.Coalesce(Arrays.asList(operands));
    }

    public static Expression cast(Expression e, DataType target) {
        return new Expression.Cast(e, target);
    }

    /** {@code TRY_CAST}: {@code NULL} where the value does not convert. */
    public static Expression tryCast(Expression e, DataType target) {
        return new Expression.Cast(e, target, true);
    }

    public static Expression like(Expression s, Expression pattern) {
        return new Expression.Like(s, pattern);
    }

    public static Expression ilike(Expression s, Expression pattern) {
        return new Expression.Like(s, pattern, true);
    }

    public static Expression isDistinct(Expression l, Expression r) {
        return new Expression.IsDistinct(l, r, false);
    }

    public static Expression isNotDistinct(Expression l, Expression r) {
        return new Expression.IsDistinct(l, r, true);
    }

    private static final Batch ONE_ROW = new Batch(new Schema(List.of()), 1, List.of());

    /** Calls a built-in function by name, {@linkplain ScalarFunction#specialize specialized} for its arguments. */
    public static Expression call(String function, Expression... arguments) {
        ScalarFunction f = STANDARD.resolve(function, Arrays.stream(arguments).map(Expression::type).toList());
        return new Expression.Call(f.specialize(Arrays.asList(arguments)), Arrays.asList(arguments));
    }

    /**
     * Returns the value of an expression without columns, or throws if it
     * has a column; {@code random} counts as a column.
     *
     * @throws IllegalArgumentException if the expression is not constant
     */
    public static Object constantValue(Expression e) {
        if (!isConstant(e)) {
            throw new IllegalArgumentException("Not a constant: " + e);
        }
        return Evaluator.evalRow(e, ONE_ROW, 0);
    }

    /** Tells whether a predicate holds for every node of an expression. */
    public static boolean allMatch(Expression e, Predicate<Expression> predicate) {
        if (!predicate.test(e)) {
            return false;
        }
        for (Expression child : e.children()) {
            if (!allMatch(child, predicate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Joins conditions with {@code AND}, from the left.
     *
     * @throws IllegalArgumentException if there are no conditions
     */
    public static Expression conjunction(List<Expression> conditions) {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("No conditions");
        }
        Expression result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = new Expression.And(result, conditions.get(i));
        }
        return result;
    }

    /** Returns the operands of nested {@code AND}s, from the left; the inverse of {@link #conjunction}. */
    public static List<Expression> conjuncts(Expression condition) {
        List<Expression> result = new ArrayList<>();
        collectConjuncts(condition, result);
        return result;
    }

    private static void collectConjuncts(Expression e, List<Expression> into) {
        if (e instanceof Expression.And a) {
            collectConjuncts(a.left(), into);
            collectConjuncts(a.right(), into);
        } else {
            into.add(e);
        }
    }

    /** Marks the columns an expression reads. */
    public static void columns(Expression e, BitSet into) {
        if (e instanceof Expression.Column c) {
            into.set(c.index());
        }
        for (Expression child : e.children()) {
            columns(child, into);
        }
    }

    /** Returns the columns an expression reads. */
    public static BitSet columns(Expression e) {
        BitSet result = new BitSet();
        columns(e, result);
        return result;
    }

    /** Which inputs of a join an expression reads, see {@link #side}. */
    public enum Side {
        NONE, LEFT, RIGHT, BOTH
    }

    /**
     * Tells which inputs of a join an expression over the join's output
     * reads, where the left input has {@code leftWidth} columns.
     */
    public static Side side(Expression e, int leftWidth) {
        boolean left = false;
        boolean right = false;
        List<Expression> pending = new ArrayList<>(List.of(e));
        while (!pending.isEmpty()) {
            Expression x = pending.remove(pending.size() - 1);
            if (x instanceof Expression.Column c) {
                if (c.index() < leftWidth) {
                    left = true;
                } else {
                    right = true;
                }
            }
            pending.addAll(x.children());
        }
        return left ? (right ? Side.BOTH : Side.LEFT) : (right ? Side.RIGHT : Side.NONE);
    }

    /** Renumbers the columns from an index on by an offset, which may be negative. */
    public static Expression shift(Expression e, int from, int by) {
        if (by == 0) {
            return e;
        }
        return transform(e, x -> x instanceof Expression.Column c && c.index() >= from
                ? new Expression.Column(c.index() + by, c.type()) : x);
    }

    /**
     * Tells whether an expression has no columns, no call of {@code random}
     * and no call of a function that is not
     * {@linkplain net.fyrql.core.function.ScalarFunction#foldable() foldable}.
     */
    public static boolean isConstant(Expression e) {
        return allMatch(e, x -> !(x instanceof Expression.Column) && !isRandom(x)
                && !(x instanceof Expression.Call c && !c.function().foldable()));
    }

    /** Tells whether an expression gives the same value for the same row: whether it has no call of {@code random}. */
    public static boolean isDeterministic(Expression e) {
        return allMatch(e, x -> !isRandom(x));
    }

    private static boolean isRandom(Expression e) {
        return e instanceof Expression.Call c && c.function().name().equals("random");
    }

    /**
     * Checks that every column reference of an expression exists in a schema
     * and has its type.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static void check(Schema schema, Expression expression) {
        if (expression instanceof Expression.Column c) {
            if (c.index() >= schema.size()) {
                throw new IllegalArgumentException("No column " + c.index() + " in " + schema);
            }
            if (!c.type().equals(schema.type(c.index()))) {
                throw new IllegalArgumentException("Column " + c.index() + " is a " + schema.type(c.index())
                        + ", not a " + c.type());
            }
        }
        for (Expression child : expression.children()) {
            check(schema, child);
        }
    }

    /** Returns the number of nodes of an expression. */
    public static int size(Expression e) {
        int n = 1;
        for (Expression child : e.children()) {
            n += size(child);
        }
        return n;
    }

    /** Rebuilds an expression with new operands, in the order of {@link Expression#children()}. */
    public static Expression withChildren(Expression e, List<Expression> c) {
        return switch (e) {
            case Expression.Literal l -> l;
            case Expression.Column col -> col;
            case Expression.Arithmetic a -> new Expression.Arithmetic(a.op(), c.get(0), c.get(1));
            case Expression.Negate n -> new Expression.Negate(c.get(0));
            case Expression.Comparison cm -> new Expression.Comparison(cm.op(), c.get(0), c.get(1));
            case Expression.And a -> new Expression.And(c.get(0), c.get(1));
            case Expression.Or o -> new Expression.Or(c.get(0), c.get(1));
            case Expression.Not n -> new Expression.Not(c.get(0));
            case Expression.IsNull n -> new Expression.IsNull(c.get(0), n.negated());
            case Expression.Between b -> new Expression.Between(c.get(0), c.get(1), c.get(2));
            case Expression.In in -> new Expression.In(c.get(0), c.subList(1, c.size()));
            case Expression.Case cs -> {
                List<Expression.When> whens = new java.util.ArrayList<>();
                for (int i = 0; i < cs.whens().size(); i++) {
                    whens.add(new Expression.When(c.get(2 * i), c.get(2 * i + 1)));
                }
                yield new Expression.Case(whens, c.get(c.size() - 1));
            }
            case Expression.If i -> new Expression.If(c.get(0), c.get(1), c.get(2));
            case Expression.Coalesce co -> new Expression.Coalesce(c);
            case Expression.Cast ca -> new Expression.Cast(c.get(0), ca.target(), ca.safe());
            case Expression.Like l -> new Expression.Like(c.get(0), c.get(1), l.caseInsensitive());
            case Expression.IsDistinct d -> new Expression.IsDistinct(c.get(0), c.get(1), d.negated());
            case Expression.Call call -> new Expression.Call(call.function(), c);
        };
    }

    /**
     * A chain of {@code struct_extract} calls over a value that is not one:
     * the value and the names of the fields, outermost first; an expression
     * that is no such call is its own base with an empty path.
     */
    public record FieldAccess(Expression base, List<String> path) {
        public FieldAccess {
            Objects.requireNonNull(base, "base");
            path = List.copyOf(path);
        }
    }

    /** Takes an expression apart into the value and the fields taken out of it. */
    public static FieldAccess fieldAccess(Expression e) {
        ArrayDeque<String> path = new ArrayDeque<>();
        Expression base = e;
        while (base instanceof Expression.Call c && c.function() instanceof StructExtract x) {
            path.addFirst(x.field());
            base = c.arguments().get(0);
        }
        return new FieldAccess(base, new ArrayList<>(path));
    }

    /**
     * Returns the field a path names inside a struct, as a chain of
     * {@code struct_extract} calls; the struct itself for an empty path.
     *
     * @throws IllegalArgumentException if a step is not a field of a struct
     */
    public static Expression structField(Expression struct, List<String> path) {
        Expression e = struct;
        for (String name : path) {
            if (!(e.type() instanceof DataType.Struct s) || s.indexOf(name) < 0) {
                throw new IllegalArgumentException("No field " + name + " in a " + e.type().sqlName());
            }
            e = new Expression.Call(new StructExtract(s.indexOf(name), name),
                    List.of(e, new Expression.Literal(DataType.VARCHAR, name)));
        }
        return e;
    }

    /** Applies an operator bottom-up to every node of an expression. */
    public static Expression transform(Expression e, UnaryOperator<Expression> operator) {
        List<Expression> children = e.children().stream().map(ch -> transform(ch, operator)).toList();
        return operator.apply(withChildren(e, children));
    }
}
