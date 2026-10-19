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

package net.fyrql.core.function;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * The numeric functions, with the result types and edge cases measured
 * against DuckDB 1.5.6.
 * <ul>
 * <li>{@code abs}, {@code trunc} and {@code round} keep the type of an
 * integral argument; {@code ceil} and {@code floor} turn it into a
 * {@code DOUBLE}. On a {@code DECIMAL(p,s)} all four give
 * {@code DECIMAL(p,0)}.</li>
 * <li>{@code round(x, n)} on an integral type rounds to a power of ten for
 * a negative {@code n} and may overflow; on a decimal it is not offered,
 * because its type would depend on the value of {@code n}.</li>
 * <li>{@code sqrt}, {@code ln}, {@code log10}, {@code log}, {@code log2},
 * {@code exp}, {@code power} and {@code pi} are {@code DOUBLE}; the square
 * root of a negative number and the logarithm of zero or a negative number
 * are errors, as in DuckDB, while NaN passes through.</li>
 * <li>{@code sign} is a {@code TINYINT}; NaN has the sign 0.</li>
 * </ul>
 */
final class NumericFunctions {

    private NumericFunctions() {
    }

    static List<ScalarFunction> all() {
        return List.of(ABS, CEIL, CEIL.alias("ceiling"), FLOOR, TRUNC, ROUND, SIGN, SQRT, LN, LOG10, LOG10.alias("log"),
                LOG_BASE, LOG2, EXP, POWER, POWER.alias("pow"), PI, RANDOM);
    }

    /** The rule of a unary function that keeps a numeric type. */
    private static final Function<List<DataType>, DataType> SAME_NUMERIC =
            types -> types.size() == 1 && types.get(0).isNumeric() ? types.get(0) : null;

    /** The rule of a unary function that gives {@code DOUBLE} for integers and decimals, else keeps the type. */
    private static final Function<List<DataType>, DataType> INTEGRAL_TO_DOUBLE = types -> {
        if (types.size() != 1 || !types.get(0).isNumeric()) {
            return null;
        }
        DataType t = types.get(0);
        if (t instanceof Decimal d) {
            return DataType.decimal(d.precision(), 0);
        }
        return t.isIntegral() ? DataType.DOUBLE : t;
    };

    /** The rule of a unary function that keeps the type except for a decimal, which loses its scale. */
    private static final Function<List<DataType>, DataType> INTEGRAL_KEPT = types -> {
        if (types.size() != 1 || !types.get(0).isNumeric()) {
            return null;
        }
        DataType t = types.get(0);
        return t instanceof Decimal d ? DataType.decimal(d.precision(), 0) : t;
    };

    /** The rule of a function over numeric arguments that gives {@code DOUBLE}. */
    private static Function<List<DataType>, DataType> doubleOf(int arity) {
        return types -> {
            if (types.size() != arity) {
                return null;
            }
            for (DataType t : types) {
                if (!t.isNumeric()) {
                    return null;
                }
            }
            return DataType.DOUBLE;
        };
    }

    /** Casts an integral argument to {@code DOUBLE}, as {@code ceil} and {@code floor} do. */
    private static List<DataType> integralToDouble(List<DataType> types) {
        return types.get(0).isIntegral() ? List.of(DataType.DOUBLE) : types;
    }

