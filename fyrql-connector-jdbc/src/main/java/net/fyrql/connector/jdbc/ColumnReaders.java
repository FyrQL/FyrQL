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

package net.fyrql.connector.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Reads a column of a result as the Java values of its FyrQL type:
 * through the typed getters of JDBC, and for SQLite, whose values carry
 * their own storage class, through {@code getObject} with a check of
 * that class.
 */
final class ColumnReaders {

    /** Reads the value of a column at the current row; {@code null} for {@code NULL}. */
    @FunctionalInterface
    interface ColumnReader {
        Object read(ResultSet rs, int column) throws SQLException;
    }

    private ColumnReaders() {
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
