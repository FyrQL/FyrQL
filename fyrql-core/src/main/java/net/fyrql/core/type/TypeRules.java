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

package net.fyrql.core.type;

import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;

import java.util.ArrayList;
import java.util.List;

/**
 * The typing rules of arithmetic, measured against DuckDB 1.5.6 for every
 * pair of numeric types.
 * <p>
 * Two integral operands keep the wider of their types, in the order
 * {@code TINYINT < SMALLINT < INTEGER < BIGINT < HUGEINT}; there is no
 * promotion to {@code INTEGER}, and an overflow is an error. {@code DOUBLE}
 * absorbs every other operand, then {@code FLOAT}, also a decimal. An
 * integral operand next to a {@code DECIMAL} becomes the decimal that holds
 * its range ({@code DECIMAL(10,0)} for {@code INTEGER}). Two decimals follow
 * DuckDB's width rules, which keep a result within 18 digits when both
 * operands fit into 18 digits, and never exceed 38. Division with {@code /}
 * gives {@code DOUBLE}, or {@code FLOAT} if an operand is a {@code FLOAT} and
 * none a {@code DOUBLE}; {@code //} of decimals gives {@code DOUBLE} as well.
 */
public final class TypeRules {

    /** The width up to which DuckDB stores a decimal in 64 bits. */
    private static final int INT64_WIDTH = 18;

    private static final List<Scalar> INTEGRAL_ORDER = List.of(Scalar.TINYINT, Scalar.SMALLINT, Scalar.INTEGER,
            Scalar.BIGINT, Scalar.HUGEINT);

    private TypeRules() {
    }

    /**
     * Returns the type of {@code a + b} and {@code a - b}.
     *
     * @throws IllegalArgumentException if a type is not numeric
     */
    public static DataType numeric(DataType a, DataType b) {
        DataType floating = floating(a, b);
        if (floating != null) {
            return floating;
        }
        if (a instanceof Decimal || b instanceof Decimal) {
            Decimal da = asDecimal(a);
            Decimal db = asDecimal(b);
            int scale = Math.max(da.scale(), db.scale());
            int width = Math.max(da.precision() - da.scale(), db.precision() - db.scale()) + scale;
            // One digit for the carry, but two 64-bit operands give a 64-bit result, whose overflow is checked.
            int required = width + 1;
            if (required > INT64_WIDTH && Math.max(da.precision(), db.precision()) <= INT64_WIDTH) {
                required = INT64_WIDTH;
            }
            return DataType.decimal(Math.min(DataType.MAX_DECIMAL_PRECISION, required), scale);
        }
        return INTEGRAL_ORDER.indexOf((Scalar) a) >= INTEGRAL_ORDER.indexOf((Scalar) b) ? a : b;
    }

    /**
     * Returns the type of {@code a + b}: {@code DATE} for a {@code DATE} and
     * an integral type up to {@code INTEGER} in either order, a number of
     * days, otherwise as {@link #numeric}. DuckDB adds no {@code BIGINT} to
     * a date.
     *
     * @throws IllegalArgumentException if the types do not add
     */
    public static DataType add(DataType a, DataType b) {
        if (a == Scalar.DATE && isInt32(b) || isInt32(a) && b == Scalar.DATE) {
            return Scalar.DATE;
        }
        return numeric(a, b);
    }

    /**
     * Returns the type of {@code a - b}: {@code DATE} for a {@code DATE}
     * minus an integral type up to {@code INTEGER}, {@code BIGINT} for the
     * days between two dates, otherwise as {@link #numeric}.
     *
     * @throws IllegalArgumentException if the types do not subtract
     */
    public static DataType subtract(DataType a, DataType b) {
        if (a == Scalar.DATE && isInt32(b)) {
            return Scalar.DATE;
        }
        if (a == Scalar.DATE && b == Scalar.DATE) {
            return Scalar.BIGINT;
        }
        return numeric(a, b);
    }

    private static boolean isInt32(DataType t) {
        return t == Scalar.TINYINT || t == Scalar.SMALLINT || t == Scalar.INTEGER;
    }

