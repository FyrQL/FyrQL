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
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * The semantics of arithmetic on single values, shared by the
 * {@link Evaluator} and by generated code, so that both compute the same
 * results and raise the same errors with the same messages.
 * <p>
 * The primitive methods leave {@code NULL} to the caller: before calling
 * them, it asks {@link #nullDivisor(ArithmeticOp, long)} or
 * {@link #nullDivisor(ArithmeticOp, double)} whether the operation gives
 * {@code NULL}, and does not call them for {@code NULL} operands.
 */
public final class ScalarOps {

    /** The largest unscaled value of a decimal by its precision up to 18, {@code 10^p - 1}. */
    private static final long[] MAX_UNSCALED = new long[19];

    static {
        long p = 1;
        for (int i = 0; i < MAX_UNSCALED.length; i++) {
            MAX_UNSCALED[i] = p - 1;
            p *= 10;
        }
    }

    private ScalarOps() {
    }

    /** Tells whether an integral or decimal operation gives {@code NULL} for a divisor: {@code //} and {@code %} by zero. */
    public static boolean nullDivisor(ArithmeticOp op, long divisor) {
        return divisor == 0 && (op == ArithmeticOp.INT_DIVIDE || op == ArithmeticOp.REMAINDER);
    }

    /** Tells whether a floating-point operation gives {@code NULL} for a divisor: {@code //} by zero. */
    public static boolean nullDivisor(ArithmeticOp op, double divisor) {
        return divisor == 0 && op == ArithmeticOp.INT_DIVIDE;
    }

    /**
     * Integral arithmetic in {@code long}, checked against the range of the
     * result type, which is the type of the operands; the caller has
     * excluded a divisor that gives {@code NULL}.
     *
     * @throws ArithmeticException on overflow
     */
    public static long integral(ArithmeticOp op, long x, long y, DataType resultType) {
        if (op == ArithmeticOp.INT_DIVIDE && x == Long.MIN_VALUE && y == -1) {
            throw new ArithmeticException("Overflow in // of BIGINT");
        }
        // As in DuckDB, which divides to get the remainder, the minimum of a type modulo -1 overflows.
        if (op == ArithmeticOp.REMAINDER && y == -1 && Values.min(resultType) == x) {
            throw new ArithmeticException("Overflow in division of " + x + " / -1");
        }
        long v = switch (op) {
            case ADD -> Math.addExact(x, y);
            case SUBTRACT -> Math.subtractExact(x, y);
            case MULTIPLY -> Math.multiplyExact(x, y);
            case INT_DIVIDE -> x / y;
            case REMAINDER -> x % y;
            case DIVIDE -> throw new IllegalStateException("/ is floating-point");
        };
        return Values.checkRange(v, resultType);
    }

    /**
     * Floating-point arithmetic. As in DuckDB, {@code //} on floating-point
     * operands is the plain division, and {@code %} by zero gives NaN; the
     * caller has excluded {@code //} by zero, which gives {@code NULL}.
     * {@code FLOAT} operands are computed as doubles and the result is
     * narrowed to {@code float}.
     */
    public static double floating(ArithmeticOp op, double x, double y) {
        return switch (op) {
            case ADD -> x + y;
            case SUBTRACT -> x - y;
            case MULTIPLY -> x * y;
            case DIVIDE, INT_DIVIDE -> x / y;
            case REMAINDER -> x % y;
        };
    }

    /**
     * Arithmetic on the unscaled values of decimals of at most 18 digits,
     * giving the unscaled value of the result type, which has at most 18
     * digits as well; the caller has excluded a divisor that gives
     * {@code NULL}. The operands of {@code +}, {@code -} and {@code %} have
     * the scale of the result; the factors of {@code *} have their own
     * scales, which add up to it.
     *
     * @throws ArithmeticException if the result does not fit the type
     */
    public static long decimal(ArithmeticOp op, long x, long y, Decimal resultType) {
        long v;
        switch (op) {
            case ADD -> v = x + y;
            case SUBTRACT -> v = x - y;
            case REMAINDER -> v = x % y;
            case MULTIPLY -> {
                long high = Math.multiplyHigh(x, y);
                v = x * y;
                if (high != (v >> 63)) {
                    BigDecimal exact = new BigDecimal(BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)),
                            resultType.scale());
                    throw new ArithmeticException("Overflow in " + op.symbol() + " of " + resultType + ": " + exact);
                }
            }
            default -> throw new IllegalStateException(op + " on decimals is floating-point");
        }
        if (Math.abs(v) > MAX_UNSCALED[resultType.precision()]) {
            throw new ArithmeticException("Overflow in " + op.symbol() + " of " + resultType + ": "
                    + BigDecimal.valueOf(v, resultType.scale()));
        }
        return v;
    }

    /**
     * Negates an integral value of a type that fits into a {@code long}.
     *
     * @throws ArithmeticException on overflow
     */
    public static long negate(long x, DataType type) {
        return Values.checkRange(Math.negateExact(x), type);
    }

    /**
     * Adds a number of days to a date or subtracts it from one; the result
     * must stay in the range of dates of DuckDB.
     *
     * @throws ArithmeticException otherwise
     */
    public static int shiftDate(ArithmeticOp op, long days, long shift) {
        return Dates.checkDays(op == ArithmeticOp.SUBTRACT ? days - shift : days + shift);
    }

    /**
     * Arithmetic on {@code TIME}, {@code TIMESTAMP}, {@code DATE} and
     * {@code INTERVAL} values as {@link net.fyrql.core.type.TypeRules#add}
     * and its siblings type it; {@code null} for a division by zero. The
     * operands are not {@code null}.
     *
     * @throws ArithmeticException if the result is out of range
     */
    public static Object temporal(ArithmeticOp op, Object l, Object r, DataType lt, DataType rt) {
        if (lt == Scalar.INTERVAL && rt == Scalar.INTERVAL) {
            Interval x = (Interval) l;
            Interval y = (Interval) r;
            return op == ArithmeticOp.ADD ? x.plus(y) : x.minus(y);
        }
        if (op == ArithmeticOp.MULTIPLY) {
            return lt == Scalar.INTERVAL ? scale((Interval) l, r, rt) : scale((Interval) r, l, lt);
        }
        if (op == ArithmeticOp.DIVIDE) {
            double divisor = toDouble(r, rt);
            return divisor == 0 ? null : ((Interval) l).dividedBy(divisor);
        }
        if (lt == Scalar.INTERVAL) {
            return temporal(op, r, l, rt, lt);
        }
        if (rt == Scalar.INTERVAL) {
            Interval shift = op == ArithmeticOp.SUBTRACT ? ((Interval) r).negate() : (Interval) r;
            return switch ((Scalar) lt) {
                case TIME -> Dates.addToTime((Long) l, shift.micros());
                case DATE -> Dates.add(Conversions.dateToTimestamp((Integer) l), shift);
                default -> Dates.add((Long) l, shift);
            };
        }
        if (op == ArithmeticOp.ADD) {
            // A date and a time of day.
            return lt == Scalar.DATE ? Dates.timestamp((Integer) l, (Long) r) : Dates.timestamp((Integer) r, (Long) l);
        }
        return Dates.subtract(micros(l, lt), micros(r, rt));
    }

    private static long micros(Object value, DataType type) {
        return type == Scalar.DATE ? Conversions.dateToTimestamp((Integer) value) : (Long) value;
    }

    private static Interval scale(Interval interval, Object factor, DataType type) {
        if (type.isIntegral()) {
            BigInteger big = factor instanceof BigInteger b ? b : BigInteger.valueOf(((Number) factor).longValue());
            if (big.bitLength() < 64) {
                return interval.times(big.longValue());
            }
        }
        return interval.times(toDouble(factor, type));
    }

    private static double toDouble(Object value, DataType type) {
        return type instanceof Decimal ? ((BigDecimal) value).doubleValue() : ((Number) value).doubleValue();
    }

    /**
     * Arithmetic on Java values of the operand type, giving a value of the
     * result type, or {@code null} where the operation gives {@code NULL};
     * the operands are not {@code null}.
     *
     * @throws ArithmeticException on overflow
     */
    public static Object arithmetic(ArithmeticOp op, Object l, Object r, DataType operandType, DataType resultType) {
        if (operandType instanceof Decimal) {
            BigDecimal x = (BigDecimal) l;
            BigDecimal y = (BigDecimal) r;
            if (resultType == DataType.DOUBLE) {
                double b = y.doubleValue();
                return nullDivisor(op, b) ? null : floating(op, x.doubleValue(), b);
            }
            Decimal rt = (Decimal) resultType;
            BigDecimal v = switch (op) {
                case ADD -> x.add(y);
                case SUBTRACT -> x.subtract(y);
                case MULTIPLY -> x.multiply(y);
                case REMAINDER -> y.signum() == 0 ? null : x.remainder(y);
                default -> throw new IllegalStateException(op + " on decimals is floating-point");
            };
            if (v == null) {
                return null;
            }
            BigDecimal scaled = v.setScale(rt.scale(), RoundingMode.UNNECESSARY);
            if (scaled.unscaledValue().abs().compareTo(rt.maxUnscaled()) > 0) {
                throw new ArithmeticException("Overflow in " + op.symbol() + " of " + rt + ": " + v);
            }
            return scaled;
        }
        return switch ((Scalar) operandType) {
            case TINYINT, SMALLINT, INTEGER, BIGINT -> {
                long x = ((Number) l).longValue();
                long y = ((Number) r).longValue();
                yield nullDivisor(op, y) ? null : Values.box(integral(op, x, y, resultType), resultType);
            }
            case HUGEINT -> {
                BigInteger x = (BigInteger) l;
                BigInteger y = (BigInteger) r;
                if (op == ArithmeticOp.REMAINDER && y.equals(BigInteger.ONE.negate())
                        && x.equals(Values.HUGEINT_MIN)) {
                    throw new ArithmeticException("Overflow in division of " + x + " / -1");
                }
                BigInteger v = switch (op) {
                    case ADD -> x.add(y);
                    case SUBTRACT -> x.subtract(y);
                    case MULTIPLY -> x.multiply(y);
                    case INT_DIVIDE -> y.signum() == 0 ? null : x.divide(y);
                    case REMAINDER -> y.signum() == 0 ? null : x.remainder(y);
                    case DIVIDE -> throw new IllegalStateException("/ is floating-point");
                };
                yield v == null ? null : Values.boxChecked(v, DataType.HUGEINT);
            }
            case FLOAT -> {
                float y = (Float) r;
                yield nullDivisor(op, y) ? null : (float) floating(op, (Float) l, y);
            }
            case DOUBLE -> {
                double y = (Double) r;
                yield nullDivisor(op, y) ? null : floating(op, (Double) l, y);
            }
            default -> throw new IllegalStateException("Not numeric: " + operandType);
        };
    }

    /**
     * Negates a Java value of a numeric type or an interval.
     *
     * @throws ArithmeticException on overflow
     */
    public static Object negate(Object x, DataType type) {
        if (type instanceof Decimal) {
            return ((BigDecimal) x).negate();
        }
        if (type == Scalar.INTERVAL) {
            return ((Interval) x).negate();
        }
        return switch ((Scalar) type) {
            case TINYINT, SMALLINT, INTEGER, BIGINT -> Values.box(negate(((Number) x).longValue(), type), type);
            case HUGEINT -> Values.boxChecked(((BigInteger) x).negate(), type);
            case FLOAT -> -(Float) x;
            case DOUBLE -> -(Double) x;
            default -> throw new IllegalStateException("Not numeric: " + type);
        };
    }
}
