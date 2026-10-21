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

package net.fyrql.connector.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Reads a column of a result as the Java values of its FyrQL type:
 * through the typed getters of JDBC, and for SQLite, whose values carry
 * their own storage class, through {@code getObject} with a check of
 * that class. For the {@linkplain JdbcDialect#GENERIC generic dialect},
 * dates and timestamps are read with the getters of JDBC 4.0 and a
 * calendar in UTC, since not every driver reads {@code java.time} values
 * and the default time zone would shift a time in a gap of daylight
 * saving time.
 */
final class ColumnReaders {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /** Reads the value of a column at the current row; {@code null} for {@code NULL}. */
    @FunctionalInterface
    interface ColumnReader {
        Object read(ResultSet rs, int column) throws SQLException;
    }

    private ColumnReaders() {
    }

    /**
     * Returns a new calendar of the proleptic Gregorian calendar in UTC, as
     * FyrQL counts days, for the getters and setters of JDBC 4.0; a
     * calendar is not shared between threads.
     */
    static Calendar utc() {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setGregorianChange(new Date(Long.MIN_VALUE));
        return calendar;
    }

    /** The {@code java.sql.Date} of a day of FyrQL, at midnight in UTC. */
    static java.sql.Date date(int days) {
        return new java.sql.Date(days * MILLIS_PER_DAY);
    }

    /** The {@code Timestamp} of microseconds since the epoch of FyrQL, in UTC. */
    static Timestamp timestamp(long micros) {
        Timestamp t = new Timestamp(Math.floorDiv(micros, 1_000_000L) * 1000);
        t.setNanos((int) Math.floorMod(micros, 1_000_000L) * 1000);
        return t;
    }

    /** The microseconds since the epoch of a {@code Timestamp} read in UTC. */
    static long micros(Timestamp t) {
        return Math.floorDiv(t.getTime(), 1000L) * 1_000_000L + t.getNanos() / 1000;
    }

    /**
     * Returns the reader of a column.
     *
     * @param jdbcType the type of the column in the result, as {@link java.sql.Types}
     * @param typeName the name of the type of the column in the result
     */
    static ColumnReader of(JdbcDialect dialect, DataType type, int jdbcType, String typeName) {
        if (dialect == JdbcDialect.SQLITE) {
            return sqlite(type);
        }
        if (dialect == JdbcDialect.GENERIC && (type == DataType.DATE || type == DataType.TIMESTAMP
                || type == DataType.TIMESTAMPTZ || type == DataType.TIME)) {
            Calendar utc = utc();
            if (type == DataType.TIME) {
                return (rs, c) -> {
                    java.sql.Time v = rs.getTime(c, utc);
                    return v == null ? null : Math.floorMod(v.getTime(), MILLIS_PER_DAY) * 1000L;
                };
            }
            return type == DataType.DATE ? (rs, c) -> {
                java.sql.Date v = rs.getDate(c, utc);
                return v == null ? null : Math.toIntExact(Math.floorDiv(v.getTime(), MILLIS_PER_DAY));
            } : (rs, c) -> {
                Timestamp v = rs.getTimestamp(c, utc);
                return v == null ? null : micros(v);
            };
        }
        if (type instanceof Decimal d) {
            return (rs, c) -> {
                BigDecimal v = rs.getBigDecimal(c);
                return v == null ? null : v.setScale(d.scale(), RoundingMode.UNNECESSARY);
            };
        }
        if (!(type instanceof Scalar scalar)) {
            throw new IllegalArgumentException("A JDBC source has no columns of type " + type.sqlName());
        }
        return switch (scalar) {
            case BOOLEAN -> (rs, c) -> {
                boolean v = rs.getBoolean(c);
                return rs.wasNull() ? null : v;
            };
            case TINYINT -> (rs, c) -> {
                byte v = rs.getByte(c);
                return rs.wasNull() ? null : v;
            };
            case SMALLINT -> (rs, c) -> {
                short v = rs.getShort(c);
                return rs.wasNull() ? null : v;
            };
            case INTEGER -> (rs, c) -> {
                int v = rs.getInt(c);
                return rs.wasNull() ? null : v;
            };
            case BIGINT -> (rs, c) -> {
                long v = rs.getLong(c);
                return rs.wasNull() ? null : v;
            };
            case HUGEINT -> (rs, c) -> {
                BigDecimal v = rs.getBigDecimal(c);
                return v == null ? null : v.toBigIntegerExact();
            };
            case FLOAT -> (rs, c) -> {
                float v = rs.getFloat(c);
                return rs.wasNull() ? null : v;
            };
            case DOUBLE -> (rs, c) -> {
                double v = rs.getDouble(c);
                return rs.wasNull() ? null : v;
            };
            case VARCHAR -> ResultSet::getString;
            case BLOB -> ResultSet::getBytes;
            case DATE -> (rs, c) -> {
                LocalDate v = rs.getObject(c, LocalDate.class);
                return v == null ? null : Math.toIntExact(v.toEpochDay());
            };
            case TIMESTAMP -> JdbcTypes.withTimeZone(dialect, jdbcType, typeName) ? (rs, c) -> {
                OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
                return v == null ? null
                        : Conversions.toMicros(v.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
            } : (rs, c) -> {
                LocalDateTime v = rs.getObject(c, LocalDateTime.class);
                return v == null ? null : Conversions.toMicros(v);
            };
            case UUID -> (rs, c) -> uuid(rs.getObject(c));
            case TIME -> (rs, c) -> {
                LocalTime v = rs.getObject(c, LocalTime.class);
                // PostgreSQL's driver gives 24:00:00 as the last nanosecond of the day, never a value of its own.
                return v == null ? null : v.equals(LocalTime.MAX) ? 86_400_000_000L : v.toNanoOfDay() / 1000;
            };
            case TIMESTAMPTZ -> (rs, c) -> {
                OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
                return v == null ? null
                        : Conversions.toMicros(v.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
            };
            // The text of PostgreSQL in its default style, with + before a part of another sign than the first.
            case INTERVAL -> (rs, c) -> {
                String v = rs.getString(c);
                return v == null ? null : Interval.parse(v.replace("+", ""));
            };
        };
    }

    private static UUID uuid(Object v) {
        return switch (v) {
            case null -> null;
            case UUID u -> u;
            case String s -> Conversions.parseUuid(s);
            case byte[] b when b.length == 16 -> {
                ByteBuffer buffer = ByteBuffer.wrap(b);
                yield new UUID(buffer.getLong(), buffer.getLong());
            }
            default -> throw new IllegalArgumentException("not a UUID: " + v);
        };
    }

    /**
     * A reader of SQLite: the value as SQLite stores it, an integer, a
     * double, a string or bytes, converted to the type of the column where
     * that is exact; a value of another storage class is an error.
     */
    private static ColumnReader sqlite(DataType type) {
        return (rs, c) -> {
            Object v = rs.getObject(c);
            return v == null ? null : sqliteValue(v, type);
        };
    }

    static Object sqliteValue(Object v, DataType type) {
        if (type instanceof Decimal d) {
            BigDecimal value = switch (v) {
                case Integer i -> BigDecimal.valueOf(i);
                case Long l -> BigDecimal.valueOf(l);
                // A decimal is stored as a double; its shortest digits are the decimal written.
                case Double x -> new BigDecimal(Double.toString(x));
                case String s -> Conversions.parseDecimal(s);
                default -> throw notA(v, type);
            };
            return value.setScale(d.scale(), RoundingMode.HALF_UP);
        }
        Scalar scalar = (Scalar) type;
        return switch (scalar) {
            case BOOLEAN -> integral(v, type).signum() != 0;
            case TINYINT -> integral(v, type).byteValueExact();
            case SMALLINT -> integral(v, type).shortValueExact();
            case INTEGER -> integral(v, type).intValueExact();
            case BIGINT -> integral(v, type).longValueExact();
            case HUGEINT -> integral(v, type);
            case FLOAT -> number(v, type).floatValue();
            case DOUBLE -> number(v, type).doubleValue();
            case VARCHAR -> v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString();
            case BLOB -> v instanceof byte[] b ? b : v.toString().getBytes(StandardCharsets.UTF_8);
            case DATE -> Conversions.parseDate(text(v, type));
            case TIMESTAMP -> Conversions.parseTimestamp(text(v, type));
            case UUID -> uuid(v instanceof byte[] ? v : text(v, type));
            case TIME -> Conversions.parseTime(text(v, type));
            // An instant is stored as its timestamp in UTC.
            case TIMESTAMPTZ -> Conversions.parseTimestamp(text(v, type));
            case INTERVAL -> Interval.parse(text(v, type));
        };
    }

    private static BigInteger integral(Object v, DataType type) {
        return switch (v) {
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            default -> throw notA(v, type);
        };
    }

    private static Number number(Object v, DataType type) {
        if (v instanceof Integer || v instanceof Long || v instanceof Double) {
            return (Number) v;
        }
        throw notA(v, type);
    }

    private static String text(Object v, DataType type) {
        if (v instanceof String s) {
            return s;
        }
        throw notA(v, type);
    }

    private static IllegalArgumentException notA(Object v, DataType type) {
        String shown = v instanceof byte[] b ? Conversions.formatBlob(b) : String.valueOf(v);
        return new IllegalArgumentException("SQLite stores " + shown + ", which is not of the type " + type.sqlName());
    }
}
