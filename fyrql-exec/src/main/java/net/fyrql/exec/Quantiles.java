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

package net.fyrql.exec;

import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.Fraction;
import net.fyrql.core.plan.PlanNode.Fractions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.value.Order;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The values of a quantile, kept until its result, and the quantiles of
 * them as DuckDB 1.5.6 computes them, to the bit. Numbers, dates, times and
 * timestamps are kept unboxed, as longs (decimals of up to 18 digits
 * unscaled) or doubles; values of other types as objects.
 * <p>
 * The values are sorted, in descending order if the fractions count from
 * the largest. A continuous quantile of {@code n} values at fraction
 * {@code q} lies at {@code (n - 1) * q}; between two values it interpolates
 * as DuckDB does: doubles as {@code lo * (1 - d) + hi * d}, timestamps,
 * times and dates, as timestamps, the same in microseconds and rounded half
 * away from zero, decimals of up to 18 digits in their unscaled longs as
 * {@code lo + (long) ((hi - lo) * d)}, {@code FLOAT}s alike in floats, and
 * {@code HUGEINT}s and larger decimals as doubles. A discrete quantile takes
 * the value at {@code max(1, n - floor(n - n * q)) - 1}, with a fraction
 * written as a decimal computed exactly.
 */
final class Quantiles {

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    private final DataType type;
    private final boolean longs;
    private final boolean doubles;
    private long[] longValues;
    private double[] doubleValues;
    private List<Object> objects;
    private int size;

    Quantiles(DataType type) {
        this.type = type;
        this.longs = type.isIntegral() && type != DataType.HUGEINT || type == DataType.DATE || type == DataType.TIME
                || type == DataType.TIMESTAMP || type == DataType.TIMESTAMPTZ
                || type instanceof Decimal d && d.fitsLong();
        this.doubles = type.isFloatingPoint();
        if (longs) {
            longValues = new long[8];
        } else if (doubles) {
            doubleValues = new double[8];
        } else {
            objects = new ArrayList<>();
        }
    }

    /** Returns the number of values. */
    int size() {
        return size;
    }

    /** Adds a value that is not {@code NULL}. */
    void add(Object value) {
        if (longs) {
            if (size == longValues.length) {
                longValues = Arrays.copyOf(longValues, 2 * size);
            }
            longValues[size] = value instanceof BigDecimal d ? d.unscaledValue().longValue()
                    : ((Number) value).longValue();
        } else if (doubles) {
            if (size == doubleValues.length) {
                doubleValues = Arrays.copyOf(doubleValues, 2 * size);
            }
            doubleValues[size] = ((Number) value).doubleValue();
        } else {
            objects.add(value);
        }
        size++;
    }

    /** Adds the values of other quantiles of the same type. */
    void addAll(Quantiles other) {
        if (longs) {
            longValues = Arrays.copyOf(longValues, Math.max(longValues.length, size + other.size));
            System.arraycopy(other.longValues, 0, longValues, size, other.size);
        } else if (doubles) {
            doubleValues = Arrays.copyOf(doubleValues, Math.max(doubleValues.length, size + other.size));
            System.arraycopy(other.doubleValues, 0, doubleValues, size, other.size);
        } else {
            objects.addAll(other.objects);
        }
        size += other.size;
    }

    /** Removes all values. */
    void clear() {
        size = 0;
        if (objects != null) {
            objects.clear();
        }
    }

    /**
     * Returns the quantile, or the list of the quantiles, of the values;
     * {@code null} if there are none.
     */
    Object result(AggregateFunction function, Fractions fractions) {
        if (size == 0) {
            return null;
        }
        Sorted sorted = sorted(fractions.descending());
        List<Object> results = new ArrayList<>();
        for (Fraction q : fractions.values()) {
            results.add(function == AggregateFunction.QUANTILE_DISC ? sorted.value(index(q, size))
                    : continuous(sorted, q));
        }
        return fractions.list() ? results : results.get(0);
    }

    /** The index of a discrete quantile among {@code n} values. */
    static long index(Fraction q, long n) {
        long floored;
        if (q.decimal() != null) {
            // As DuckDB: in integers, (n * 10^scale - n * unscaled) / 10^scale.
            BigInteger scaling = BigInteger.TEN.pow(Math.max(0, q.decimal().scale()));
            BigInteger integral = q.decimal().scale() >= 0 ? q.decimal().unscaledValue()
                    : q.decimal().toBigInteger();
            BigInteger count = BigInteger.valueOf(n);
            floored = count.multiply(scaling).subtract(count.multiply(integral)).divide(scaling).longValueExact();
        } else {
            floored = (long) Math.floor((double) n - (double) n * q.value());
        }
        return Math.max(1, n - floored) - 1;
    }

