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
import net.fyrql.sql.parse.TypeNames;

import java.sql.Types;
import java.util.Locale;

/**
 * The FyrQL type of a column of a database, from what the JDBC metadata
 * says about it. A type FyrQL does not have is read as {@code VARCHAR}, so
 * that every table can be queried:
 * <ul>
 * <li>{@code BOOLEAN}, and {@code BIT} of one bit (PostgreSQL's
 * {@code bool}, MySQL's {@code tinyint(1)} and {@code bit(1)}), are
 * {@code BOOLEAN}; a wider {@code BIT} is a {@code BLOB};</li>
 * <li>the integers keep their width; an unsigned integer of MySQL takes
 * the next wider type, {@code BIGINT UNSIGNED} is {@code HUGEINT};</li>
 * <li>{@code REAL} is {@code FLOAT}, {@code FLOAT} and {@code DOUBLE} are
 * {@code DOUBLE};</li>
 * <li>{@code NUMERIC(p,s)} and {@code DECIMAL(p,s)} are a decimal up to
 * 38 digits; 39 digits without a scale are {@code HUGEINT}, as the dialects
 * write it; PostgreSQL's {@code numeric} without a precision is
 * {@code DOUBLE}, as in DuckDB; more digits are {@code VARCHAR};</li>
 * <li>the character types and large objects are {@code VARCHAR}, the
 * binary ones {@code BLOB};</li>
 * <li>{@code DATE} is {@code DATE}; {@code TIMESTAMP} is
 * {@code TIMESTAMP}, and a timestamp with a time zone, such as
 * PostgreSQL's {@code timestamptz} or MySQL's {@code TIMESTAMP}, is its
 * instant in UTC, see {@link #withTimeZone};</li>
 * <li>a type named {@code uuid} is {@code UUID};</li>
 * <li>everything else, such as {@code TIME}, {@code INTERVAL}, JSON or
 * arrays, is {@code VARCHAR}, read as the driver writes it as a
 * string.</li>
 * </ul>
 * SQLite has no types but the name a column was declared with. The name
 * is read as FyrQL reads a type name, except that {@code INTEGER} and
 * {@code INT} are {@code BIGINT} and {@code REAL} is {@code DOUBLE}, since
 * SQLite stores 64 bits, and that {@code NUMERIC} and {@code DECIMAL}
 * without a precision are {@code DOUBLE}. Any other name goes by the
 * affinity SQLite gives it: with {@code INT} {@code BIGINT}, with
 * {@code CHAR}, {@code CLOB} or {@code TEXT} {@code VARCHAR}, with
 * {@code BLOB} {@code BLOB}, with {@code REAL}, {@code FLOA} or
 * {@code DOUB} {@code DOUBLE}; no name, or any other, is {@code VARCHAR}.
 */
final class JdbcTypes {

    /** The largest precision of a decimal of FyrQL. */
    private static final int MAX_DECIMAL = DataType.MAX_DECIMAL_PRECISION;

    private JdbcTypes() {
    }

    /**
     * Returns the type of a column.
     *
     * @param jdbcType the type of {@link java.sql.Types}
     * @param typeName the name of the type in the database
     * @param size the precision of a number, the length of a string, or 0 if unknown
     * @param digits the scale of a number, or 0 if unknown
     */
    static DataType type(JdbcDialect dialect, int jdbcType, String typeName, int size, int digits) {
        String name = typeName == null ? "" : typeName.strip().toUpperCase(Locale.ROOT);
        if (dialect == JdbcDialect.SQLITE) {
            return sqlite(name);
        }
        if (name.equals("UUID")) {
            return DataType.UUID;
        }
        if (name.contains("UNSIGNED")) {
            String base = name.replace("UNSIGNED", "").replace("ZEROFILL", "").strip();
            switch (base) {
                case "TINYINT" -> {
                    return DataType.SMALLINT;
                }
                case "SMALLINT" -> {
                    return DataType.INTEGER;
                }
                case "MEDIUMINT", "INT", "INTEGER" -> {
                    return DataType.BIGINT;
                }
                case "BIGINT" -> {
                    return DataType.HUGEINT;
                }
                default -> {
                    // A decimal or floating-point type is the same with or without a sign.
                }
            }
        }
        return switch (jdbcType) {
            case Types.BOOLEAN -> DataType.BOOLEAN;
            case Types.BIT -> size <= 1 ? DataType.BOOLEAN : DataType.BLOB;
            case Types.TINYINT -> DataType.TINYINT;
            case Types.SMALLINT -> DataType.SMALLINT;
            case Types.INTEGER -> DataType.INTEGER;
            case Types.BIGINT -> DataType.BIGINT;
            case Types.REAL -> DataType.FLOAT;
            case Types.FLOAT, Types.DOUBLE -> DataType.DOUBLE;
            case Types.NUMERIC, Types.DECIMAL -> decimal(dialect, size, digits);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB -> DataType.VARCHAR;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> DataType.BLOB;
            case Types.DATE -> DataType.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> DataType.TIMESTAMP;
            default -> DataType.VARCHAR;
        };
    }

    private static DataType decimal(JdbcDialect dialect, int precision, int scale) {
        if (precision <= 0 || dialect == JdbcDialect.POSTGRESQL && precision >= 1000) {
            // A numeric without a precision has as many digits as its values; DuckDB reads it as a double too.
            return DataType.DOUBLE;
        }
        if (scale < 0 || scale > precision) {
            return DataType.VARCHAR;
        }
        if (precision <= MAX_DECIMAL) {
            return DataType.decimal(precision, scale);
        }
        return precision == MAX_DECIMAL + 1 && scale == 0 ? DataType.HUGEINT : DataType.VARCHAR;
    }

    /**
     * Tells whether a column holds instants, which the database shows and
     * compares in the time zone of its session: a timestamp with a time
     * zone, and MySQL's {@code TIMESTAMP}. FyrQL reads such a value as its
     * instant in UTC, and the database evaluates no filter on it, since it
     * would read a literal in the time zone of the session.
     */
    static boolean withTimeZone(JdbcDialect dialect, int jdbcType, String typeName) {
        String name = typeName == null ? "" : typeName.strip().toUpperCase(Locale.ROOT);
        return jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || name.equals("TIMESTAMPTZ")
                || name.contains("WITH TIME ZONE") || dialect == JdbcDialect.MYSQL && name.equals("TIMESTAMP");
    }

    /** The type of a column of SQLite by the name it was declared with. */
    static DataType sqlite(String declared) {
        String name = declared.strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return DataType.VARCHAR;
        }
        switch (name) {
            case "INTEGER", "INT" -> {
                return DataType.BIGINT;
            }
            case "REAL" -> {
                return DataType.DOUBLE;
            }
            case "NUMERIC", "DECIMAL" -> {
                return DataType.DOUBLE;
            }
            default -> {
                // Below.
            }
        }
        try {
            return TypeNames.parse(name);
        } catch (IllegalArgumentException e) {
            // Not a name of FyrQL: by the affinity SQLite gives the name.
        }
        if (name.contains("INT")) {
            return DataType.BIGINT;
        }
        if (name.contains("CHAR") || name.contains("CLOB") || name.contains("TEXT")) {
            return DataType.VARCHAR;
        }
        if (name.contains("BLOB")) {
            return DataType.BLOB;
        }
        if (name.contains("REAL") || name.contains("FLOA") || name.contains("DOUB")) {
            return DataType.DOUBLE;
        }
        return DataType.VARCHAR;
    }
}
