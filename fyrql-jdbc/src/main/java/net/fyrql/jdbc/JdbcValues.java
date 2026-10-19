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

package net.fyrql.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts the values of the engine, see {@link DataType}, to the values the
 * getters of a result set return. A getter takes the values JDBC lets it
 * convert: a number from any number, from a boolean and from text that
 * spells one, cutting off a fraction; a boolean from a number; a date or a
 * timestamp from either and from text; text from every type, as
 * {@link Casts#format} writes it. A {@code LIST} is a {@link FyrQLArray},
 * a {@code STRUCT} a {@link FyrQLStruct} and a {@code MAP} a {@code Map},
 * with the values inside converted the same way; {@code getObject} with
 * the class {@code List} or {@code Map} reads a list or a struct as a list
 * and a map or a struct as a map. A value that does not fit the getter is
 * {@code 22003}, one that cannot be converted {@code 22018}. Dates and
 * timestamps without a calendar are in the time zone of the JVM, as JDBC
 * wants it.
 * <p>
 * The other way, {@link #fromJava} and {@link #convert} turn the values of
 * the parameters of a prepared statement into values of the engine.
 */
final class JdbcValues {

    /** The type of a {@code DECIMAL} parameter without a value of its own: the {@code DECIMAL} of DuckDB. */
    static final DataType DEFAULT_DECIMAL = DataType.decimal(18, 3);

    private static final double LONG_MIN = -0x1p63;
    private static final double LONG_END = 0x1p63;

    private JdbcValues() {
    }

    /** Returns the value {@code getObject} returns, of the class {@link JdbcTypes#javaClass}. */
    static Object toObject(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        if (type == DataType.DATE) {
            return Date.valueOf(LocalDate.ofEpochDay((Integer) value));
        }
        if (type == DataType.TIMESTAMP) {
            return Timestamp.valueOf(Conversions.toLocalDateTime((Long) value));
        }
        if (type == DataType.HUGEINT) {
            return new BigDecimal((BigInteger) value);
        }
        if (type == DataType.BLOB) {
            return ((byte[]) value).clone();
        }
        if (type instanceof DataType.ListType l) {
            return new FyrQLArray(l, (List<?>) value);
        }
        if (type instanceof DataType.Struct s) {
            return new FyrQLStruct(s, (List<?>) value);
        }
        if (type instanceof DataType.MapType m) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                result.put(toObject(e.getKey(), m.key()), toObject(e.getValue(), m.value()));
            }
            return Collections.unmodifiableMap(result);
        }
        return value;
    }

    /**
     * Reads a value as a list: the elements of a {@code LIST} or the
     * attributes of a {@code STRUCT}, each as {@link #toObject} returns it.
     */
    static List<Object> toList(Object value, DataType type) throws SQLException {
        Object[] elements;
        if (type instanceof DataType.ListType l) {
            elements = (Object[]) new FyrQLArray(l, (List<?>) value).getArray();
        } else if (type instanceof DataType.Struct s) {
            elements = new FyrQLStruct(s, (List<?>) value).getAttributes();
        } else {
            throw cannotConvert(value, type, "a list", null);
        }
        return Collections.unmodifiableList(Arrays.asList(elements));
    }

    /**
     * Reads a value as a map: the entries of a {@code MAP}, or the fields of a
     * {@code STRUCT} by name, each value as {@link #toObject} returns it.
     */
    static Map<Object, Object> toMap(Object value, DataType type) throws SQLException {
        if (type instanceof DataType.MapType) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) toObject(value, type);
            return map;
        }
        if (type instanceof DataType.Struct s) {
            Map<Object, Object> result = new LinkedHashMap<>();
            List<?> values = (List<?>) value;
            for (int i = 0; i < values.size(); i++) {
                DataType.Struct.Field f = s.fields().get(i);
                result.put(f.name(), toObject(values.get(i), f.type()));
            }
            return Collections.unmodifiableMap(result);
        }
        throw cannotConvert(value, type, "a map", null);
    }

    /** Reads a value as a {@code java.sql.Array}: only a {@code LIST} is one. */
    static Array toArray(Object value, DataType type) throws SQLException {
        if (type instanceof DataType.ListType l) {
            return new FyrQLArray(l, (List<?>) value);
        }
        throw cannotConvert(value, type, "an array", null);
    }

    /** Returns the text of a value, as {@link Casts#format} writes it. */
    static String toText(Object value, DataType type) {
        return Casts.format(value, type);
    }

    /** Reads a value as a boolean: a number is true unless it is zero. */
    static boolean toBoolean(Object value, DataType type) throws SQLException {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            try {
                return Conversions.parseBoolean(s);
            } catch (RuntimeException e) {
                throw cannotConvert(value, type, "a boolean", e);
            }
        }
        if (type.isNumeric()) {
            return value instanceof Double || value instanceof Float ? ((Number) value).doubleValue() != 0
                    : toBigDecimal(value, type).signum() != 0;
        }
        throw cannotConvert(value, type, "a boolean", null);
    }

    /**
     * Reads a value as an integral number between {@code min} and
     * {@code max}; a fraction is cut off.
     *
     * @param target the Java type of the getter for the message, e.g. {@code an int}
     */
    static long toLong(Object value, DataType type, long min, long max, String target) throws SQLException {
        checkNumber(value, type, target);
        long x;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            x = ((Number) value).longValue();
        } else if (value instanceof Boolean b) {
            x = b ? 1 : 0;
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!(d >= LONG_MIN && d < LONG_END)) {
                throw outOfRange(value, type, target);
            }
            x = (long) d;
        } else {
            BigInteger b = toBigInteger(value, type, target);
            if (b.bitLength() > 63) {
                throw outOfRange(value, type, target);
            }
            x = b.longValue();
        }
        if (x < min || x > max) {
            throw outOfRange(value, type, target);
        }
        return x;
    }

    /** Reads a value as a {@code BigInteger}; a fraction is cut off. */
    static BigInteger toBigInteger(Object value, DataType type, String target) throws SQLException {
        checkNumber(value, type, target);
        if (value instanceof BigInteger b) {
            return b;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return toBigDecimal(value, type, target).setScale(0, RoundingMode.DOWN).toBigIntegerExact();
    }

    /** Reads a value as a {@code BigDecimal}. */
    static BigDecimal toBigDecimal(Object value, DataType type) throws SQLException {
        return toBigDecimal(value, type, "a BigDecimal");
    }

    private static BigDecimal toBigDecimal(Object value, DataType type, String target) throws SQLException {
        checkNumber(value, type, target);
        return switch (value) {
            case BigDecimal d -> d;
            case BigInteger b -> new BigDecimal(b);
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case Boolean b -> b ? BigDecimal.ONE : BigDecimal.ZERO;
            case Float f -> {
                if (f.isNaN() || f.isInfinite()) {
                    throw outOfRange(value, type, target);
                }
                yield new BigDecimal(Float.toString(f));
            }
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    throw outOfRange(value, type, target);
                }
                yield BigDecimal.valueOf(d);
            }
            case String s -> {
                try {
                    yield new BigDecimal(s.strip());
                } catch (NumberFormatException e) {
                    throw cannotConvert(value, type, target, e);
                }
            }
            default -> throw cannotConvert(value, type, target, null);
        };
    }

    /** Reads a value as a {@code double}. */
    static double toDouble(Object value, DataType type) throws SQLException {
        checkNumber(value, type, "a double");
        return switch (value) {
            case Number n -> n.doubleValue();
            case Boolean b -> b ? 1 : 0;
            case String s -> {
                try {
                    yield Conversions.parseDouble(s);
                } catch (RuntimeException e) {
                    throw cannotConvert(value, type, "a double", e);
                }
            }
            default -> throw cannotConvert(value, type, "a double", null);
        };
    }

    /** Accepts a number, a boolean or text for a numeric getter; a date is not a number, though it is stored as one. */
    private static void checkNumber(Object value, DataType type, String target) throws SQLException {
        if (!type.isNumeric() && type != DataType.BOOLEAN && type != DataType.VARCHAR) {
            throw cannotConvert(value, type, target, null);
        }
    }

    /** Reads a value as a {@code float}. */
    static float toFloat(Object value, DataType type) throws SQLException {
        double d = toDouble(value, type);
        if (Double.isFinite(d) && Math.abs(d) > Float.MAX_VALUE) {
            throw outOfRange(value, type, "a float");
        }
        return (float) d;
    }

    /** Reads a value as bytes: a {@code BLOB}, or text as UTF-8. */
    static byte[] toBytes(Object value, DataType type) throws SQLException {
        if (value instanceof byte[] b) {
            return b.clone();
        }
        if (value instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        throw cannotConvert(value, type, "bytes", null);
    }

    /** Reads a value as a local date: a {@code DATE}, the day of a {@code TIMESTAMP}, or text. */
    static LocalDate toLocalDate(Object value, DataType type) throws SQLException {
        if (type == DataType.DATE) {
            return LocalDate.ofEpochDay((Integer) value);
        }
        if (type == DataType.TIMESTAMP) {
            return Conversions.toLocalDateTime((Long) value).toLocalDate();
        }
        if (value instanceof String s) {
            try {
                return LocalDate.ofEpochDay(Conversions.parseDate(s));
            } catch (RuntimeException e) {
                throw cannotConvert(value, type, "a date", e);
            }
        }
        throw cannotConvert(value, type, "a date", null);
    }

    /** Reads a value as a local date and time: a {@code TIMESTAMP}, the start of a {@code DATE}, or text. */
    static LocalDateTime toLocalDateTime(Object value, DataType type) throws SQLException {
        if (type == DataType.TIMESTAMP) {
            return Conversions.toLocalDateTime((Long) value);
        }
        if (type == DataType.DATE) {
            return LocalDate.ofEpochDay((Integer) value).atStartOfDay();
        }
        if (value instanceof String s) {
            try {
                return Conversions.toLocalDateTime(Conversions.parseTimestamp(s));
            } catch (RuntimeException e) {
                throw cannotConvert(value, type, "a timestamp", e);
            }
        }
        throw cannotConvert(value, type, "a timestamp", null);
    }

    /** Reads a value as a local time: the time of a {@code TIMESTAMP}, or text such as {@code 12:30:00}. */
    static LocalTime toLocalTime(Object value, DataType type) throws SQLException {
        if (type == DataType.TIMESTAMP) {
            return Conversions.toLocalDateTime((Long) value).toLocalTime();
        }
        if (value instanceof String s) {
            try {
                return LocalTime.parse(s.strip());
            } catch (RuntimeException e) {
                throw cannotConvert(value, type, "a time", e);
            }
        }
        throw cannotConvert(value, type, "a time", null);
    }

    /** Reads a value as a {@code java.sql.Date}, at the start of its day in the zone of the calendar, if any. */
    static Date toDate(Object value, DataType type, Calendar calendar) throws SQLException {
        LocalDate d = toLocalDate(value, type);
        return calendar == null ? Date.valueOf(d)
                : new Date(d.atStartOfDay(zone(calendar)).toInstant().toEpochMilli());
    }

    /** Reads a value as a {@code java.sql.Time} on 1970-01-01, in the zone of the calendar, if any. */
    static Time toTime(Object value, DataType type, Calendar calendar) throws SQLException {
        LocalTime t = toLocalTime(value, type);
        if (calendar == null) {
            Time time = Time.valueOf(t);
            time.setTime(time.getTime() + t.getNano() / 1_000_000);
            return time;
        }
        return new Time(LocalDate.EPOCH.atTime(t).atZone(zone(calendar)).toInstant().toEpochMilli());
    }

    /** Reads a value as a {@code java.sql.Timestamp}, in the zone of the calendar, if any. */
    static Timestamp toTimestamp(Object value, DataType type, Calendar calendar) throws SQLException {
        LocalDateTime t = toLocalDateTime(value, type);
        return calendar == null ? Timestamp.valueOf(t) : Timestamp.from(t.atZone(zone(calendar)).toInstant());
    }

    private static ZoneId zone(Calendar calendar) {
        return calendar.getTimeZone().toZoneId();
    }

    /**
     * Reads a value as an object of a class, for {@code getObject(column, type)}.
     *
     * @throws SQLException {@code 0A000} for a class the driver does not
     *         convert to, {@code 22018} or {@code 22003} for a value that
     *         does not convert
     */
    static <T> T toClass(Object value, DataType type, Class<T> c) throws SQLException {
        if (c == null) {
            throw SqlStates.invalidArgument("The type of getObject must not be null");
        }
        if (value == null) {
            return null;
        }
        Object result;
        if (c == String.class) {
            result = toText(value, type);
        } else if (c == Boolean.class) {
            result = toBoolean(value, type);
        } else if (c == Byte.class) {
            result = (byte) toLong(value, type, Byte.MIN_VALUE, Byte.MAX_VALUE, "a byte");
        } else if (c == Short.class) {
            result = (short) toLong(value, type, Short.MIN_VALUE, Short.MAX_VALUE, "a short");
        } else if (c == Integer.class) {
            result = (int) toLong(value, type, Integer.MIN_VALUE, Integer.MAX_VALUE, "an int");
        } else if (c == Long.class) {
            result = toLong(value, type, Long.MIN_VALUE, Long.MAX_VALUE, "a long");
        } else if (c == Float.class) {
            result = toFloat(value, type);
        } else if (c == Double.class) {
            result = toDouble(value, type);
        } else if (c == BigDecimal.class) {
            result = toBigDecimal(value, type);
        } else if (c == BigInteger.class) {
            result = toBigInteger(value, type, "a BigInteger");
        } else if (c == byte[].class) {
            result = toBytes(value, type);
        } else if (c == LocalDate.class) {
            result = toLocalDate(value, type);
        } else if (c == LocalDateTime.class) {
            result = toLocalDateTime(value, type);
        } else if (c == LocalTime.class) {
            result = toLocalTime(value, type);
        } else if (c == Date.class) {
            result = toDate(value, type, null);
        } else if (c == Time.class) {
            result = toTime(value, type, null);
        } else if (c == Timestamp.class || c == java.util.Date.class) {
            result = toTimestamp(value, type, null);
        } else if (c == UUID.class) {
            result = toUuid(value, type);
        } else if (c == List.class) {
            result = toList(value, type);
        } else if (c == Map.class) {
            result = toMap(value, type);
        } else if (c == Array.class) {
            result = toArray(value, type);
        } else {
            result = toObject(value, type);
            if (!c.isInstance(result)) {
                throw SqlStates.create("FyrQL does not read a value of type " + type.sqlName() + " as "
                        + c.getName(), SqlStates.FEATURE_NOT_SUPPORTED, null);
            }
        }
        return c.cast(result);
    }

    private static UUID toUuid(Object value, DataType type) throws SQLException {
        if (value instanceof UUID u) {
            return u;
        }
        if (value instanceof String s) {
            try {
                return Conversions.parseUuid(s);
            } catch (RuntimeException e) {
                throw cannotConvert(value, type, "a UUID", e);
            }
        }
        throw cannotConvert(value, type, "a UUID", null);
    }

    /** A value as the engine holds it, with its type: the value of a parameter. */
    record Typed(Object value, DataType type) {
    }

    /**
     * Converts a value of a parameter to a value of the engine, of the type
     * that belongs to its class: the classes {@code getObject} returns, the
     * other boxed numbers and {@code BigInteger}, {@code Character},
     * {@code LocalDate} and {@code LocalDateTime}, and a
     * {@code java.util.Date} as a timestamp in the time zone of the JVM. A
     * {@code BigDecimal} is a {@code DECIMAL} of its digits and scale, see
     * {@link #decimal}.
     *
     * @throws SQLException {@code 0A000} for a class the driver does not
     *         take, such as {@code java.sql.Time}; {@code 22003} for a value
     *         beyond the range of its type
     */
    static Typed fromJava(Object x) throws SQLException {
        return switch (x) {
            case Boolean b -> new Typed(b, DataType.BOOLEAN);
            case Byte b -> new Typed(b, DataType.TINYINT);
            case Short s -> new Typed(s, DataType.SMALLINT);
            case Integer i -> new Typed(i, DataType.INTEGER);
            case Long l -> new Typed(l, DataType.BIGINT);
            case BigInteger b -> {
                if (b.bitLength() > 127) {
                    throw tooLarge(b.toString(), "a HUGEINT");
                }
                yield new Typed(b, DataType.HUGEINT);
            }
            case Float f -> new Typed(f, DataType.FLOAT);
            case Double d -> new Typed(d, DataType.DOUBLE);
            case BigDecimal d -> decimal(d);
            case String s -> new Typed(s, DataType.VARCHAR);
            case Character c -> new Typed(c.toString(), DataType.VARCHAR);
            case byte[] b -> new Typed(b.clone(), DataType.BLOB);
            case Date d -> fromLocalDate(d.toLocalDate());
            case Time t -> throw noTime();
            case Timestamp t -> fromLocalDateTime(t.toLocalDateTime());
            case java.util.Date d -> fromLocalDateTime(LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
            case LocalDate d -> fromLocalDate(d);
            case LocalDateTime t -> fromLocalDateTime(t);
            case LocalTime t -> throw noTime();
            case UUID u -> new Typed(u, DataType.UUID);
            case FyrQLArray a -> new Typed(a.values(), a.type());
            case FyrQLStruct s -> new Typed(s.values(), s.type());
            default -> throw SqlStates.create("FyrQL does not take a parameter of class " + x.getClass().getName(),
                    SqlStates.FEATURE_NOT_SUPPORTED, null);
        };
    }

    /** Returns a {@code DATE} of a local date. */
    static Typed fromLocalDate(LocalDate d) throws SQLException {
        long days = d.toEpochDay();
        if (days != (int) days) {
            throw tooLarge(d.toString(), "a DATE");
        }
        return new Typed((int) days, DataType.DATE);
    }

    /** Returns a {@code TIMESTAMP} of a local date and time; a fraction below a microsecond is cut off. */
    static Typed fromLocalDateTime(LocalDateTime t) throws SQLException {
        try {
            return new Typed(Conversions.toMicros(t), DataType.TIMESTAMP);
        } catch (ArithmeticException e) {
            throw tooLarge(t.toString(), "a TIMESTAMP");
        }
    }

    /** A value of a parameter that does not fit its type: {@code 22003}. */
    private static SQLException tooLarge(String value, String target) {
        return SqlStates.create("The value " + value + " does not fit " + target, SqlStates.NUMERIC_OUT_OF_RANGE, null);
    }

    /** A time of day: {@code 0A000}, FyrQL has no {@code TIME}. */
    static SQLException noTime() {
        return SqlStates.create("FyrQL has no type TIME; set a timestamp or a string",
                SqlStates.FEATURE_NOT_SUPPORTED, null);
    }

    /**
     * Returns a {@code DECIMAL} of a {@code BigDecimal}: a negative scale
     * becomes zero, the precision is that of the digits but at least one
     * more than the scale. A value of more than 38 digits loses its
     * trailing zeros after the point; if it still has more, it is
     * {@code 22003}.
     */
    static Typed decimal(BigDecimal d) throws SQLException {
        BigDecimal v = d.scale() < 0 ? d.setScale(0) : d;
        if (digits(v) > DataType.MAX_DECIMAL_PRECISION) {
            v = v.stripTrailingZeros();
            v = v.scale() < 0 ? v.setScale(0) : v;
        }
        if (digits(v) > DataType.MAX_DECIMAL_PRECISION) {
            throw tooLarge(d.toString(), "a DECIMAL of at most 38 digits");
        }
        return new Typed(v, DataType.decimal(digits(v), v.scale()));
    }

    private static int digits(BigDecimal d) {
        return Math.max(d.precision(), d.scale() + 1);
    }

    /**
     * Returns the type of a code of {@link Types}, for {@code setNull} and
     * {@code setObject} with a target type: the reverse of
     * {@link JdbcTypes#sqlType}, but {@code FLOAT} is a {@code DOUBLE} as
     * in JDBC, and {@code NUMERIC} and {@code DECIMAL} are a
     * {@code DECIMAL(18,3)}, the {@code DECIMAL} of DuckDB. It is
     * {@code null} for {@code NULL}, {@code OTHER}, {@code JAVA_OBJECT},
     * {@code ARRAY} and {@code STRUCT}: the type of the value then stays.
     *
     * @throws SQLException {@code 0A000} for a code FyrQL has no type for, such as {@code TIME}
     */
    static DataType typeOf(int sqlType) throws SQLException {
        return switch (sqlType) {
            case Types.BOOLEAN, Types.BIT -> DataType.BOOLEAN;
            case Types.TINYINT -> DataType.TINYINT;
            case Types.SMALLINT -> DataType.SMALLINT;
            case Types.INTEGER -> DataType.INTEGER;
            case Types.BIGINT -> DataType.BIGINT;
            case Types.NUMERIC, Types.DECIMAL -> DEFAULT_DECIMAL;
            case Types.REAL -> DataType.FLOAT;
            case Types.FLOAT, Types.DOUBLE -> DataType.DOUBLE;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB -> DataType.VARCHAR;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> DataType.BLOB;
            case Types.DATE -> DataType.DATE;
            case Types.TIMESTAMP -> DataType.TIMESTAMP;
            case Types.NULL, Types.OTHER, Types.JAVA_OBJECT, Types.ARRAY, Types.STRUCT -> null;
            default -> throw SqlStates.create("FyrQL has no type for the JDBC type " + typeName(sqlType),
                    SqlStates.FEATURE_NOT_SUPPORTED, null);
        };
    }

    private static String typeName(int sqlType) {
        try {
            return JDBCType.valueOf(sqlType).getName();
        } catch (IllegalArgumentException e) {
            return String.valueOf(sqlType);
        }
    }

    /**
     * Converts the value of a parameter to the type of a code of
     * {@link Types}, as a cast of FyrQL converts it. A {@code DECIMAL} has
     * the scale {@code scale}, rounded half up, or else the scale of the
     * value; a code without a type of its own keeps the value.
     *
     * @param scale the scale of a {@code DECIMAL}, or {@code -1}
     * @throws SQLException {@code 22018} if the value does not convert,
     *         {@code 22003} if it is beyond the range of the type
     */
    static Typed convert(Typed v, int sqlType, int scale) throws SQLException {
        DataType target = typeOf(sqlType);
        if (target == null || target.equals(v.type())) {
            return v;
        }
        if (target instanceof DataType.Decimal) {
            BigDecimal d = toBigDecimal(v.value(), v.type(), "a DECIMAL");
            return decimal(scale >= 0 ? d.setScale(scale, RoundingMode.HALF_UP) : d);
        }
        try {
            return new Typed(Casts.cast(v.value(), v.type(), target), target);
        } catch (ArithmeticException e) {
            throw outOfRange(v.value(), v.type(), "the type " + target.sqlName());
        } catch (ConversionException e) {
            throw cannotConvert(v.value(), v.type(), "the type " + target.sqlName(), e);
        }
    }

    /** The value does not fit the Java type of the getter: {@code 22003}. */
    static SQLException outOfRange(Object value, DataType type, String target) {
        return SqlStates.create("The value " + describe(value, type) + " of type " + type.sqlName()
                + " does not fit " + target, SqlStates.NUMERIC_OUT_OF_RANGE, null);
    }

    /** The value cannot be converted to the Java type of the getter: {@code 22018}. */
    static SQLException cannotConvert(Object value, DataType type, String target, Throwable cause) {
        return SqlStates.create("The value " + describe(value, type) + " of type " + type.sqlName()
                + " cannot be read as " + target, SqlStates.INVALID_CAST, cause);
    }

    /** Returns the text of a value for a message, shortened to 40 characters. */
    private static String describe(Object value, DataType type) {
        String s = toText(value, type);
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
