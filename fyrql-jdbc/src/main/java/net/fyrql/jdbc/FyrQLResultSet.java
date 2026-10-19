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
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A result set over the batches of a query, forward only and read only.
 * It reads one batch at a time; when the last row is read, or the maximum
 * number of rows of the statement, it releases the sources of the query
 * at once. The getters convert as {@link JdbcValues} describes; a column
 * label matches ignoring case, the first column of a name wins.
 */
final class FyrQLResultSet implements ResultSet {

    private final FyrQLStatement statement;
    private final BatchSource source;
    private final Schema schema;
    private final long maxRows;
    private final Lock lock;
    private final int holdability;
    private Batch batch;
    private Batch pending;
    private int row = -1;
    private long rowNumber;
    private boolean afterLast;
    private volatile boolean exhausted;
    private boolean wasNull;
    private int fetchSize;
    private volatile boolean closed;

    /**
     * @param statement the statement of the result set, or {@code null}
     * @param maxRows the most rows to return, or {@code 0} for all
     * @param lock the lock that the batches are read under, or {@code null}
     */
    FyrQLResultSet(FyrQLStatement statement, BatchSource source, long maxRows, Lock lock, int holdability) {
        this.statement = statement;
        this.source = source;
        this.schema = source.schema();
        this.maxRows = maxRows;
        this.lock = lock;
        this.holdability = holdability;
    }

