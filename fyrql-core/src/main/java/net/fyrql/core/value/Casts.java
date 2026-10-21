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

package net.fyrql.core.value;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantics of {@code CAST} on Java values, after DuckDB: a cast that
 * cannot represent the value is an error, never {@code NULL}. A value out of
 * the range of the target throws {@link ArithmeticException}, a string that
 * does not parse {@link ConversionException}. A floating-point or decimal
 * value cast to an integral type is rounded half away from zero, and a
 * decimal is rescaled the same way. Every type converts to and from
 * {@code VARCHAR}; the numeric types convert among each other and to
 * {@code BOOLEAN}; {@code DATE}, {@code TIMESTAMP} and {@code TIMESTAMP WITH
 * TIME ZONE} convert into each other, {@code TIMESTAMP} to {@code TIME}. The
 * conversions between {@code TIMESTAMP WITH TIME ZONE} and the local types
 * and text take place in a time zone, UTC unless one is given. A list
 * converts to a list whose element type its elements convert to, a map to
 * a map likewise, which fails if two keys become equal, and a
 * struct to a struct by the names of the fields, without regard to case: a
 * field of the target takes the field of the source with its name, or
 * {@code NULL} if there is none, and at least one name must match. A string
 * does not convert to a nested type, unlike in DuckDB.
 */
public final class Casts {

    /** The powers of ten as doubles and floats, as the literals 1e0 to 1e38 give them. */
    private static final double[] DOUBLE_POWERS = new double[39];
    private static final float[] FLOAT_POWERS = new float[39];

    static {
        for (int i = 0; i < DOUBLE_POWERS.length; i++) {
            DOUBLE_POWERS[i] = Double.parseDouble("1e" + i);
            FLOAT_POWERS[i] = Float.parseFloat("1e" + i);
        }
    }

    private Casts() {
    }

    /** Tells whether values of one type can be cast to another. */
    public static boolean allowed(DataType from, DataType to) {
        if (from.equals(to) || to == Scalar.VARCHAR || from == Scalar.VARCHAR && !to.isNested()) {
            return true;
        }
        if (from.isNumeric() && to.isNumeric()) {
            return true;
        }
        if (from == Scalar.BOOLEAN && to.isNumeric() || from.isNumeric() && to == Scalar.BOOLEAN) {
            return true;
        }
        if (isLocalDateTime(from) && (isLocalDateTime(to) || to == Scalar.TIMESTAMPTZ)
                || from == Scalar.TIMESTAMPTZ && isLocalDateTime(to) || from == Scalar.TIMESTAMP && to == Scalar.TIME) {
            return true;
        }
        return allowedNested(from, to);
    }

    private static boolean isLocalDateTime(DataType type) {
        return type == Scalar.DATE || type == Scalar.TIMESTAMP;
    }

    /**
     * Tells whether a cast from one type to another depends on the time
     * zone: one between {@code TIMESTAMP WITH TIME ZONE} and {@code VARCHAR},
     * {@code DATE} or {@code TIMESTAMP}, also inside nested types.
     */
    public static boolean needsZone(DataType from, DataType to) {
        if (from == Scalar.TIMESTAMPTZ || to == Scalar.TIMESTAMPTZ) {
            return !from.equals(to);
        }
        if (from instanceof DataType.ListType f && to instanceof DataType.ListType t) {
            return needsZone(f.element(), t.element());
        }
        if (from instanceof DataType.MapType f && to instanceof DataType.MapType t) {
            return needsZone(f.key(), t.key()) || needsZone(f.value(), t.value());
        }
        if (from instanceof DataType.Struct f && to instanceof DataType.Struct t) {
            for (DataType.Struct.Field field : t.fields()) {
                int i = f.find(field.name());
                if (i >= 0 && needsZone(f.fields().get(i).type(), field.type())) {
                    return true;
                }
            }
            return false;
        }
        return from.isNested() && to == Scalar.VARCHAR && mentions(from, Scalar.TIMESTAMPTZ)
                || to.isNested() && from == Scalar.VARCHAR && mentions(to, Scalar.TIMESTAMPTZ);
    }

