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

package net.fyrql.core.type;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Helpers for the Java values of the types: ranges of the integral types,
 * narrowing with a range check, and the check of a value against its type.
 */
public final class Values {

    /** The smallest {@code HUGEINT}, {@code -2^127}. */
    public static final BigInteger HUGEINT_MIN = BigInteger.ONE.shiftLeft(127).negate();

    /** The largest {@code HUGEINT}, {@code 2^127 - 1}. */
    public static final BigInteger HUGEINT_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

    private Values() {
    }

    /** Returns the smallest value of an integral type that fits into a {@code long}. */
    public static long min(DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> Byte.MIN_VALUE;
            case SMALLINT -> Short.MIN_VALUE;
            case INTEGER -> Integer.MIN_VALUE;
            case BIGINT -> Long.MIN_VALUE;
            default -> throw new IllegalArgumentException("Not an integral type within long: " + type);
        };
    }

    /** Returns the largest value of an integral type that fits into a {@code long}. */
    public static long max(DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> Byte.MAX_VALUE;
            case SMALLINT -> Short.MAX_VALUE;
            case INTEGER -> Integer.MAX_VALUE;
            case BIGINT -> Long.MAX_VALUE;
            default -> throw new IllegalArgumentException("Not an integral type within long: " + type);
        };
    }

    /** Tells whether a value lies within the 128-bit range of {@code HUGEINT}. */
    public static boolean inHugeIntRange(BigInteger value) {
        return value.compareTo(HUGEINT_MIN) >= 0 && value.compareTo(HUGEINT_MAX) <= 0;
    }

    /**
     * Returns the value unchanged if it is in the range of the type.
     *
     * @throws ArithmeticException otherwise, as DuckDB raises an out-of-range
     *         error
     */
    public static long checkRange(long value, DataType type) {
        if (type == DataType.HUGEINT) {
            return value;
        }
        if (value < min(type) || value > max(type)) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return value;
    }

    /**
     * Boxes a value that is known to be in the range of an integral type;
     * {@code HUGEINT} becomes a {@code BigInteger}.
     */
    public static Object box(long value, DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> (byte) value;
            case SMALLINT -> (short) value;
            case INTEGER -> (int) value;
            case BIGINT -> value;
            case HUGEINT -> BigInteger.valueOf(value);
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }

    /**
     * Boxes a value with a range check.
     *
     * @throws ArithmeticException if the value is out of range
     */
    public static Object boxChecked(long value, DataType type) {
        return box(checkRange(value, type), type);
    }

    /**
     * Boxes a {@code BigInteger} into an integral type.
     *
     * @throws ArithmeticException if the value is out of range
     */
    public static Object boxChecked(BigInteger value, DataType type) {
        if (type == DataType.HUGEINT) {
            if (!inHugeIntRange(value)) {
                throw new ArithmeticException("Out of range for HUGEINT: " + value);
            }
            return value;
        }
        if (value.bitLength() >= 64) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return boxChecked(value.longValue(), type);
    }

    /**
     * Returns a decimal with exactly the scale of the type, rounding half away
     * from zero as DuckDB does.
     *
     * @throws ArithmeticException if the value does not fit into the precision
     */
    public static BigDecimal toDecimal(BigDecimal value, DataType.Decimal type) {
        BigDecimal scaled = value.setScale(type.scale(), java.math.RoundingMode.HALF_UP);
        if (scaled.unscaledValue().abs().compareTo(type.maxUnscaled()) > 0) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return scaled;
    }

    /**
     * Checks that a value belongs to a type.
     *
     * @return the value
     * @throws IllegalArgumentException otherwise
     */
    public static Object require(DataType type, Object value) {
        if (!type.accepts(value)) {
            throw new IllegalArgumentException("Not a " + type + ": " + describe(value));
        }
        return value;
    }

    /** Describes a value for a message, with its class. */
    public static String describe(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        return value.getClass().getSimpleName() + " " + value;
    }

    private static DataType.Scalar scalar(DataType type) {
        if (type instanceof DataType.Scalar s) {
            return s;
        }
        throw new IllegalArgumentException("Not a scalar type: " + type);
    }
}
