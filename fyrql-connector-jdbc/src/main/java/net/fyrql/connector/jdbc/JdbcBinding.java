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
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Binds the Java values of FyrQL types to the parameters of a statement,
 * in the way the columns of the dialect are read, see
 * {@link ColumnReaders}: dates and timestamps as {@code java.time} values
 * for H2, PostgreSQL and MySQL, with the getters of JDBC 4.0 and a
 * calendar in UTC for the generic dialect, and as text for SQLite, which
 * stores them so; a {@code UUID} as itself where the database has the
 * type, else as text.
 */
final class JdbcBinding {

    /** Binds a value, or {@code NULL}, to a parameter. */
    @FunctionalInterface
    interface Setter {
        void set(PreparedStatement statement, int index, Object value) throws SQLException;
    }

    private JdbcBinding() {
    }

    /**
     * Binds a value that is not {@code NULL} with the setters of JDBC 4.0,
     * dates and timestamps with a calendar in UTC, as the generic dialect
     * reads them.
     */
    static void bind(PreparedStatement statement, int index, DataType type, Object v, Calendar utc)
            throws SQLException {
        if (type instanceof DataType.Decimal) {
            statement.setBigDecimal(index, (BigDecimal) v);
            return;
        }
        switch ((Scalar) type) {
            case BOOLEAN -> statement.setBoolean(index, (Boolean) v);
            case TINYINT -> statement.setByte(index, (Byte) v);
            case SMALLINT -> statement.setShort(index, (Short) v);
            case INTEGER -> statement.setInt(index, (Integer) v);
            case BIGINT -> statement.setLong(index, (Long) v);
            case HUGEINT -> statement.setBigDecimal(index, new BigDecimal((BigInteger) v));
            case FLOAT -> statement.setFloat(index, (Float) v);
            case DOUBLE -> statement.setDouble(index, (Double) v);
            case VARCHAR -> statement.setString(index, (String) v);
            case BLOB -> statement.setBytes(index, (byte[]) v);
            case DATE -> statement.setDate(index, ColumnReaders.date((Integer) v), utc);
            case TIMESTAMP -> statement.setTimestamp(index, ColumnReaders.timestamp((Long) v), utc);
            case UUID -> statement.setString(index, v.toString());
        }
    }

    /**
     * Returns the setters of the parameters of a prepared statement, one per
     * column, of a value or {@code NULL}. A {@code NULL} is bound with the
     * type of the parameter as the driver tells it for the generic dialect,
     * since a database such as Derby takes no other; else, or if the driver
     * cannot tell it, with the type of JDBC of the FyrQL type.
     *
     * @param types the types of the columns
     * @param withTimeZone the columns that hold instants, whose timestamps
     *        are bound at the offset UTC
     */
    static Setter[] setters(JdbcDialect dialect, PreparedStatement statement, List<DataType> types,
            BitSet withTimeZone) {
        ParameterMetaData md = null;
        if (dialect == JdbcDialect.GENERIC) {
            try {
                md = statement.getParameterMetaData();
            } catch (SQLException | RuntimeException | AbstractMethodError e) {
                // The types of JDBC of the FyrQL types then.
            }
        }
        Setter[] setters = new Setter[types.size()];
        for (int c = 0; c < setters.length; c++) {
            DataType type = types.get(c);
            int nullType = sqlType(dialect, type);
            if (md != null) {
                try {
                    int told = md.getParameterType(c + 1);
                    nullType = told == Types.NULL ? nullType : told;
                } catch (SQLException | RuntimeException e) {
                    // As above.
                }
            }
            setters[c] = nullable(nullType, of(dialect, type, withTimeZone.get(c)));
        }
        return setters;
    }

    private static Setter nullable(int nullType, Setter value) {
        return (s, i, v) -> {
            if (v == null) {
                s.setNull(i, nullType);
            } else {
                value.set(s, i, v);
            }
        };
    }

    /** The setter of a value that is not {@code NULL}. */
    private static Setter of(JdbcDialect dialect, DataType type, boolean withTimeZone) {
        if (dialect == JdbcDialect.GENERIC) {
            Calendar utc = ColumnReaders.utc();
            return (s, i, v) -> bind(s, i, type, v, utc);
        }
        if (type == DataType.DATE) {
            return dialect == JdbcDialect.SQLITE ? (s, i, v) -> s.setString(i, Conversions.formatDate((Integer) v))
                    : (s, i, v) -> s.setObject(i, LocalDate.ofEpochDay((Integer) v));
        }
        if (type == DataType.TIMESTAMP) {
            if (dialect == JdbcDialect.SQLITE) {
                return (s, i, v) -> s.setString(i, Conversions.formatTimestamp((Long) v));
            }
            return withTimeZone ? (s, i, v) -> s.setObject(i, Conversions.toLocalDateTime((Long) v)
                    .atOffset(ZoneOffset.UTC)) : (s, i, v) -> s.setObject(i, Conversions.toLocalDateTime((Long) v));
        }
        if (type == DataType.UUID && uuidType(dialect)) {
            return (s, i, v) -> s.setObject(i, (UUID) v);
        }
        // The calendar is not used for the other types.
        return (s, i, v) -> bind(s, i, type, v, null);
    }

    /** Tells whether the dialect has a type of its own for a {@code UUID}. */
    private static boolean uuidType(JdbcDialect dialect) {
        return dialect == JdbcDialect.H2 || dialect == JdbcDialect.POSTGRESQL;
    }

    /** The type of JDBC of a FyrQL type, for a {@code NULL}. */
    static int sqlType(JdbcDialect dialect, DataType type) {
        if (type instanceof DataType.Decimal) {
            return Types.DECIMAL;
        }
        return switch ((Scalar) type) {
            case BOOLEAN -> Types.BOOLEAN;
            case TINYINT -> Types.TINYINT;
            case SMALLINT -> Types.SMALLINT;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case HUGEINT -> Types.DECIMAL;
            case FLOAT -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case VARCHAR -> Types.VARCHAR;
            case BLOB -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case UUID -> uuidType(dialect) ? Types.OTHER : Types.VARCHAR;
        };
    }
}
