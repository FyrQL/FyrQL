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
import net.fyrql.core.function.Strings;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Evaluates expressions over batches, one vector at a time, with the
 * semantics of DuckDB.
 * <ul>
 * <li>{@code NULL} propagates through arithmetic, comparisons, casts and
 * most functions; {@code AND}, {@code OR}, {@code NOT} use three-valued
 * logic; {@code IS NULL}, {@code IN}, {@code CASE}, {@code IF} and
 * {@code COALESCE} handle {@code NULL} themselves.</li>
 * <li>Arithmetic on integral types stays in the common type and throws
 * {@link ArithmeticException} on overflow. {@code /} divides as doubles,
 * so {@code 1 / 0} is infinity; integral {@code //} and {@code %} by zero
 * give {@code NULL}; on floating-point operands {@code //} is the plain
 * division.</li>
 * <li>Comparisons follow the total order of {@link Vectors#compare}: NaN
 * equals NaN and lies above every number, {@code -0.0} equals {@code 0.0},
 * strings compare by code point.</li>
 * <li>The branches of {@code CASE}, {@code IF} and {@code COALESCE}, and the
 * right operands of {@code AND} and {@code OR}, are evaluated only for the
 * rows that need them, so that an error in a branch that is not taken does
 * not surface.</li>
 * </ul>
 */
public final class Evaluator {

    private Evaluator() {
    }

    /** Evaluates an expression for every row of a batch. */
    public static Vector eval(Expression e, Batch in) {
        return switch (e) {
            case Expression.Column c -> in.column(c.index());
            case Expression.Literal l -> constant(l.type(), l.value(), in.rowCount());
            case Expression.Arithmetic a -> arithmetic(a, in);
            case Expression.Negate n -> negate(n, in);
            case Expression.Comparison c -> comparison(c, in);
            case Expression.And a -> and(a, in);
            case Expression.Or o -> or(o, in);
            case Expression.Not n -> not(eval(n.operand(), in));
            case Expression.IsNull n -> isNull(eval(n.operand(), in), n.negated());
            case Expression.Between b -> between(b, in);
            case Expression.In i -> in(i, in);
            case Expression.Case c -> caseWhen(c, in);
            case Expression.If i -> iff(i, in);
            case Expression.Coalesce c -> coalesce(c, in);
            case Expression.Cast c when c.operand() instanceof Expression.Literal l && in.rowCount() > 0 ->
                    // Once rather than for every row, which gives the same values and fails alike.
                    constant(c.target(), c.safe() ? Casts.tryCast(l.value(), l.type(), c.target())
                            : Casts.cast(l.value(), l.type(), c.target()), in.rowCount());
            case Expression.Cast c -> c.safe() ? tryCast(eval(c.operand(), in), c.operand().type(), c.target())
                    : cast(eval(c.operand(), in), c.operand().type(), c.target());
            case Expression.Like l -> like(l, in);
            case Expression.IsDistinct d -> isDistinct(d, in);
            case Expression.Call c -> call(c, in);
        };
    }

    /**
     * Returns the rows of a batch, in order, for which a condition is
     * {@code TRUE}, evaluating it as DuckDB evaluates a filter: each operand
     * of {@code AND} only for the rows for which the operands before it are
     * {@code TRUE}, each operand of {@code OR} only for the rows for which
     * none before it is, and {@code BETWEEN} as its lower comparison, then
     * its upper one for the rows that pass. An error in a part that no row
     * reaches does not surface.
     */
    public static int[] select(Expression condition, Batch in) {
        int[] all = new int[in.rowCount()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return select(condition, in, all);
    }

    private static int[] select(Expression condition, Batch in, int[] rows) {
        if (rows.length == 0) {
            return rows;
        }
        switch (condition) {
            case Expression.And a -> {
                return select(a.right(), in, select(a.left(), in, rows));
            }
            case Expression.Or o -> {
                int[] left = select(o.left(), in, rows);
                int[] right = select(o.right(), in, without(rows, left));
                return union(left, right);
            }
            case Expression.Between b -> {
                // Both comparisons in the common type of the three operands, as the BETWEEN itself compares.
                DataType t = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
                Expression value = as(b.value(), t);
                int[] low = select(new Expression.Comparison(Expression.ComparisonOp.GE, value, as(b.low(), t)), in,
                        rows);
                return select(new Expression.Comparison(Expression.ComparisonOp.LE, value, as(b.high(), t)), in, low);
            }
            default -> {
                Batch sub = rows.length == in.rowCount() ? in : in.select(rows);
                BooleanVector v = (BooleanVector) eval(condition, sub);
                int[] result = new int[rows.length];
                int n = 0;
                for (int i = 0; i < rows.length; i++) {
                    if (!v.isNull(i) && v.getBoolean(i)) {
                        result[n++] = rows[i];
                    }
                }
                return n == rows.length ? rows : Arrays.copyOf(result, n);
            }
        }
    }

    private static Expression as(Expression e, DataType type) {
        return e.type().equals(type) ? e : new Expression.Cast(e, type);
    }

    /** The rows of a sorted array that are not in a sorted subset of it. */
    private static int[] without(int[] rows, int[] subset) {
        int[] result = new int[rows.length - subset.length];
        int n = 0;
        int k = 0;
        for (int row : rows) {
            if (k < subset.length && subset[k] == row) {
                k++;
            } else {
                result[n++] = row;
            }
        }
        return result;
    }

    /** The union of two disjoint sorted arrays, sorted. */
    private static int[] union(int[] a, int[] b) {
        int[] result = new int[a.length + b.length];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < a.length || j < b.length) {
            result[n++] = j >= b.length || i < a.length && a[i] < b[j] ? a[i++] : b[j++];
        }
        return result;
    }

    /** Evaluates an expression for one row of a batch, as a Java value. */
    public static Object evalRow(Expression e, Batch in, int row) {
        return eval(e, in.select(new int[] {row})).get(0);
    }

    /**
     * Evaluates an expression and casts it to a type; a literal is cast
     * once rather than for every row, which gives the same values and fails
     * alike, since a cast of a constant fails at the first row if at all.
     */
    private static Vector evalAs(Expression e, Batch in, DataType type) {
        if (e instanceof Expression.Literal l && !l.type().equals(type) && in.rowCount() > 0) {
            return constant(type, Casts.cast(l.value(), l.type(), type), in.rowCount());
        }
        return cast(eval(e, in), e.type(), type);
    }

    /** Returns a vector that repeats a value. */
    public static Vector constant(DataType type, Object value, int size) {
        Vector.Builder b = Vectors.builder(type, size);
        for (int i = 0; i < size; i++) {
            b.append(value);
        }
        return b.build();
    }

    /** Casts every value of a vector to a type; the vector itself if the type is the same. */
    public static Vector cast(Vector v, DataType from, DataType to) {
        if (from.equals(to)) {
            return v;
        }
        Vector.Builder b = Vectors.builder(to, v.size());
        for (int i = 0; i < v.size(); i++) {
            b.append(Casts.cast(v.get(i), from, to));
        }
        return b.build();
    }

    /** Casts every value of a vector to a type, {@code NULL} where the value does not convert. */
    public static Vector tryCast(Vector v, DataType from, DataType to) {
        if (from.equals(to)) {
            return v;
        }
        Vector.Builder b = Vectors.builder(to, v.size());
        for (int i = 0; i < v.size(); i++) {
            b.append(Casts.tryCast(v.get(i), from, to));
        }
        return b.build();
    }

    private static Vector arithmetic(Expression.Arithmetic a, Batch in) {
        if (a.left().type() == DataType.DATE || a.right().type() == DataType.DATE) {
            return dateArithmetic(a, in);
        }
        DataType resultType = a.type();
        ArithmeticOp op = a.op();
        DataType operandType;
        DataType leftType;
        DataType rightType;
        if (op == ArithmeticOp.DIVIDE || resultType.isFloatingPoint()
                || op == ArithmeticOp.REMAINDER && resultType instanceof Decimal) {
            operandType = resultType;
            leftType = resultType;
            rightType = resultType;
        } else if (op == ArithmeticOp.MULTIPLY && resultType instanceof Decimal) {
            // Each factor keeps its own digits, which the type of a sum would not always hold.
            operandType = resultType;
            leftType = TypeRules.asDecimal(a.left().type());
            rightType = TypeRules.asDecimal(a.right().type());
        } else {
            operandType = TypeRules.numeric(a.left().type(), a.right().type());
            leftType = operandType;
            rightType = operandType;
        }
        Vector l = evalAs(a.left(), in, leftType);
        Vector r = evalAs(a.right(), in, rightType);
        int n = in.rowCount();
        if (l instanceof IntVector x && r instanceof IntVector y && resultType == DataType.INTEGER) {
            IntVector.Builder b = new IntVector.Builder(DataType.INTEGER, n);
            for (int i = 0; i < n; i++) {
                if (x.isNull(i) || y.isNull(i) || ScalarOps.nullDivisor(op, y.getInt(i))) {
                    b.appendNull();
                } else {
                    b.appendInt((int) ScalarOps.integral(op, x.getInt(i), y.getInt(i), DataType.INTEGER));
                }
            }
            return b.build();
        }
        if (l instanceof BigIntVector x && r instanceof BigIntVector y && resultType == DataType.BIGINT) {
            BigIntVector.Builder b = new BigIntVector.Builder(DataType.BIGINT, n);
            for (int i = 0; i < n; i++) {
                if (x.isNull(i) || y.isNull(i) || ScalarOps.nullDivisor(op, y.getLong(i))) {
                    b.appendNull();
                } else {
                    b.appendLong(ScalarOps.integral(op, x.getLong(i), y.getLong(i), DataType.BIGINT));
                }
            }
            return b.build();
        }
        if (l instanceof DoubleVector x && r instanceof DoubleVector y) {
            DoubleVector.Builder b = new DoubleVector.Builder(n);
            for (int i = 0; i < n; i++) {
                if (x.isNull(i) || y.isNull(i) || ScalarOps.nullDivisor(op, y.getDouble(i))) {
                    b.appendNull();
                } else {
                    b.appendDouble(ScalarOps.floating(op, x.getDouble(i), y.getDouble(i)));
                }
            }
            return b.build();
        }
        if (l instanceof DecimalVector x && r instanceof DecimalVector y && x.isSmall() && y.isSmall()
                && resultType instanceof Decimal rt && rt.fitsLong() && scalesFit(op, x.type(), y.type(), rt)) {
            DecimalVector.Builder b = new DecimalVector.Builder(rt, n);
            for (int i = 0; i < n; i++) {
                if (x.isNull(i) || y.isNull(i) || ScalarOps.nullDivisor(op, y.getUnscaledLong(i))) {
                    b.appendNull();
                } else {
                    b.appendUnscaled(ScalarOps.decimal(op, x.getUnscaledLong(i), y.getUnscaledLong(i), rt));
                }
            }
            return b.build();
        }
        Vector.Builder b = Vectors.builder(resultType, n);
        for (int i = 0; i < n; i++) {
            if (l.isNull(i) || r.isNull(i)) {
                b.appendNull();
            } else {
                b.append(ScalarOps.arithmetic(op, l.get(i), r.get(i), operandType, resultType));
            }
        }
        return b.build();
    }

    /**
     * A number of days added to or subtracted from a date, which must stay
     * in the range of DuckDB, or the days between two dates.
     */
    private static Vector dateArithmetic(Expression.Arithmetic a, Batch in) {
        Vector l = eval(a.left(), in);
        Vector r = eval(a.right(), in);
        boolean bothDates = a.left().type() == DataType.DATE && a.right().type() == DataType.DATE;
        int n = in.rowCount();
        Vector.Builder b = Vectors.builder(a.type(), n);
        for (int i = 0; i < n; i++) {
            if (l.isNull(i) || r.isNull(i)) {
                b.appendNull();
                continue;
            }
            long x = ((Number) l.get(i)).longValue();
            long y = ((Number) r.get(i)).longValue();
            if (bothDates) {
                b.append(x - y);
            } else {
                b.append(ScalarOps.shiftDate(a.op(), x, y));
            }
        }
        return b.build();
    }

    /** Tells whether the unscaled operands of a decimal operation have the scales {@link ScalarOps#decimal} expects. */
    private static boolean scalesFit(ArithmeticOp op, Decimal x, Decimal y, Decimal result) {
        return op == ArithmeticOp.MULTIPLY ? x.scale() + y.scale() == result.scale()
                : x.scale() == result.scale() && y.scale() == result.scale();
    }

    private static Vector negate(Expression.Negate n, Batch in) {
        Vector v = eval(n.operand(), in);
        DataType t = n.type();
        Vector.Builder b = Vectors.builder(t, v.size());
        for (int i = 0; i < v.size(); i++) {
            if (v.isNull(i)) {
                b.appendNull();
                continue;
            }
            b.append(ScalarOps.negate(v.get(i), t));
        }
        return b.build();
    }

    private static Vector comparison(Expression.Comparison c, Batch in) {
        DataType t = TypeRules.common(c.left().type(), c.right().type());
        Vector l = evalAs(c.left(), in, t);
        Vector r = evalAs(c.right(), in, t);
        return compare(c.op(), l, r);
    }

    /** Compares two vectors row by row; {@code NULL} where either side is. */
    static BooleanVector compare(Expression.ComparisonOp op, Vector l, Vector r) {
        int n = l.size();
        BooleanVector.Builder b = new BooleanVector.Builder(n);
        for (int i = 0; i < n; i++) {
            if (l.isNull(i) || r.isNull(i)) {
                b.appendNull();
            } else {
                b.appendBoolean(op.test(Vectors.compare(l, i, r, i)));
            }
        }
        return b.build();
    }

    private static Vector and(Expression.And a, Batch in) {
        BooleanVector l = (BooleanVector) eval(a.left(), in);
        // The right side is evaluated only where the left one does not decide.
        int[] undecided = rowsWhere(l, false);
        BooleanVector r = undecided.length == 0 ? null : (BooleanVector) eval(a.right(), in.select(undecided));
        BooleanVector.Builder b = new BooleanVector.Builder(l.size());
        int k = 0;
        for (int i = 0; i < l.size(); i++) {
            if (!l.isNull(i) && !l.getBoolean(i)) {
                b.appendBoolean(false);
                continue;
            }
            int j = k++;
            if (!r.isNull(j) && !r.getBoolean(j)) {
                b.appendBoolean(false);
            } else if (l.isNull(i) || r.isNull(j)) {
                b.appendNull();
            } else {
                b.appendBoolean(true);
            }
        }
        return b.build();
    }

    private static Vector or(Expression.Or o, Batch in) {
        BooleanVector l = (BooleanVector) eval(o.left(), in);
        int[] undecided = rowsWhere(l, true);
        BooleanVector r = undecided.length == 0 ? null : (BooleanVector) eval(o.right(), in.select(undecided));
        BooleanVector.Builder b = new BooleanVector.Builder(l.size());
        int k = 0;
        for (int i = 0; i < l.size(); i++) {
            if (!l.isNull(i) && l.getBoolean(i)) {
                b.appendBoolean(true);
                continue;
            }
            int j = k++;
            if (!r.isNull(j) && r.getBoolean(j)) {
                b.appendBoolean(true);
            } else if (l.isNull(i) || r.isNull(j)) {
                b.appendNull();
            } else {
                b.appendBoolean(false);
            }
        }
        return b.build();
    }

    /** The rows where a boolean vector is not the deciding value ({@code false} for AND, {@code true} for OR). */
    private static int[] rowsWhere(BooleanVector v, boolean deciding) {
        int[] rows = new int[v.size()];
        int n = 0;
        for (int i = 0; i < v.size(); i++) {
            if (v.isNull(i) || v.getBoolean(i) != deciding) {
                rows[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(rows, n);
    }

    private static Vector not(Vector v) {
        BooleanVector x = (BooleanVector) v;
        BooleanVector.Builder b = new BooleanVector.Builder(x.size());
        for (int i = 0; i < x.size(); i++) {
            if (x.isNull(i)) {
                b.appendNull();
            } else {
                b.appendBoolean(!x.getBoolean(i));
            }
        }
        return b.build();
    }

    private static Vector isNull(Vector v, boolean negated) {
        boolean[] values = new boolean[v.size()];
        for (int i = 0; i < v.size(); i++) {
            values[i] = v.isNull(i) != negated;
        }
        return BooleanVector.of(values);
    }

    private static Vector between(Expression.Between bt, Batch in) {
        DataType t = TypeRules.common(List.of(bt.value().type(), bt.low().type(), bt.high().type()));
        Vector v = evalAs(bt.value(), in, t);
        Vector lo = evalAs(bt.low(), in, t);
        Vector hi = evalAs(bt.high(), in, t);
        BooleanVector ge = compare(Expression.ComparisonOp.GE, v, lo);
        BooleanVector le = compare(Expression.ComparisonOp.LE, v, hi);
        BooleanVector.Builder b = new BooleanVector.Builder(v.size());
        for (int i = 0; i < v.size(); i++) {
            boolean geFalse = !ge.isNull(i) && !ge.getBoolean(i);
            boolean leFalse = !le.isNull(i) && !le.getBoolean(i);
            if (geFalse || leFalse) {
                b.appendBoolean(false);
            } else if (ge.isNull(i) || le.isNull(i)) {
                b.appendNull();
            } else {
                b.appendBoolean(true);
            }
        }
        return b.build();
    }

    private static Vector in(Expression.In in, Batch batch) {
        Vector v = eval(in.value(), batch);
        int n = v.size();
        boolean[] matched = new boolean[n];
        boolean[] sawNull = new boolean[n];
        for (Expression element : in.list()) {
            DataType t = TypeRules.common(in.value().type(), element.type());
            Vector l = cast(v, in.value().type(), t);
            Vector r = evalAs(element, batch, t);
            for (int i = 0; i < n; i++) {
                if (matched[i] || v.isNull(i)) {
                    continue;
                }
                if (r.isNull(i)) {
                    sawNull[i] = true;
                } else if (Vectors.compare(l, i, r, i) == 0) {
                    matched[i] = true;
                }
            }
        }
        BooleanVector.Builder b = new BooleanVector.Builder(n);
        for (int i = 0; i < n; i++) {
            if (v.isNull(i) || !matched[i] && sawNull[i]) {
                b.appendNull();
            } else {
                b.appendBoolean(matched[i]);
            }
        }
        return b.build();
    }

    private static Vector caseWhen(Expression.Case c, Batch in) {
        int n = in.rowCount();
        DataType t = c.type();
        Object[] result = new Object[n];
        boolean[] decided = new boolean[n];
        for (Expression.When w : c.whens()) {
            int[] open = undecided(decided);
            if (open.length == 0) {
                break;
            }
            Batch sub = in.select(open);
            BooleanVector cond = (BooleanVector) eval(w.condition(), sub);
            int[] taken = new int[open.length];
            int m = 0;
            for (int i = 0; i < open.length; i++) {
                if (!cond.isNull(i) && cond.getBoolean(i)) {
                    taken[m++] = i;
                }
            }
            if (m > 0) {
                int[] takenRows = java.util.Arrays.copyOf(taken, m);
                Vector values = evalAs(w.result(), sub.select(takenRows), t);
                for (int i = 0; i < m; i++) {
                    result[open[takenRows[i]]] = values.get(i);
                    decided[open[takenRows[i]]] = true;
                }
            }
        }
        int[] open = undecided(decided);
        if (open.length > 0) {
            Vector values = evalAs(c.otherwise(), in.select(open), t);
            for (int i = 0; i < open.length; i++) {
                result[open[i]] = values.get(i);
            }
        }
        return Vectors.of(t, java.util.Arrays.asList(result));
    }

    private static int[] undecided(boolean[] decided) {
        int[] rows = new int[decided.length];
        int n = 0;
        for (int i = 0; i < decided.length; i++) {
            if (!decided[i]) {
                rows[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(rows, n);
    }

    private static Vector iff(Expression.If i, Batch in) {
        return caseWhen(new Expression.Case(List.of(new Expression.When(i.condition(), i.whenTrue())), i.whenFalse()),
                in);
    }

    private static Vector coalesce(Expression.Coalesce c, Batch in) {
        int n = in.rowCount();
        DataType t = c.type();
        Object[] result = new Object[n];
        boolean[] decided = new boolean[n];
        for (Expression operand : c.operands()) {
            int[] open = undecided(decided);
            if (open.length == 0) {
                break;
            }
            Vector values = evalAs(operand, in.select(open), t);
            for (int i = 0; i < open.length; i++) {
                if (!values.isNull(i)) {
                    result[open[i]] = values.get(i);
                    decided[open[i]] = true;
                }
            }
        }
        return Vectors.of(t, java.util.Arrays.asList(result));
    }

    private static Vector like(Expression.Like l, Batch in) {
        BytesVector s = (BytesVector) eval(l.string(), in);
        BytesVector p = (BytesVector) eval(l.pattern(), in);
        BooleanVector.Builder b = new BooleanVector.Builder(s.size());
        for (int i = 0; i < s.size(); i++) {
            if (s.isNull(i) || p.isNull(i)) {
                b.appendNull();
            } else if (l.caseInsensitive()) {
                b.appendBoolean(Strings.like(Strings.lower(s.getString(i)), Strings.lower(p.getString(i))));
            } else {
                b.appendBoolean(Strings.like(s.getString(i), p.getString(i)));
            }
        }
        return b.build();
    }

    private static Vector isDistinct(Expression.IsDistinct d, Batch in) {
        DataType t = TypeRules.common(d.left().type(), d.right().type());
        Vector l = evalAs(d.left(), in, t);
        Vector r = evalAs(d.right(), in, t);
        boolean[] values = new boolean[l.size()];
        for (int i = 0; i < values.length; i++) {
            boolean distinct;
            if (l.isNull(i) || r.isNull(i)) {
                distinct = l.isNull(i) != r.isNull(i);
            } else {
                distinct = Vectors.compare(l, i, r, i) != 0;
            }
            values[i] = distinct != d.negated();
        }
        return BooleanVector.of(values);
    }

    private static Vector call(Expression.Call c, Batch in) {
        List<DataType> types = c.function().coerce(c.argumentTypes());
        if (!c.function().readsArguments()) {
            return constant(c.type(), c.function().evaluate(types, new Object[types.size()]), in.rowCount());
        }
        List<Vector> args = new ArrayList<>();
        for (int i = 0; i < c.arguments().size(); i++) {
            Expression a = c.arguments().get(i);
            args.add(evalAs(a, in, types.get(i)));
        }
        int n = in.rowCount();
        Vector.Builder b = Vectors.builder(c.type(), n);
        Object[] values = new Object[args.size()];
        for (int i = 0; i < n; i++) {
            boolean anyNull = false;
            for (int k = 0; k < args.size(); k++) {
                values[k] = args.get(k).get(i);
                anyNull |= values[k] == null;
            }
            if (anyNull && c.function().nullOnNullInput()) {
                b.appendNull();
            } else {
                b.append(c.function().evaluate(types, values.clone()));
            }
        }
        return b.build();
    }
}