    /**
     * Returns the type of {@code a * b}: as {@link #numeric}, except that two
     * decimals add their widths and scales, within 18 digits if both fit into
     * 18 digits and the scale is below 18, and within 38 otherwise.
     *
     * @throws IllegalArgumentException if a type is not numeric, or if the
     *         scale of the product exceeds its width
     */
    public static DataType multiply(DataType a, DataType b) {
        DataType floating = floating(a, b);
        if (floating != null) {
            return floating;
        }
        if (a instanceof Decimal || b instanceof Decimal) {
            Decimal da = asDecimal(a);
            Decimal db = asDecimal(b);
            int width = da.precision() + db.precision();
            int scale = da.scale() + db.scale();
            if (da.precision() <= INT64_WIDTH && db.precision() <= INT64_WIDTH && scale < INT64_WIDTH) {
                width = Math.min(width, INT64_WIDTH);
            }
            return DataType.decimal(Math.min(DataType.MAX_DECIMAL_PRECISION, width), scale);
        }
        return numeric(a, b);
    }

    /**
     * Returns the type of {@code a / b}: {@code FLOAT} if an operand is a
     * {@code FLOAT} and none a {@code DOUBLE}, otherwise {@code DOUBLE}.
     *
     * @throws IllegalArgumentException if a type is not numeric
     */
    public static DataType divide(DataType a, DataType b) {
        DataType floating = floating(a, b);
        return floating != null ? floating : Scalar.DOUBLE;
    }

    /**
     * Returns the type of {@code a // b}: the common integral type, or
     * {@code DOUBLE} if a decimal takes part, or the floating-point type.
     *
     * @throws IllegalArgumentException if a type is not numeric
     */
    public static DataType intDivide(DataType a, DataType b) {
        DataType floating = floating(a, b);
        if (floating != null) {
            return floating;
        }
        if (a instanceof Decimal || b instanceof Decimal) {
            return Scalar.DOUBLE;
        }
        return numeric(a, b);
    }

    /**
     * Returns the type of {@code a % b}: as {@link #numeric} for integral and
     * floating-point types; two decimals keep the larger scale and the larger
     * number of integer digits, and become {@code DOUBLE} if that exceeds 38
     * digits.
     *
     * @throws IllegalArgumentException if a type is not numeric
     */
    public static DataType remainder(DataType a, DataType b) {
        DataType floating = floating(a, b);
        if (floating != null) {
            return floating;
        }
        if (a instanceof Decimal || b instanceof Decimal) {
            int width = maxWidth(a, b);
            return width > DataType.MAX_DECIMAL_PRECISION ? Scalar.DOUBLE : DataType.decimal(width, maxScale(a, b));
        }
        return numeric(a, b);
    }

    /** The larger scale of two integral or decimal types. */
    private static int maxScale(DataType a, DataType b) {
        return Math.max(asDecimal(a).scale(), asDecimal(b).scale());
    }

    /** The width that holds the larger integer part and the larger scale of two integral or decimal types. */
    private static int maxWidth(DataType a, DataType b) {
        Decimal da = asDecimal(a);
        Decimal db = asDecimal(b);
        return Math.max(da.precision() - da.scale(), db.precision() - db.scale()) + maxScale(a, b);
    }

    /** The floating-point result if an operand is floating-point, otherwise {@code null}. */
    private static DataType floating(DataType a, DataType b) {
        requireNumeric(a);
        requireNumeric(b);
        if (a == Scalar.DOUBLE || b == Scalar.DOUBLE) {
            return Scalar.DOUBLE;
        }
        if (a == Scalar.FLOAT || b == Scalar.FLOAT) {
            return Scalar.FLOAT;
        }
        return null;
    }

    /**
     * Returns the common type of two integral types.
     *
     * @throws IllegalArgumentException if a type is not integral
     */
    public static DataType integral(DataType a, DataType b) {
        if (!a.isIntegral() || !b.isIntegral()) {
            throw new IllegalArgumentException("Not integral: " + a + ", " + b);
        }
        return numeric(a, b);
    }

