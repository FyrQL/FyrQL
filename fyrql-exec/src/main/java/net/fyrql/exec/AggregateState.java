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

package net.fyrql.exec;

import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.FloatVector;
import net.fyrql.core.vector.HugeIntVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * The state of one aggregate over all groups of an aggregation, filled
 * batch by batch and read out group by group in the order of the groups.
 * <p>
 * {@link #of} gives a state that keeps the groups column-wise in primitive
 * arrays and reads each batch through the typed accessors of its vector,
 * so that nothing is boxed, for {@code count(*)}, and without
 * {@code DISTINCT} and {@code ORDER BY} for {@code count}, for {@code sum}
 * and {@code avg} over the integral types up to {@code BIGINT}, decimals,
 * {@code FLOAT} and {@code DOUBLE}, and for {@code min} and {@code max}
 * over these, except decimals of more than 18 digits, and over
 * {@code BOOLEAN}, {@code DATE} and {@code TIMESTAMP}. Its results are
 * those of the aggregation over objects in {@link HashAggregateOperator},
 * which covers the rest: exact sums that grow into a {@code BigInteger}
 * when a {@code long} overflows, the same conversion of a sum to a double
 * for {@code avg}, the sum of doubles in the order of the rows, and for
 * {@code min} and {@code max} the first of equal values, {@code -0.0} and
 * {@code 0.0} equal and NaN above everything.
 */
abstract class AggregateState {

    /** Accumulates the rows of a batch into their groups; there are {@code groups} groups so far. */
    abstract void add(Batch b, int[] groupOf, int rows, int groups);

    /**
     * Adds what a group of another state of the same aggregate holds to a
     * group of this one, as if the rows of the other group came after those
     * of this one; the other state stays as it is. Sums of {@code FLOAT}s
     * and {@code DOUBLE}s add up in another order than row by row, and of
     * equal values, {@code min} and {@code max} keep the one of this group.
     */
    abstract void combine(int group, AggregateState other, int otherGroup);

    /** Starts the results of a number of groups. */
    abstract void startResult(int groups);

    /** Computes the result of the next group; groups come in their order. */
    abstract void result(int group);

    /** Returns the results of all groups. */
    abstract Vector finishResult();

    /** Returns a state without boxing for an aggregate, or {@code null} if there is none for it. */
    static AggregateState of(AggregateCall call, Schema input) {
        AggregateFunction function = call.function();
        if (function == AggregateFunction.COUNT_STAR) {
            return new Count(-1);
        }
        if (call.distinct() || !call.orderBy().isEmpty()) {
            return null;
        }
        DataType type = input.type(call.column());
        boolean integral = type.isIntegral() && type != DataType.HUGEINT;
        return switch (function) {
            case COUNT -> new Count(call.column());
            case SUM, AVG -> {
                boolean average = function == AggregateFunction.AVG;
                if (integral || type instanceof Decimal) {
                    yield new ExactSum(call.column(), type, call.type(input), average);
                }
                yield type.isFloatingPoint() ? new DoubleSum(call.column(), average) : null;
            }
            case MIN, MAX -> {
                boolean max = function == AggregateFunction.MAX;
                if (integral || type == DataType.BOOLEAN || type == DataType.DATE || type == DataType.TIMESTAMP
                        || type instanceof Decimal d && d.fitsLong()) {
                    yield new LongExtreme(call.column(), type, max);
                }
                yield type.isFloatingPoint() ? new DoubleExtreme(call.column(), type, max) : null;
            }
            default -> null;
        };
    }

    /** Returns the array with room for at least {@code n} groups. */
    static long[] grow(long[] a, int n) {
        return n <= a.length ? a : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    static double[] grow(double[] a, int n) {
        return n <= a.length ? a : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    static boolean[] grow(boolean[] a, int n) {
        return n <= a.length ? a : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    static BigInteger[] grow(BigInteger[] a, int n) {
        return n <= a.length ? a : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    /**
     * Reads the values of a vector as longs, booleans as 0 and 1 and
     * decimals unscaled, and their nulls; the arrays have room for the rows.
     */
    static void readLongs(Vector v, int rows, long[] values, boolean[] nulls) {
        switch (v) {
            case BooleanVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getBoolean(r) ? 1 : 0;
                    nulls[r] = x.isNull(r);
                }
            }
            case TinyIntVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getByte(r);
                    nulls[r] = x.isNull(r);
                }
            }
            case SmallIntVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getShort(r);
                    nulls[r] = x.isNull(r);
                }
            }
            case IntVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getInt(r);
                    nulls[r] = x.isNull(r);
                }
            }
            case BigIntVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getLong(r);
                    nulls[r] = x.isNull(r);
                }
            }
            case DecimalVector x when x.isSmall() -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getUnscaledLong(r);
                    nulls[r] = x.isNull(r);
                }
            }
            default -> throw new IllegalStateException("No longs in a " + v.type() + " vector");
        }
    }

    /** Reads the values of a {@code FLOAT} or {@code DOUBLE} vector as doubles, and their nulls. */
    static void readDoubles(Vector v, int rows, double[] values, boolean[] nulls) {
        switch (v) {
            case FloatVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getFloat(r);
                    nulls[r] = x.isNull(r);
                }
            }
            case DoubleVector x -> {
                for (int r = 0; r < rows; r++) {
                    values[r] = x.getDouble(r);
                    nulls[r] = x.isNull(r);
                }
            }
            default -> throw new IllegalStateException("No doubles in a " + v.type() + " vector");
        }
    }

    /**
     * Converts a {@code long} to a double as {@link HashAggregateOperator#toDouble(BigInteger)}
     * converts the same number, which for a negative number below -2^53 is
     * not always {@code (double) x}.
     */
    static double toDouble(long x) {
        return x >= 0 ? (double) x : -(double) ~x - 1;
    }

    /** {@code count(*)} and {@code count}: a count per group. */
    private static final class Count extends AggregateState {
        /** The column, -1 for {@code count(*)}. */
        private final int column;
        private long[] counts = new long[16];
        private int groups;

        Count(int column) {
            this.column = column;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            counts = grow(counts, groups);
            if (column < 0) {
                for (int r = 0; r < rows; r++) {
                    counts[groupOf[r]]++;
                }
                return;
            }
            Vector v = b.column(column);
            for (int r = 0; r < rows; r++) {
                if (!v.isNull(r)) {
                    counts[groupOf[r]]++;
                }
            }
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            counts = grow(counts, group + 1);
            counts[group] += ((Count) other).counts[otherGroup];
        }

        @Override
        void startResult(int groups) {
            this.groups = groups;
            counts = grow(counts, groups);
        }

        @Override
        void result(int group) {
        }

        @Override
        Vector finishResult() {
            return BigIntVector.wrap(DataType.BIGINT, Arrays.copyOf(counts, groups), null);
        }
    }

    /**
     * {@code sum} and {@code avg} over integers up to {@code BIGINT} and over
     * decimals: an exact sum per group, of unscaled values for decimals, in
     * a {@code long} until it overflows, then in a {@code BigInteger}.
     */
    private static final class ExactSum extends AggregateState {
        private final int column;
        /** The scale of a decimal, -1 for integers. */
        private final int scale;
        private final DataType resultType;
        private final boolean average;
        private long[] sums = new long[16];
        private BigInteger[] bigs;
        private long[] counts = new long[16];
        private long[] values = new long[0];
        private boolean[] nulls = new boolean[0];
        private Vector.Builder out;
        private long[] high;
        private long[] low;
        private boolean[] outNulls;
        private double[] averages;

        ExactSum(int column, DataType type, DataType resultType, boolean average) {
            this.column = column;
            this.scale = type instanceof Decimal d ? d.scale() : -1;
            this.resultType = resultType;
            this.average = average;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            sums = grow(sums, groups);
            counts = grow(counts, groups);
            if (bigs != null) {
                bigs = grow(bigs, groups);
            }
            Vector v = b.column(column);
            if (v instanceof DecimalVector d && !d.isSmall()) {
                for (int r = 0; r < rows; r++) {
                    if (d.isNull(r)) {
                        continue;
                    }
                    int g = groupOf[r];
                    counts[g]++;
                    BigInteger x = d.getUnscaled(r);
                    if (x.bitLength() < 64) {
                        add(g, x.longValue());
                    } else {
                        addBig(g, x);
                    }
                }
                return;
            }
            if (values.length < rows) {
                values = new long[rows];
                nulls = new boolean[rows];
            }
            readLongs(v, rows, values, nulls);
            for (int r = 0; r < rows; r++) {
                if (nulls[r]) {
                    continue;
                }
                int g = groupOf[r];
                counts[g]++;
                add(g, values[r]);
            }
        }

        private void add(int g, long x) {
            if (bigs != null && bigs[g] != null) {
                bigs[g] = bigs[g].add(BigInteger.valueOf(x));
                return;
            }
            long sum = sums[g] + x;
            if (((sums[g] ^ sum) & (x ^ sum)) < 0) {
                addBig(g, BigInteger.valueOf(x));
            } else {
                sums[g] = sum;
            }
        }

        private void addBig(int g, BigInteger x) {
            if (bigs == null) {
                bigs = new BigInteger[sums.length];
            }
            bigs[g] = (bigs[g] != null ? bigs[g] : BigInteger.valueOf(sums[g])).add(x);
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            ExactSum o = (ExactSum) other;
            if (o.counts[otherGroup] == 0) {
                return;
            }
            sums = grow(sums, group + 1);
            counts = grow(counts, group + 1);
            if (bigs != null) {
                bigs = grow(bigs, group + 1);
            }
            counts[group] += o.counts[otherGroup];
            BigInteger big = o.bigs == null ? null : o.bigs[otherGroup];
            if (big != null) {
                addBig(group, big);
            } else {
                add(group, o.sums[otherGroup]);
            }
        }

        @Override
        void startResult(int groups) {
            sums = grow(sums, groups);
            counts = grow(counts, groups);
            if (bigs != null) {
                bigs = grow(bigs, groups);
            }
            outNulls = new boolean[groups];
            if (average) {
                averages = new double[groups];
            } else if (scale >= 0) {
                out = Vectors.builder(resultType, groups);
            } else {
                high = new long[groups];
                low = new long[groups];
            }
        }

        @Override
        void result(int group) {
            if (counts[group] == 0) {
                outNulls[group] = true;
                if (out != null) {
                    out.appendNull();
                }
                return;
            }
            BigInteger big = bigs == null ? null : bigs[group];
            long sum = sums[group];
            if (average) {
                double total = big != null ? HashAggregateOperator.toDouble(big) : toDouble(sum);
                // As DuckDB: the sum as a double, divided by the count and, for a decimal, its power of ten.
                averages[group] = scale >= 0 ? total / (counts[group] * Math.pow(10, scale))
                        : total / counts[group];
            } else if (scale >= 0) {
                DecimalVector.Builder decimals = (DecimalVector.Builder) out;
                if (big == null) {
                    decimals.appendUnscaled(sum);
                } else {
                    decimals.appendUnscaled(Values.toDecimal(new BigDecimal(big, scale), (Decimal) resultType)
                            .unscaledValue());
                }
            } else if (big == null) {
                high[group] = sum >> 63;
                low[group] = sum;
            } else {
                BigInteger checked = (BigInteger) Values.boxChecked(big, DataType.HUGEINT);
                high[group] = checked.shiftRight(64).longValue();
                low[group] = checked.longValue();
            }
        }

        @Override
        Vector finishResult() {
            if (average) {
                return DoubleVector.wrap(averages, outNulls);
            }
            if (out != null) {
                return out.build();
            }
            return HugeIntVector.wrap(high, low, outNulls);
        }
    }

    /** {@code sum} and {@code avg} over {@code FLOAT} and {@code DOUBLE}: a double sum per group. */
    private static final class DoubleSum extends AggregateState {
        private final int column;
        private final boolean average;
        private double[] sums = new double[16];
        private long[] counts = new long[16];
        private double[] values = new double[0];
        private boolean[] nulls = new boolean[0];
        private double[] out;
        private boolean[] outNulls;

        DoubleSum(int column, boolean average) {
            this.column = column;
            this.average = average;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            sums = grow(sums, groups);
            counts = grow(counts, groups);
            if (values.length < rows) {
                values = new double[rows];
                nulls = new boolean[rows];
            }
            readDoubles(b.column(column), rows, values, nulls);
            for (int r = 0; r < rows; r++) {
                if (!nulls[r]) {
                    int g = groupOf[r];
                    counts[g]++;
                    sums[g] += values[r];
                }
            }
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            DoubleSum o = (DoubleSum) other;
            if (o.counts[otherGroup] == 0) {
                return;
            }
            sums = grow(sums, group + 1);
            counts = grow(counts, group + 1);
            sums[group] += o.sums[otherGroup];
            counts[group] += o.counts[otherGroup];
        }

        @Override
        void startResult(int groups) {
            sums = grow(sums, groups);
            counts = grow(counts, groups);
            out = new double[groups];
            outNulls = new boolean[groups];
        }

        @Override
        void result(int group) {
            if (counts[group] == 0) {
                outNulls[group] = true;
            } else {
                out[group] = average ? sums[group] / counts[group] : sums[group];
            }
        }

        @Override
        Vector finishResult() {
            return DoubleVector.wrap(out, outNulls);
        }
    }

    /**
     * {@code min} and {@code max} over values that read as longs: the
     * integral types up to {@code BIGINT}, {@code BOOLEAN}, {@code DATE},
     * {@code TIMESTAMP} and decimals of at most 18 digits, whose unscaled
     * values of one scale order as the decimals do.
     */
    private static final class LongExtreme extends AggregateState {
        private final int column;
        private final DataType type;
        private final boolean max;
        private long[] best = new long[16];
        private boolean[] has = new boolean[16];
        private long[] values = new long[0];
        private boolean[] nulls = new boolean[0];
        private int groups;

        LongExtreme(int column, DataType type, boolean max) {
            this.column = column;
            this.type = type;
            this.max = max;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            best = grow(best, groups);
            has = grow(has, groups);
            if (values.length < rows) {
                values = new long[rows];
                nulls = new boolean[rows];
            }
            readLongs(b.column(column), rows, values, nulls);
            for (int r = 0; r < rows; r++) {
                if (nulls[r]) {
                    continue;
                }
                int g = groupOf[r];
                long x = values[r];
                if (!has[g] || (max ? x > best[g] : x < best[g])) {
                    best[g] = x;
                    has[g] = true;
                }
            }
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            LongExtreme o = (LongExtreme) other;
            if (!o.has[otherGroup]) {
                return;
            }
            best = grow(best, group + 1);
            has = grow(has, group + 1);
            long x = o.best[otherGroup];
            if (!has[group] || (max ? x > best[group] : x < best[group])) {
                best[group] = x;
                has[group] = true;
            }
        }

        @Override
        void startResult(int groups) {
            this.groups = groups;
            best = grow(best, groups);
            has = grow(has, groups);
        }

        @Override
        void result(int group) {
        }

        @Override
        Vector finishResult() {
            boolean[] missing = new boolean[groups];
            for (int g = 0; g < groups; g++) {
                missing[g] = !has[g];
            }
            if (type instanceof Decimal d) {
                return DecimalVector.wrap(d, Arrays.copyOf(best, groups), missing);
            }
            switch ((DataType.Scalar) type) {
                case BOOLEAN -> {
                    boolean[] out = new boolean[groups];
                    for (int g = 0; g < groups; g++) {
                        out[g] = best[g] != 0;
                    }
                    return BooleanVector.wrap(out, missing);
                }
                case TINYINT -> {
                    byte[] out = new byte[groups];
                    for (int g = 0; g < groups; g++) {
                        out[g] = (byte) best[g];
                    }
                    return TinyIntVector.wrap(out, missing);
                }
                case SMALLINT -> {
                    short[] out = new short[groups];
                    for (int g = 0; g < groups; g++) {
                        out[g] = (short) best[g];
                    }
                    return SmallIntVector.wrap(out, missing);
                }
                case INTEGER, DATE -> {
                    int[] out = new int[groups];
                    for (int g = 0; g < groups; g++) {
                        out[g] = (int) best[g];
                    }
                    return IntVector.wrap(type, out, missing);
                }
                case BIGINT, TIMESTAMP -> {
                    return BigIntVector.wrap(type, Arrays.copyOf(best, groups), missing);
                }
                default -> throw new IllegalStateException("No longs for " + type);
            }
        }
    }

    /** {@code min} and {@code max} over {@code FLOAT} and {@code DOUBLE}. */
    private static final class DoubleExtreme extends AggregateState {
        private final int column;
        private final DataType type;
        private final boolean max;
        private double[] best = new double[16];
        private boolean[] has = new boolean[16];
        private double[] values = new double[0];
        private boolean[] nulls = new boolean[0];
        private int groups;

        DoubleExtreme(int column, DataType type, boolean max) {
            this.column = column;
            this.type = type;
            this.max = max;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            best = grow(best, groups);
            has = grow(has, groups);
            if (values.length < rows) {
                values = new double[rows];
                nulls = new boolean[rows];
            }
            readDoubles(b.column(column), rows, values, nulls);
            for (int r = 0; r < rows; r++) {
                if (nulls[r]) {
                    continue;
                }
                int g = groupOf[r];
                double x = values[r];
                if (!has[g]) {
                    best[g] = x;
                    has[g] = true;
                } else {
                    int c = Vectors.compare(x, best[g]);
                    if (max ? c > 0 : c < 0) {
                        best[g] = x;
                    }
                }
            }
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            DoubleExtreme o = (DoubleExtreme) other;
            if (!o.has[otherGroup]) {
                return;
            }
            best = grow(best, group + 1);
            has = grow(has, group + 1);
            double x = o.best[otherGroup];
            int c = has[group] ? Vectors.compare(x, best[group]) : 0;
            if (!has[group] || (max ? c > 0 : c < 0)) {
                best[group] = x;
                has[group] = true;
            }
        }

        @Override
        void startResult(int groups) {
            this.groups = groups;
            best = grow(best, groups);
            has = grow(has, groups);
        }

        @Override
        void result(int group) {
        }

        @Override
        Vector finishResult() {
            boolean[] missing = new boolean[groups];
            for (int g = 0; g < groups; g++) {
                missing[g] = !has[g];
            }
            if (type == DataType.FLOAT) {
                float[] out = new float[groups];
                for (int g = 0; g < groups; g++) {
                    out[g] = (float) best[g];
                }
                return FloatVector.wrap(out, missing);
            }
            return DoubleVector.wrap(Arrays.copyOf(best, groups), missing);
        }
    }
}