    private Object continuous(Sorted sorted, Fraction q) {
        double rn = (double) (size - 1) * q.value();
        long frn = (long) Math.floor(rn);
        long crn = (long) Math.ceil(rn);
        double d = rn - frn;
        if (longs) {
            long lo = sorted.longAt(frn);
            long hi = sorted.longAt(crn);
            if (type instanceof Decimal decimal) {
                long value = frn == crn ? lo : lo + (long) ((double) (hi - lo) * d);
                return BigDecimal.valueOf(value, decimal.scale());
            }
            if (type == DataType.DATE) {
                lo = Math.multiplyExact(lo, MICROS_PER_DAY);
                hi = Math.multiplyExact(hi, MICROS_PER_DAY);
            }
            if (type == DataType.DATE || type == DataType.TIME || type == DataType.TIMESTAMP
                    || type == DataType.TIMESTAMPTZ) {
                return frn == crn ? lo : roundHalfAway((double) lo * (1.0 - d) + (double) hi * d);
            }
            return frn == crn ? (double) lo : interpolate(lo, d, hi);
        }
        if (doubles) {
            double lo = sorted.doubleAt(frn);
            double hi = sorted.doubleAt(crn);
            if (type == DataType.FLOAT) {
                float l = (float) lo;
                return frn == crn ? l : l + (float) ((double) ((float) hi - l) * d);
            }
            return frn == crn ? lo : interpolate(lo, d, hi);
        }
        Object lo = sorted.value(frn);
        Object hi = sorted.value(crn);
        if (type == DataType.HUGEINT) {
            double l = HashAggregateOperator.toDouble((BigInteger) lo);
            return frn == crn ? l : interpolate(l, d, HashAggregateOperator.toDouble((BigInteger) hi));
        }
        // A decimal of more than 18 digits, which DuckDB interpolates as doubles and truncates.
        BigDecimal l = (BigDecimal) lo;
        if (frn == crn) {
            return l;
        }
        double value = interpolate(HashAggregateOperator.toDouble(l.unscaledValue()), d,
                HashAggregateOperator.toDouble(((BigDecimal) hi).unscaledValue()));
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("Could not convert " + value + " to a decimal");
        }
        return new BigDecimal(new BigDecimal(value).toBigInteger(), l.scale());
    }

    private static double interpolate(double lo, double d, double hi) {
        return lo * (1.0 - d) + hi * d;
    }

    /** Rounds as C's {@code llround}: to the nearest long, halves away from zero. */
    private static long roundHalfAway(double x) {
        return x < 0 ? -Math.round(-x) : Math.round(x);
    }

    private Sorted sorted(boolean descending) {
        if (longs) {
            long[] values = Arrays.copyOf(longValues, size);
            Arrays.sort(values);
            return new Sorted(values, null, null, descending);
        }
        if (doubles) {
            double[] values = Arrays.copyOf(doubleValues, size);
            Arrays.sort(values);
            return new Sorted(null, values, null, descending);
        }
        List<Object> values = new ArrayList<>(objects);
        values.sort((a, b) -> Order.compare(a, b, type));
        return new Sorted(null, null, values, descending);
    }

    /** The values in order, read from the end in descending order. */
    private final class Sorted {
        private final long[] longs;
        private final double[] doubles;
        private final List<Object> objects;
        private final boolean descending;

        Sorted(long[] longs, double[] doubles, List<Object> objects, boolean descending) {
            this.longs = longs;
            this.doubles = doubles;
            this.objects = objects;
            this.descending = descending;
        }

        private int at(long i) {
            return (int) (descending ? size - 1 - i : i);
        }

        long longAt(long i) {
            return longs[at(i)];
        }

        double doubleAt(long i) {
            return doubles[at(i)];
        }

        /** The value at an index as the type boxes it. */
        Object value(long i) {
            if (longs != null) {
                long x = longs[at(i)];
                if (type instanceof Decimal d) {
                    return BigDecimal.valueOf(x, d.scale());
                }
                return switch ((DataType.Scalar) type) {
                    case TINYINT -> (byte) x;
                    case SMALLINT -> (short) x;
                    case INTEGER, DATE -> (int) x;
                    default -> x;
                };
            }
            if (doubles != null) {
                double x = doubles[at(i)];
                return type == DataType.FLOAT ? (Object) (float) x : (Object) x;
            }
            return objects.get(at(i));
        }
    }
}
