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
import net.fyrql.jdbc.JdbcValues.Typed;
import net.fyrql.sql.parse.Parameters;
import net.fyrql.sql.parse.TypeNames;
import net.fyrql.sql.render.DuckDbDialect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * A prepared statement: a query text with {@code ?} for its parameters, see
 * {@link Parameters}. A setter turns its value into a literal of
 * FyrQL with an explicit type, such as {@code CAST(42 AS INTEGER)} or
 * {@code DATE '2024-02-29'}, and a run puts the literals in place of the
 * parameters, so that the engine plans the text anew each time. The values
 * stay set from run to run until {@link #clearParameters()}.
 * <p>
 * {@code setObject} takes the classes {@link JdbcValues#fromJava} takes;
 * with a target type it converts the value as a cast of FyrQL does.
 * {@code setNull} with a type name of FyrQL, such as {@code INTEGER[]},
 * makes a {@code NULL} of that type. FyrQL has no {@code TIME}:
 * {@code setTime} is {@code 0A000}. A stream or reader is read whole when
 * it is set. {@link #getParameterMetaData()} tells the types the engine
 * gives the parameters, where it can; a value keeps the type of its setter.
 */
final class FyrQLPreparedStatement extends FyrQLStatement implements PreparedStatement {

    /** The literal of a {@code NULL} without a type. */
    private static final String UNTYPED_NULL = "NULL";

    private final Parameters sql;
    private final String[] literals;

    FyrQLPreparedStatement(FyrQLConnection connection, int holdability, String sql) {
        super(connection, holdability);
        this.sql = Parameters.parse(sql);
        this.literals = new String[this.sql.count()];
    }

    /**
     * Returns the text with the literals in place of the parameters.
     *
     * @throws SQLException {@code 07001} if a parameter has no value
     */
    private String rendered() throws SQLException {
        for (int i = 0; i < literals.length; i++) {
            if (literals[i] == null) {
                throw new SQLException("No value is set for parameter " + (i + 1), SqlStates.PARAMETER_NOT_SET);
            }
        }
        return sql.render(literals);
    }

    /** Throws {@code HY010} if the statement is closed, {@code 22023} if there is no such parameter. */
    private void check(int index) throws SQLException {
        checkOpen();
        if (index < 1 || index > literals.length) {
            throw SqlStates.invalidArgument(literals.length == 0
                    ? "The query has no parameters; the index " + index + " is out of range"
                    : "The parameter index " + index + " is out of range 1 to " + literals.length);
        }
    }

    /**
     * Sets the literal of a value. A type with a {@code MAP} in it is
     * {@code 0A000}: the parser of FyrQL reads no cast to a map type, so
     * there is no literal of one.
     */
    private void put(int index, Typed value) throws SQLException {
        if (Parameters.hasMap(value.type())) {
            throw SqlStates.create("FyrQL does not take a parameter of type " + value.type().sqlName()
                    + ": there is no literal of a MAP", SqlStates.FEATURE_NOT_SUPPORTED, null);
        }
        literals[index - 1] = DuckDbDialect.INSTANCE.literal(value.value(), value.type());
    }

    /** Sets a value of a type; {@code null} is a {@code NULL} of the type. */
    private void put(int index, Object value, DataType type) throws SQLException {
        put(index, new Typed(value, type));
    }

    /** Fails for a method of {@code Statement} with a query text: a prepared statement runs its own. */
    private SQLException ownQuery() throws SQLException {
        checkOpen();
        return SqlStates.create("A prepared statement runs the query it was prepared with; call the method "
                + "without a query text", SqlStates.WRONG_OBJECT_TYPE, null);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        return run(rendered());
    }

    @Override
    public boolean execute() throws SQLException {
        executeQuery();
        return true;
    }

    /** Fails: FyrQL only reads; see {@link FyrQLStatement#executeUpdate(String)}. */
    @Override
    public int executeUpdate() throws SQLException {
        throw notAnUpdate(textForParser());
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        throw notAnUpdate(textForParser());
    }

    /** Returns the text with a {@code NULL} for every parameter without a value, for the message of the parser. */
    private String textForParser() throws SQLException {
        String[] all = literals.clone();
        Arrays.setAll(all, i -> all[i] == null ? UNTYPED_NULL : all[i]);
        return sql.render(all);
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Batches");
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(literals, null);
    }

    /**
     * Returns the columns of the result when every parameter has a value:
     * the engine plans the query without running it. Before that it is
     * {@code null}, since the types of the columns may depend on the
     * values.
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        for (String literal : literals) {
            if (literal == null) {
                return null;
            }
        }
        return new FyrQLResultSetMetaData(connection.describe(rendered()));
    }

    /**
     * Returns the parameters with the types the engine gives them when it
     * prepares the query; if it cannot, as for {@code SELECT ?}, their types
     * are unknown, see {@link FyrQLParameterMetaData}.
     */
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        List<DataType> types = connection.parameterTypes(sql.sql());
        return types != null && types.size() == literals.length ? FyrQLParameterMetaData.of(types)
                : FyrQLParameterMetaData.unknown(literals.length);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        check(parameterIndex);
        DataType type = JdbcValues.typeOf(sqlType);
        literals[parameterIndex - 1] = type == null ? UNTYPED_NULL : DuckDbDialect.INSTANCE.literal(null, type);
    }

    /** Sets a {@code NULL} of the type name if it is a type of FyrQL, else of the code. */
    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        check(parameterIndex);
        DataType type = null;
        if (typeName != null && !typeName.isBlank()) {
            try {
                type = TypeNames.parse(typeName);
            } catch (IllegalArgumentException e) {
                // Not a name of FyrQL: the code tells the type.
            }
        }
        if (type != null) {
            put(parameterIndex, null, type);
        } else {
            setNull(parameterIndex, sqlType);
        }
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.BOOLEAN);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.TINYINT);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.SMALLINT);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.INTEGER);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.BIGINT);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.FLOAT);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.DOUBLE);
    }

    /** Sets a {@code DECIMAL} of the digits and scale of the value, see {@link JdbcValues#decimal}. */
    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x == null ? new Typed(null, JdbcValues.DEFAULT_DECIMAL) : JdbcValues.decimal(x));
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x, DataType.VARCHAR);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x == null ? null : x.clone(), DataType.BLOB);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setDate(parameterIndex, x, null);
    }

    /** Sets the day of the date in the time zone of the calendar, or of the JVM. */
    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        check(parameterIndex);
        if (x == null) {
            put(parameterIndex, null, DataType.DATE);
        } else if (cal == null) {
            put(parameterIndex, JdbcValues.fromLocalDate(x.toLocalDate()));
        } else {
            put(parameterIndex, JdbcValues.fromLocalDate(
                    Instant.ofEpochMilli(x.getTime()).atZone(cal.getTimeZone().toZoneId()).toLocalDate()));
        }
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setTime(parameterIndex, x, null);
    }

    /** Fails with {@code 0A000}: FyrQL has no {@code TIME}. */
    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        check(parameterIndex);
        throw JdbcValues.noTime();
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setTimestamp(parameterIndex, x, null);
    }

    /** Sets the local date and time of the timestamp in the time zone of the calendar, or of the JVM. */
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        check(parameterIndex);
        if (x == null) {
            put(parameterIndex, null, DataType.TIMESTAMP);
        } else if (cal == null) {
            put(parameterIndex, JdbcValues.fromLocalDateTime(x.toLocalDateTime()));
        } else {
            put(parameterIndex, JdbcValues.fromLocalDateTime(
                    LocalDateTime.ofInstant(x.toInstant(), cal.getTimeZone().toZoneId())));
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        check(parameterIndex);
        if (x == null) {
            literals[parameterIndex - 1] = UNTYPED_NULL;
        } else {
            put(parameterIndex, JdbcValues.fromJava(x));
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x, targetSqlType, -1);
    }

    /**
     * Sets a value converted to the type of the code, see
     * {@link JdbcValues#convert}; {@code scaleOrLength} is the scale of a
     * {@code DECIMAL} or {@code NUMERIC} and is ignored otherwise.
     */
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        check(parameterIndex);
        if (x == null) {
            setNull(parameterIndex, targetSqlType);
            return;
        }
        boolean decimal = targetSqlType == Types.DECIMAL || targetSqlType == Types.NUMERIC;
        put(parameterIndex, JdbcValues.convert(JdbcValues.fromJava(x), targetSqlType, decimal ? scaleOrLength : -1));
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        setObject(parameterIndex, x, code(targetSqlType), -1);
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength)
            throws SQLException {
        setObject(parameterIndex, x, code(targetSqlType), scaleOrLength);
    }

    /** Returns the code of a type of JDBC; FyrQL knows no type of a vendor. */
    private int code(SQLType type) throws SQLException {
        checkOpen();
        if (type instanceof JDBCType t) {
            return t.getVendorTypeNumber();
        }
        throw SqlStates.create("FyrQL has no type " + (type == null ? null : type.getName()),
                SqlStates.FEATURE_NOT_SUPPORTED, null);
    }

    /** Sets an array as a result set of FyrQL returns it; other arrays are {@code 0A000}. */
    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        check(parameterIndex);
        if (x != null && !(x instanceof FyrQLArray)) {
            throw SqlStates.create("FyrQL takes an array as a parameter only as a result set of FyrQL returns it",
                    SqlStates.FEATURE_NOT_SUPPORTED, null);
        }
        setObject(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        setText(parameterIndex, x, -1, StandardCharsets.US_ASCII);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setText(parameterIndex, x, length, StandardCharsets.US_ASCII);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setText(parameterIndex, x, length, StandardCharsets.US_ASCII);
    }

    /** Sets the text of the bytes in UTF-8, as JDBC defines the stream. */
    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setText(parameterIndex, x, length, StandardCharsets.UTF_8);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setBytes(parameterIndex, x, -1);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBytes(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setBytes(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setText(parameterIndex, reader, -1);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setText(parameterIndex, reader, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        setText(parameterIndex, reader, length);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setText(parameterIndex, value, -1);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setText(parameterIndex, value, length);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x == null ? null : x.getBytes(1, Math.toIntExact(x.length())), DataType.BLOB);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setBytes(parameterIndex, inputStream, -1);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        setBytes(parameterIndex, inputStream, length);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, x == null ? null : x.getSubString(1, Math.toIntExact(x.length())), DataType.VARCHAR);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setText(parameterIndex, reader, -1);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setText(parameterIndex, reader, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        setClob(parameterIndex, value);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setText(parameterIndex, reader, -1);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setText(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        check(parameterIndex);
        throw SqlStates.unsupported("References");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        check(parameterIndex);
        throw SqlStates.unsupported("Row ids");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        check(parameterIndex);
        throw SqlStates.unsupported("XML values");
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        check(parameterIndex);
        throw SqlStates.unsupported("URL values");
    }

    /** Sets the bytes of a stream, at most {@code length} of them unless it is {@code -1}. */
    private void setBytes(int parameterIndex, InputStream in, long length) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, in == null ? null : read(parameterIndex, in, length), DataType.BLOB);
    }

    /** Sets the text of the bytes of a stream in a character set. */
    private void setText(int parameterIndex, InputStream in, long length, Charset charset) throws SQLException {
        check(parameterIndex);
        put(parameterIndex, in == null ? null : new String(read(parameterIndex, in, length), charset),
                DataType.VARCHAR);
    }

    /** Sets the characters of a reader, at most {@code length} of them unless it is {@code -1}. */
    private void setText(int parameterIndex, Reader reader, long length) throws SQLException {
        check(parameterIndex);
        checkLength(length);
        if (reader == null) {
            put(parameterIndex, null, DataType.VARCHAR);
            return;
        }
        StringBuilder b = new StringBuilder();
        char[] buffer = new char[8192];
        long left = length < 0 ? Long.MAX_VALUE : length;
        try {
            int n;
            while (left > 0 && (n = reader.read(buffer, 0, (int) Math.min(buffer.length, left))) >= 0) {
                b.append(buffer, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            throw cannotRead(parameterIndex, e);
        }
        put(parameterIndex, b.toString(), DataType.VARCHAR);
    }

    private static byte[] read(int parameterIndex, InputStream in, long length) throws SQLException {
        checkLength(length);
        try {
            if (length < 0) {
                return in.readAllBytes();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long left = length;
            int n;
            while (left > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) >= 0) {
                out.write(buffer, 0, n);
                left -= n;
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw cannotRead(parameterIndex, e);
        }
    }

    private static void checkLength(long length) throws SQLException {
        if (length < -1) {
            throw SqlStates.invalidArgument("The length must not be negative: " + length);
        }
    }

    private static SQLException cannotRead(int parameterIndex, IOException e) {
        return new SQLException("Cannot read the value of parameter " + parameterIndex + ": " + e.getMessage(),
                SqlStates.GENERAL, e);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw ownQuery();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw ownQuery();
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        throw ownQuery();
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw ownQuery();
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw ownQuery();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw ownQuery();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw ownQuery();
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw ownQuery();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw ownQuery();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw ownQuery();
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw ownQuery();
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw ownQuery();
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        throw ownQuery();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw ownQuery();
    }
}