    static final SimpleFunction ABS = new SimpleFunction("abs", SAME_NUMERIC) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(0);
            Object v = args[0];
            if (t instanceof Decimal) {
                return ((BigDecimal) v).abs();
            }
            return switch ((Scalar) t) {
                case TINYINT, SMALLINT, INTEGER, BIGINT -> {
                    long x = ((Number) v).longValue();
                    if (x == Long.MIN_VALUE) {
                        throw new ArithmeticException("Overflow in abs(" + x + ")");
                    }
                    yield Values.boxChecked(Math.abs(x), t);
                }
                case HUGEINT -> Values.boxChecked(((BigInteger) v).abs(), t);
                case FLOAT -> Math.abs((Float) v);
                case DOUBLE -> Math.abs((Double) v);
                default -> throw new IllegalStateException("Not numeric: " + t);
            };
        }
    };

    static final SimpleFunction CEIL = new Rounding("ceil", INTEGRAL_TO_DOUBLE, Math::ceil, RoundingMode.CEILING);

    static final SimpleFunction FLOOR = new Rounding("floor", INTEGRAL_TO_DOUBLE, Math::floor, RoundingMode.FLOOR);

    static final SimpleFunction TRUNC = new Rounding("trunc", INTEGRAL_KEPT,
            v -> v < 0 ? Math.ceil(v) : Math.floor(v), RoundingMode.DOWN);

    /** A unary rounding function: a floating-point rule, a decimal mode, identity on integers. */
    private static class Rounding extends SimpleFunction {
        private final DoubleUnaryOperator floating;
        private final RoundingMode decimal;

        Rounding(String name, Function<List<DataType>, DataType> resultType, DoubleUnaryOperator floating,
                RoundingMode decimal) {
            super(name, resultType, resultType == INTEGRAL_TO_DOUBLE ? NumericFunctions::integralToDouble
                    : java.util.function.UnaryOperator.identity());
            this.floating = floating;
            this.decimal = decimal;
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(0);
            if (t instanceof Decimal) {
                return ((BigDecimal) args[0]).setScale(0, decimal);
            }
            if (t == DataType.DOUBLE) {
                return floating.applyAsDouble((Double) args[0]);
            }
            if (t == DataType.FLOAT) {
                return (float) floating.applyAsDouble((Float) args[0]);
            }
            return args[0];
        }
    }

    /**
     * {@code round(x)} and {@code round(x, n)}: halves round away from zero.
     * A floating-point value scales by a power of ten, rounds and scales
     * back, and stays unchanged if that overflows, as DuckDB computes it.
     * An integral value with a negative {@code n} rounds to a power of ten
     * and may leave its type, which is an error. A decimal takes a constant
     * {@code n} only, which {@link #specialize} reads: the result keeps the
     * precision and has {@code n} places, at most as many as before and at
     * least none.
     */
    static final SimpleFunction ROUND = new SimpleFunction("round", types -> {
        if (types.isEmpty() || types.size() > 2 || !types.get(0).isNumeric()
                || types.size() == 2 && !SimpleFunction.isInt32(types.get(1))) {
            return null;
        }
        DataType x = types.get(0);
        if (x instanceof Decimal d) {
            // With n, the type of the specialized function; until then the scale stays.
            return types.size() == 1 ? DataType.decimal(d.precision(), 0) : d;
        }
        return x;
    }, SimpleFunction.from(1, DataType.INTEGER)) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            if (arguments.size() != 2 || !(arguments.get(0).type() instanceof Decimal)) {
                return this;
            }
            Object places;
            try {
                places = Expressions.constantValue(arguments.get(1).type() == DataType.INTEGER ? arguments.get(1)
                        : new Expression.Cast(arguments.get(1), DataType.INTEGER));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("ROUND(DECIMAL, INTEGER) with non-constant precision is not supported",
                        e);
            }
            return places == null ? this : roundDecimal((Integer) places);
        }

        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(0);
            long places = args.length > 1 ? (Integer) args[1] : 0;
            if (t instanceof Decimal d) {
                BigDecimal rounded = ((BigDecimal) args[0]).setScale((int) Math.max(-40, Math.min(places, d.scale())),
                        RoundingMode.HALF_UP);
                return args.length > 1 ? rounded.setScale(d.scale()) : rounded;
            }
            if (t.isIntegral()) {
                if (places >= 0) {
                    return args[0];
                }
                if (places < -40) {
                    return Values.boxChecked(BigInteger.ZERO, t);
                }
                BigDecimal v = t == DataType.HUGEINT ? new BigDecimal((BigInteger) args[0])
                        : BigDecimal.valueOf(((Number) args[0]).longValue());
                return Values.boxChecked(v.setScale((int) places, RoundingMode.HALF_UP).toBigIntegerExact(), t);
            }
            double v = ((Number) args[0]).doubleValue();
            double rounded = round(v, places);
            return t == DataType.FLOAT ? (Object) (float) rounded : (Object) rounded;
        }

        /**
         * As DuckDB rounds a double to a number of places: the value is
         * scaled by a power of ten; if that is infinite or NaN, the result
         * is 0 for negative places and the input for positive ones.
         */
        private double round(double v, long places) {
            if (places < 0) {
                double modifier = Math.pow(10, -places);
                double scaled = v / modifier;
                if (Double.isInfinite(scaled) || Double.isNaN(scaled)) {
                    return 0;
                }
                return Casts.roundHalfAwayFromZero(scaled) * modifier;
            }
            if (places == 0) {
                return Double.isNaN(v) || Double.isInfinite(v) ? v : Casts.roundHalfAwayFromZero(v);
            }
            double modifier = Math.pow(10, places);
            double scaled = v * modifier;
            if (Double.isInfinite(scaled) || Double.isNaN(scaled)) {
                return v;
            }
            return Casts.roundHalfAwayFromZero(scaled) / modifier;
        }
    };

    /** {@code round(x, n)} of a {@code DECIMAL(p,s)} for a constant {@code n}: {@code DECIMAL(p, min(s, max(n, 0)))}. */
    static SimpleFunction roundDecimal(int places) {
        return new SimpleFunction("round", types -> types.size() == 2 && types.get(0) instanceof Decimal d
                && SimpleFunction.isInt32(types.get(1))
                ? DataType.decimal(d.precision(), Math.min(d.scale(), Math.max(places, 0))) : null,
                SimpleFunction.from(1, DataType.INTEGER)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                Decimal d = (Decimal) types.get(0);
                int scale = Math.min(d.scale(), Math.max(places, 0));
                // Rounding to fewer places frees a digit for the carry, so the precision always holds.
                return ((BigDecimal) args[0]).setScale(Math.max(-40, Math.min(places, d.scale())), RoundingMode.HALF_UP)
                        .setScale(scale);
            }
        };
    }

    /** {@code sign(x)} as a {@code TINYINT}; NaN counts as zero. */
    static final SimpleFunction SIGN = new SimpleFunction("sign",
            types -> types.size() == 1 && types.get(0).isNumeric() ? DataType.TINYINT : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(0);
            Object v = args[0];
            int sign;
            if (t instanceof Decimal) {
                sign = ((BigDecimal) v).signum();
            } else if (t == DataType.HUGEINT) {
                sign = ((BigInteger) v).signum();
            } else if (t.isFloatingPoint()) {
                double d = ((Number) v).doubleValue();
                sign = Double.isNaN(d) ? 0 : d > 0 ? 1 : d < 0 ? -1 : 0;
            } else {
                sign = Long.signum(((Number) v).longValue());
            }
            return (byte) sign;
        }
    };

    /** A unary {@code DOUBLE} function with a check of the domain, as DuckDB raises out-of-range errors. */
    private static SimpleFunction unaryDouble(String name, DoubleUnaryOperator op, String domainError,
            java.util.function.DoublePredicate outOfDomain) {
        return new SimpleFunction(name, doubleOf(1), SimpleFunction.allTo(DataType.DOUBLE)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                double x = (Double) args[0];
                if (!Double.isNaN(x) && outOfDomain.test(x)) {
                    throw new ArithmeticException(domainError + ": " + x);
                }
                return op.applyAsDouble(x);
            }
        };
    }

    static final SimpleFunction SQRT = unaryDouble("sqrt", Math::sqrt, "Cannot take square root of a negative number",
            x -> x < 0);

    static final SimpleFunction LN = unaryDouble("ln", Math::log, "Cannot take logarithm of zero or a negative number",
            x -> x <= 0);

    static final SimpleFunction LOG10 = unaryDouble("log10", Math::log10,
            "Cannot take logarithm of zero or a negative number", x -> x <= 0);

    static final SimpleFunction LOG2 = unaryDouble("log2", x -> Math.log(x) / Math.log(2),
            "Cannot take logarithm of zero or a negative number", x -> x <= 0);

    static final SimpleFunction EXP = unaryDouble("exp", Math::exp, "", x -> false);

    /** {@code log(b, x)}: the logarithm of {@code x} to the base {@code b}. */
    static final SimpleFunction LOG_BASE = new SimpleFunction("log", doubleOf(2), SimpleFunction.allTo(DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            double base = (Double) args[0];
            double x = (Double) args[1];
            if (!Double.isNaN(base) && base <= 0 || !Double.isNaN(x) && x <= 0) {
                throw new ArithmeticException("Cannot take logarithm of zero or a negative number: log(" + base + ", "
                        + x + ")");
            }
            double divisor = Math.log(base);
            if (divisor == 0) {
                // DuckDB's message, spelling included.
                throw new ArithmeticException("divison by zero in based logarithm");
            }
            return Math.log(x) / divisor;
        }
    };

    /** {@code power(x, y)} as a {@code DOUBLE}, with the special cases of {@link Math#pow}. */
    static final SimpleFunction POWER = new SimpleFunction("power", doubleOf(2), SimpleFunction.allTo(DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Math.pow((Double) args[0], (Double) args[1]);
        }
    };

    static final SimpleFunction PI = new SimpleFunction("pi", types -> types.isEmpty() ? DataType.DOUBLE : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Math.PI;
        }
    };

    /** {@code random()}: a {@code DOUBLE} in {@code [0, 1)}; not deterministic. */
    static final SimpleFunction RANDOM = new SimpleFunction("random", types -> types.isEmpty() ? DataType.DOUBLE : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        }
    };
}