    /** Returns the columns. */
    Schema schema() {
        return schema;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw SqlStates.closed("result set");
        }
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (afterLast) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            toEnd();
            return false;
        }
        if (batch != null && row + 1 < batch.rowCount()) {
            row++;
            rowNumber++;
            return true;
        }
        Batch b = pending != null ? pending : fetch();
        pending = null;
        if (b == null) {
            toEnd();
            return false;
        }
        batch = b;
        row = 0;
        rowNumber++;
        return true;
    }

    /**
     * Reads the first batch with rows ahead, so that the query runs until
     * its first rows while it is executed, as in the drivers of databases:
     * tools cancel a statement only while it is executed, and an error of
     * the first rows is one of the execution.
     */
    void prefetch() throws SQLException {
        if (batch == null && pending == null && !afterLast) {
            pending = fetch();
        }
    }

    /** Reads the next batch with rows from the source, or returns {@code null} after the last one. */
    private Batch fetch() throws SQLException {
        if (exhausted) {
            return null;
        }
        if (lock != null) {
            lock.lock();
        }
        try {
            for (Batch b = source.next(); b != null; b = source.next()) {
                if (b.rowCount() > 0) {
                    return b;
                }
            }
        } catch (RuntimeException e) {
            SQLException failure = SqlStates.map(e);
            afterLast = true;
            batch = null;
            try {
                release();
            } catch (SQLException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
        release();
        return null;
    }

    /** Moves after the last row and releases the source. */
    private void toEnd() throws SQLException {
        afterLast = true;
        batch = null;
        pending = null;
        release();
    }

    /** Closes the source, once, also when another thread closes the result set while this one reads. */
    private void release() throws SQLException {
        if (exhausted) {
            return;
        }
        if (lock != null) {
            lock.lock();
        }
        try {
            if (exhausted) {
                return;
            }
            exhausted = true;
            source.close();
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /**
     * Closes the result set and ends its query. While another thread reads
     * a batch of it, the query is canceled first, so that closing does not
     * wait until the batch is there.
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        batch = null;
        pending = null;
        if (lock instanceof ReentrantLock l && l.isLocked() && !l.isHeldByCurrentThread()) {
            source.cancel();
        }
        try {
            release();
        } finally {
            if (statement != null) {
                statement.resultSetClosed(this);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** Returns the value of a column in the current row; {@code null} for {@code NULL}. */
    private Object value(int column) throws SQLException {
        checkOpen();
        checkColumn(column);
        if (batch == null) {
            throw new SQLException(afterLast ? "The result set is after its last row"
                    : "The result set is not on a row; call next() first", SqlStates.INVALID_CURSOR_STATE);
        }
        Vector v = batch.column(column - 1);
        wasNull = v.isNull(row);
        return wasNull ? null : v.get(row);
    }

    private void checkColumn(int column) throws SQLException {
        if (column < 1 || column > schema.size()) {
            throw SqlStates.invalidArgument("The column index " + column + " is out of range; the result has "
                    + schema.size() + " columns");
        }
    }

    private DataType type(int column) {
        return schema.type(column - 1);
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel != null) {
            for (int i = 0; i < schema.size(); i++) {
                if (schema.name(i).equalsIgnoreCase(columnLabel)) {
                    return i + 1;
                }
            }
        }
        throw SqlStates.create("The result has no column " + columnLabel + "; the columns are "
                + String.join(", ", schema.fields().stream().map(Schema.Field::name).toList()),
                SqlStates.UNDEFINED_COLUMN, null);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toText(v, type(columnIndex));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v != null && JdbcValues.toBoolean(v, type(columnIndex));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : (byte) JdbcValues.toLong(v, type(columnIndex), Byte.MIN_VALUE, Byte.MAX_VALUE,
                "a byte");
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : (short) JdbcValues.toLong(v, type(columnIndex), Short.MIN_VALUE, Short.MAX_VALUE,
                "a short");
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : (int) JdbcValues.toLong(v, type(columnIndex), Integer.MIN_VALUE, Integer.MAX_VALUE,
                "an int");
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : JdbcValues.toLong(v, type(columnIndex), Long.MIN_VALUE, Long.MAX_VALUE, "a long");
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : JdbcValues.toFloat(v, type(columnIndex));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? 0 : JdbcValues.toDouble(v, type(columnIndex));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal d = getBigDecimal(columnIndex);
        return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toBigDecimal(v, type(columnIndex));
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toBytes(v, type(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return getDate(columnIndex, null);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return getTime(columnIndex, null);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getTimestamp(columnIndex, null);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toDate(v, type(columnIndex), cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toTime(v, type(columnIndex), cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toTimestamp(v, type(columnIndex), cal);
    }

    /** Returns the text as ASCII; a character outside of it is a {@code ?}. */
    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_16BE));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new StringReader(s);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toObject(v, type(columnIndex));
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw SqlStates.unsupported("User-defined types");
        }
        return getObject(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return JdbcValues.toClass(value(columnIndex), type(columnIndex), type);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        Object v = value(columnIndex);
        return v == null ? null : JdbcValues.toArray(v, type(columnIndex));
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("Refs");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("Blobs");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("Clobs");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("NClobs");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("SQLXML values");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("URL values");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        value(columnIndex);
        throw SqlStates.unsupported("Row ids");
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public String getCursorName() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Named cursors");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new FyrQLResultSetMetaData(schema);
    }

    /** Tells whether the result set is before its first row; {@code false} if it has none. */
    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        if (batch != null || afterLast) {
            return false;
        }
        if (pending == null) {
            pending = fetch();
        }
        return pending != null;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return afterLast && rowNumber > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return batch != null && rowNumber == 1;
    }

    /** Tells whether the current row is the last; it may read the next batch ahead. */
    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        if (batch == null) {
            return false;
        }
        if (maxRows > 0 && rowNumber >= maxRows) {
            return true;
        }
        if (row + 1 < batch.rowCount()) {
            return false;
        }
        if (pending == null) {
            pending = fetch();
        }
        return pending == null;
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly();
    }

    /** Returns the number of the current row, from 1, or 0 if there is none. */
    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return batch == null ? 0 : (int) Math.min(rowNumber, Integer.MAX_VALUE);
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly();
    }

    private SQLException forwardOnly() throws SQLException {
        checkOpen();
        return SqlStates.unsupported("Scrollable result sets");
    }

    private SQLException readOnly() throws SQLException {
        checkOpen();
        return SqlStates.unsupported("Updates of result sets");
    }

    /** Accepts only {@link #FETCH_FORWARD}, and {@link #FETCH_UNKNOWN}, which is taken as such. */
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction == FETCH_REVERSE) {
            throw forwardOnly();
        }
        if (direction != FETCH_FORWARD && direction != FETCH_UNKNOWN) {
            throw SqlStates.invalidArgument("Unknown fetch direction " + direction);
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    /** Keeps the hint; the result set reads the batches of the engine as they come. */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw SqlStates.invalidArgument("The fetch size must not be negative: " + rows);
        }
        this.fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The result set does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }
}