    /**
     * Returns the type in which two values are compared or united, e.g. in a
     * comparison, a {@code CASE}, a {@code COALESCE} or {@code greatest}, as
     * DuckDB picks it: the type itself if both are the same; among numeric
     * types the floating-point type if there is one, else the decimal that
     * holds the larger integer part and the larger scale of both (without
     * the carry digit of an addition, capped at 38 digits; two decimals that
     * need more give up digits of the scale), else the wider integral type. {@code BOOLEAN} unites with an integral type to that
     * type, {@code DATE} with {@code TIMESTAMP} to {@code TIMESTAMP}. Two
     * lists unite to the list of the common element type, two maps to the
     * map of the common key and value types, and two structs to the union of
     * their fields by name, whose missing fields a cast fills with
     * {@code NULL}.
     *
     * @throws IllegalArgumentException if the types are incompatible
     */
    public static DataType common(DataType a, DataType b) {
        if (a.equals(b)) {
            return a;
        }
        if (a == Scalar.BOOLEAN && b.isIntegral()) {
            return b;
        }
        if (b == Scalar.BOOLEAN && a.isIntegral()) {
            return a;
        }
        if (a.isNumeric() && b.isNumeric()) {
            DataType floating = floating(a, b);
            if (floating != null) {
                return floating;
            }
            if (a instanceof Decimal x && b instanceof Decimal y) {
                int integer = Math.max(x.precision() - x.scale(), y.precision() - y.scale());
                int width = Math.min(DataType.MAX_DECIMAL_PRECISION, maxWidth(a, b));
                return DataType.decimal(width, Math.min(maxScale(a, b), width - integer));
            }
            if (a instanceof Decimal || b instanceof Decimal) {
                return DataType.decimal(Math.min(DataType.MAX_DECIMAL_PRECISION, maxWidth(a, b)), maxScale(a, b));
            }
            return numeric(a, b);
        }
        if (a == Scalar.DATE && b == Scalar.TIMESTAMP || a == Scalar.TIMESTAMP && b == Scalar.DATE) {
            return Scalar.TIMESTAMP;
        }
        if (a instanceof DataType.ListType x && b instanceof DataType.ListType y) {
            return DataType.listOf(common(x.element(), y.element()));
        }
        if (a instanceof DataType.MapType x && b instanceof DataType.MapType y) {
            return DataType.mapOf(common(x.key(), y.key()), common(x.value(), y.value()));
        }
        if (a instanceof DataType.Struct x && b instanceof DataType.Struct y) {
            return common(x, y);
        }
        throw new IllegalArgumentException("Incompatible types: " + a + ", " + b);
    }

    /**
     * The union of two structs by the names of their fields, as DuckDB
     * 1.5.6 unites them: the fields of the first in their order, of the
     * common type where the second has a field of the name in any case, then
     * the fields only the second has.
     */
    private static DataType common(DataType.Struct a, DataType.Struct b) {
        List<DataType.Struct.Field> fields = new ArrayList<>();
        for (DataType.Struct.Field f : a.fields()) {
            int i = b.find(f.name());
            fields.add(i < 0 ? f : new DataType.Struct.Field(f.name(), common(f.type(), b.fields().get(i).type())));
        }
        for (DataType.Struct.Field f : b.fields()) {
            if (a.find(f.name()) < 0) {
                fields.add(f);
            }
        }
        return new DataType.Struct(fields);
    }

    /** Returns the common type of one or more types, see {@link #common(DataType, DataType)}. */
    public static DataType common(List<DataType> types) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("No types");
        }
        DataType result = types.get(0);
        for (DataType t : types.subList(1, types.size())) {
            result = common(result, t);
        }
        return result;
    }

    /**
     * Returns the decimal that holds every value of an integral type:
     * {@code DECIMAL(3,0)} for {@code TINYINT} up to {@code DECIMAL(38,0)} for
     * {@code HUGEINT}; a decimal is returned as is.
     */
    public static Decimal asDecimal(DataType type) {
        if (type instanceof Decimal d) {
            return d;
        }
        return switch ((Scalar) type) {
            case TINYINT -> DataType.decimal(3, 0);
            case SMALLINT -> DataType.decimal(5, 0);
            case INTEGER -> DataType.decimal(10, 0);
            case BIGINT -> DataType.decimal(19, 0);
            case HUGEINT -> DataType.decimal(38, 0);
            default -> throw new IllegalArgumentException("Not integral or decimal: " + type);
        };
    }

    /**
     * Checks that a type is numeric.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static void requireNumeric(DataType type) {
        if (!type.isNumeric()) {
            throw new IllegalArgumentException("Not numeric: " + type);
        }
    }

    /**
     * Checks that a type is the expected one.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static void require(DataType expected, DataType actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(what + " must be " + expected + ", is " + actual);
        }
    }
}