    /** Tells whether a type is or contains a scalar type. */
    public static boolean mentions(DataType type, Scalar scalar) {
        if (type == scalar) {
            return true;
        }
        if (type instanceof DataType.ListType l) {
            return mentions(l.element(), scalar);
        }
        if (type instanceof DataType.MapType m) {
            return mentions(m.key(), scalar) || mentions(m.value(), scalar);
        }
        if (type instanceof DataType.Struct s) {
            for (DataType.Struct.Field f : s.fields()) {
                if (mentions(f.type(), scalar)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Converts a value; see the class description.
     *
     * @throws ArithmeticException if the value is out of range
     * @throws ConversionException if the value does not convert
     */
    public static Object cast(Object value, DataType from, DataType to) {
        return cast(value, from, to, ZoneOffset.UTC);
    }

    /**
     * Converts a value in a time zone; see the class description.
     *
     * @throws ArithmeticException if the value is out of range
     * @throws ConversionException if the value does not convert
     */
    public static Object cast(Object value, DataType from, DataType to, ZoneId zone) {
        if (value == null || from.equals(to)) {
            return value;
        }
        if (!allowed(from, to)) {
            throw new ConversionException("Cannot cast " + from + " to " + to);
        }
        if (to == Scalar.VARCHAR) {
            return format(value, from, zone);
        }
        if (from == Scalar.VARCHAR) {
            return parse((String) value, to, zone);
        }
        if (from == Scalar.BOOLEAN) {
            return numeric(((Boolean) value) ? 1L : 0L, Scalar.BIGINT, to);
        }
        if (to == Scalar.BOOLEAN) {
            return isNonZero(value, from);
        }
        if (from == Scalar.DATE || from == Scalar.TIMESTAMP || from == Scalar.TIMESTAMPTZ) {
            return temporal(value, from, to, zone);
        }
        if (to.isNested()) {
            return nested(value, from, to, false, zone);
        }
        return numeric(value, from, to);
    }

    /** Converts between {@code DATE}, {@code TIMESTAMP}, {@code TIMESTAMP WITH TIME ZONE} and {@code TIME}. */
    private static Object temporal(Object value, DataType from, DataType to, ZoneId zone) {
        long local = switch ((Scalar) from) {
            case DATE -> Conversions.dateToTimestamp((Integer) value);
            case TIMESTAMP -> (Long) value;
            default -> ZonedDates.toLocal((Long) value, zone);
        };
        return switch ((Scalar) to) {
            case DATE -> Conversions.timestampToDate(local);
            case TIMESTAMP -> local;
            case TIME -> Dates.timeOf(local);
            default -> ZonedDates.toInstant(local, zone);
        };
    }

    /**
     * Converts a value as {@code TRY_CAST} does: as {@link #cast}, but a
     * value that does not convert or is out of range becomes {@code NULL};
     * inside a list or a struct only the element or field does, as in
     * DuckDB, while a map with a key that does not convert becomes
     * {@code NULL} as a whole.
     */
    public static Object tryCast(Object value, DataType from, DataType to) {
        return tryCast(value, from, to, ZoneOffset.UTC);
    }

    /** Converts a value as {@link #tryCast(Object, DataType, DataType)} does, in a time zone. */
    public static Object tryCast(Object value, DataType from, DataType to, ZoneId zone) {
        if (value == null || from.equals(to)) {
            return value;
        }
        if (from.isNested() && to.isNested() && allowed(from, to)) {
            return nested(value, from, to, true, zone);
        }
        try {
            return cast(value, from, to, zone);
        } catch (ArithmeticException | ConversionException e) {
            return null;
        }
    }

    /** Tells whether one nested type converts to another of its kind; see the class description. */
    private static boolean allowedNested(DataType from, DataType to) {
        if (from instanceof DataType.ListType f && to instanceof DataType.ListType t) {
            return allowed(f.element(), t.element());
        }
        if (from instanceof DataType.MapType f && to instanceof DataType.MapType t) {
            return allowed(f.key(), t.key()) && allowed(f.value(), t.value());
        }
        if (from instanceof DataType.Struct f && to instanceof DataType.Struct t) {
            boolean matching = false;
            for (DataType.Struct.Field field : t.fields()) {
                int i = f.find(field.name());
                if (i >= 0) {
                    if (!allowed(f.fields().get(i).type(), field.type())) {
                        return false;
                    }
                    matching = true;
                }
            }
            return matching;
        }
        return false;
    }

    /** Converts a list, map or struct to another type of its kind, element by element. */
    private static Object nested(Object value, DataType from, DataType to, boolean safe, ZoneId zone) {
        if (from instanceof DataType.ListType f && to instanceof DataType.ListType t) {
            List<Object> result = new ArrayList<>();
            for (Object element : (List<?>) value) {
                result.add(safe ? tryCast(element, f.element(), t.element(), zone)
                        : cast(element, f.element(), t.element(), zone));
            }
            return result;
        }
        if (from instanceof DataType.MapType f && to instanceof DataType.MapType t) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                Object key = safe ? tryCast(e.getKey(), f.key(), t.key(), zone)
                        : cast(e.getKey(), f.key(), t.key(), zone);
                if (key == null || result.containsKey(key)) {
                    if (safe) {
                        return null;
                    }
                    throw new ConversionException("Map keys must be unique after the cast to " + to);
                }
                result.put(key, safe ? tryCast(e.getValue(), f.value(), t.value(), zone)
                        : cast(e.getValue(), f.value(), t.value(), zone));
            }
            return result;
        }
        DataType.Struct f = (DataType.Struct) from;
        DataType.Struct t = (DataType.Struct) to;
        List<?> values = (List<?>) value;
        List<Object> result = new ArrayList<>();
        for (DataType.Struct.Field field : t.fields()) {
            int i = f.find(field.name());
            Object v = i < 0 ? null : values.get(i);
            DataType type = i < 0 ? field.type() : f.fields().get(i).type();
            result.add(safe ? tryCast(v, type, field.type(), zone) : cast(v, type, field.type(), zone));
        }
        return result;
    }

    /** Formats a value of a type as its {@code VARCHAR} form, as DuckDB prints it, in UTC. */
    public static String format(Object value, DataType from) {
        return format(value, from, ZoneOffset.UTC);
    }

    /** Formats a value of a type as its {@code VARCHAR} form, as DuckDB prints it, in a time zone. */
    public static String format(Object value, DataType from, ZoneId zone) {
        if (value == null) {
            return null;
        }
        if (from instanceof Decimal) {
            return Conversions.formatDecimal((BigDecimal) value);
        }
        if (from instanceof DataType.Struct s) {
            List<?> values = (List<?>) value;
            StringBuilder b = new StringBuilder("{");
            for (int i = 0; i < values.size(); i++) {
                b.append(i > 0 ? ", " : "").append('\'').append(s.fields().get(i).name().replace("'", "\\'"))
                        .append("': ").append(nestedElement(values.get(i), s.fields().get(i).type(), zone));
            }
            return b.append('}').toString();
        }
        if (from instanceof DataType.ListType l) {
            StringBuilder b = new StringBuilder("[");
            int i = 0;
            for (Object v : (List<?>) value) {
                b.append(i++ > 0 ? ", " : "").append(nestedElement(v, l.element(), zone));
            }
            return b.append(']').toString();
        }
        if (from instanceof DataType.MapType m) {
            StringBuilder b = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                b.append(i++ > 0 ? ", " : "").append(nestedElement(e.getKey(), m.key(), zone)).append('=')
                        .append(nestedElement(e.getValue(), m.value(), zone));
            }
            return b.append('}').toString();
        }
        return switch ((Scalar) from) {
            case BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT, VARCHAR, UUID -> String.valueOf(value);
            case FLOAT -> Conversions.formatFloat((Float) value);
            case DOUBLE -> Conversions.formatDouble((Double) value);
            case BLOB -> Conversions.formatBlob((byte[]) value);
            case DATE -> Conversions.formatDate((Integer) value);
            case TIMESTAMP -> Conversions.formatTimestamp((Long) value);
            case TIME -> Conversions.formatTime((Long) value);
            case TIMESTAMPTZ -> Conversions.formatTimestampTz((Long) value, zone);
            case INTERVAL -> value.toString();
        };
    }

    /**
     * An element inside a nested value, as DuckDB 1.5.6 prints it:
     * {@code NULL} as such, a nested value as it is, and the text of a
     * scalar in single quotes, with {@code \'} for a quote, only where it
     * could be misread: if it is empty, spells {@code NULL} in any case,
     * starts or ends with whitespace, or contains one of
     * {@code , ' " [ ] { } ( ) : =}.
     */
    private static String nestedElement(Object value, DataType type, ZoneId zone) {
        if (value == null) {
            return "NULL";
        }
        String s = format(value, type, zone);
        return type.isNested() || !needsQuotes(s) ? s : "'" + s.replace("'", "\\'") + "'";
    }

    private static boolean needsQuotes(String s) {
        if (s.isEmpty() || s.equalsIgnoreCase("null") || isSpace(s.charAt(0)) || isSpace(s.charAt(s.length() - 1))) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (",'\"[]{}():=".indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    /** Parses the {@code VARCHAR} form of a value of a scalar or decimal type, in UTC. */
    public static Object parse(String s, DataType to) {
        return parse(s, to, ZoneOffset.UTC);
    }

    /** Parses the {@code VARCHAR} form of a value of a scalar or decimal type, in a time zone. */
    public static Object parse(String s, DataType to, ZoneId zone) {
        if (to instanceof Decimal d) {
            return Values.toDecimal(Conversions.parseDecimal(s), d);
        }
        return switch ((Scalar) to) {
            case BOOLEAN -> Conversions.parseBoolean(s);
            case TINYINT -> Conversions.parseByte(s);
            case SMALLINT -> Conversions.parseShort(s);
            case INTEGER -> Conversions.parseInt(s);
            case BIGINT -> Conversions.parseLong(s);
            case HUGEINT -> Conversions.parseHugeInt(s);
            case FLOAT -> Conversions.parseFloat(s);
            case DOUBLE -> Conversions.parseDouble(s);
            case VARCHAR -> s;
            case BLOB -> Conversions.parseBlob(s);
            case DATE -> Conversions.parseDate(s);
            case TIMESTAMP -> Conversions.parseTimestamp(s);
            case UUID -> Conversions.parseUuid(s);
            case TIME -> Conversions.parseTime(s);
            case TIMESTAMPTZ -> Conversions.parseTimestampTz(s, zone);
            case INTERVAL -> Interval.parse(s);
        };
    }

    private static boolean isNonZero(Object value, DataType from) {
        if (from instanceof Decimal) {
            return ((BigDecimal) value).signum() != 0;
        }
        return switch ((Scalar) from) {
            case FLOAT, DOUBLE -> ((Number) value).doubleValue() != 0;
            case HUGEINT -> ((BigInteger) value).signum() != 0;
            default -> ((Number) value).longValue() != 0;
        };
    }

    /** Converts between numeric types. */
    private static Object numeric(Object value, DataType from, DataType to) {
        if (from.isIntegral()) {
            if (from == Scalar.HUGEINT) {
                return fromBigInteger((BigInteger) value, to);
            }
            return fromLong(((Number) value).longValue(), to);
        }
        if (from.isFloatingPoint()) {
            return fromDouble(((Number) value).doubleValue(), from, to);
        }
        return fromDecimal((BigDecimal) value, to);
    }

    private static Object fromLong(long x, DataType to) {
        if (to instanceof Decimal d) {
            return Values.toDecimal(BigDecimal.valueOf(x), d);
        }
        return switch ((Scalar) to) {
            case FLOAT -> (float) x;
            case DOUBLE -> (double) x;
            default -> Values.boxChecked(x, to);
        };
    }

    private static Object fromBigInteger(BigInteger x, DataType to) {
        if (to instanceof Decimal d) {
            return Values.toDecimal(new BigDecimal(x), d);
        }
        return switch ((Scalar) to) {
            case FLOAT -> x.floatValue();
            case DOUBLE -> x.doubleValue();
            default -> Values.boxChecked(x, to);
        };
    }

    private static Object fromDouble(double d, DataType from, DataType to) {
        if (to == Scalar.DOUBLE) {
            return d;
        }
        if (to == Scalar.FLOAT) {
            float f = (float) d;
            if (Float.isInfinite(f) && !Double.isInfinite(d)) {
                throw new ArithmeticException("Out of range for FLOAT: " + d);
            }
            return f;
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new ArithmeticException("Cannot convert " + Conversions.formatDouble(d) + " to " + to);
        }
        if (to instanceof Decimal dec) {
            // As DuckDB: the value times the power of ten of the scale, in the precision of the source, rounded
            // with halves away from zero.
            double scaled = from == Scalar.FLOAT ? (double) ((float) d * FLOAT_POWERS[dec.scale()])
                    : d * DOUBLE_POWERS[dec.scale()];
            double rounded = roundHalfAwayFromZero(scaled);
            if (Double.isInfinite(rounded) || Math.abs(rounded) >= DOUBLE_POWERS[dec.precision()]) {
                throw new ArithmeticException("Out of range for " + to + ": " + Conversions.formatDouble(d));
            }
            return Values.toDecimal(new BigDecimal(new BigDecimal(rounded).toBigInteger(), dec.scale()), dec);
        }
        // DuckDB casts a floating-point value to an integer with nearbyint: halves to the even neighbor.
        double rounded = Math.rint(d);
        if (to == Scalar.HUGEINT) {
            return Values.boxChecked(new BigDecimal(rounded).toBigInteger(), to);
        }
        if (rounded < Values.min(to) || rounded > Values.max(to) || to == Scalar.BIGINT && rounded == 0x1p63) {
            throw new ArithmeticException("Out of range for " + to + ": " + Conversions.formatDouble(d));
        }
        return Values.box((long) rounded, to);
    }

    private static Object fromDecimal(BigDecimal bd, DataType to) {
        if (to instanceof Decimal d) {
            return Values.toDecimal(bd, d);
        }
        return switch ((Scalar) to) {
            case FLOAT -> {
                float f = bd.floatValue();
                if (Float.isInfinite(f)) {
                    throw new ArithmeticException("Out of range for FLOAT: " + bd);
                }
                yield f;
            }
            case DOUBLE -> {
                double v = bd.doubleValue();
                if (Double.isInfinite(v)) {
                    throw new ArithmeticException("Out of range for DOUBLE: " + bd);
                }
                yield v;
            }
            default -> Values.boxChecked(bd.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact(), to);
        };
    }

    /** Rounds as DuckDB's {@code round} does: halves away from zero. */
    public static double roundHalfAwayFromZero(double d) {
        double a = Math.abs(d);
        double f = Math.floor(a);
        double r = a - f >= 0.5 ? f + 1 : f;
        return Math.copySign(r, d);
    }
}
