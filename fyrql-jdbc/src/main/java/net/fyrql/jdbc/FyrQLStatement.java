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

package net.fyrql.jdbc;

import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.parse.SqlParser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * A statement that runs one query at a time; each run closes the result
 * set of the one before. A query is one {@code SELECT}, {@code WITH},
 * {@code VALUES} or set operation, or {@code EXPLAIN} and a query, whose
 * result is the plan in a column {@code plan}, one line per row. Every
 * other statement fails with the message of FyrQL; updates and batches
 * are not supported.
 */
class FyrQLStatement implements Statement {

    final FyrQLConnection connection;
    private final int holdability;
    private FyrQLResultSet resultSet;
    private final List<FyrQLResultSet> kept = new ArrayList<>();
    private long maxRows;
    private int fetchSize;
    private int queryTimeout;
    /** The context of the last query, which {@link #cancel()} cancels. */
    private volatile QueryContext running;
    private int maxFieldSize;
    private boolean poolable;
    private boolean closeOnCompletion;
    private SQLWarning warnings;
    private volatile boolean closed;

    FyrQLStatement(FyrQLConnection connection, int holdability) {
        this.connection = connection;
        this.holdability = holdability;
    }

    /** Throws {@code HY010} if the statement is closed. */
    void checkOpen() throws SQLException {
        if (closed) {
            throw SqlStates.closed("statement");
        }
    }

    /**
     * Runs a query and makes its result the current result set. The query
     * runs until its first rows before this returns, and then until its
     * result set is read or closed; {@link #cancel()} and the query timeout
     * end it on the way, from another thread.
     */
    FyrQLResultSet run(String sql) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw SqlStates.invalidArgument("The query must not be null");
        }
        closeResultSet();
        QueryContext context = new QueryContext();
        running = context;
        ScheduledFuture<?> timeout = queryTimeout > 0 ? Timeouts.schedule(queryTimeout, context) : null;
        BatchSource source;
        try {
            source = connection.query(sql, context);
        } catch (SQLException | RuntimeException e) {
            if (timeout != null) {
                timeout.cancel(false);
            }
            throw e;
        }
        if (timeout != null) {
            source = BatchSource.whenClosed(source, () -> timeout.cancel(false));
        }
        FyrQLResultSet rs = new FyrQLResultSet(this, source, maxRows, connection.lock(), holdability);
        synchronized (this) {
            resultSet = rs;
        }
        try {
            rs.prefetch();
        } catch (SQLException e) {
            try {
                rs.close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return rs;
    }

    /**
     * Called by a result set of the statement when it closes; the
     * statement closes with its last open result set if
     * {@link #closeOnCompletion()} was called.
     */
    void resultSetClosed(FyrQLResultSet closedSet) throws SQLException {
        boolean last;
        synchronized (this) {
            if (!kept.remove(closedSet)) {
                if (resultSet != closedSet) {
                    return;
                }
                resultSet = null;
            }
            last = kept.isEmpty() && resultSet == null;
        }
        if (closeOnCompletion && last) {
            close();
        }
    }

    private void closeResultSet() throws SQLException {
        FyrQLResultSet rs;
        synchronized (this) {
            rs = resultSet;
            resultSet = null;
        }
        if (rs != null) {
            rs.close();
        }
    }

    /** Closes the current result set and those kept open by {@link #getMoreResults(int)}. */
    private void closeAllResultSets() throws SQLException {
        List<FyrQLResultSet> all;
        synchronized (this) {
            all = new ArrayList<>(kept);
            kept.clear();
        }
        SQLException failure = null;
        for (FyrQLResultSet rs : all) {
            try {
                rs.close();
            } catch (SQLException e) {
                failure = failure == null ? e : failure;
            }
        }
        closeResultSet();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return run(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        run(sql);
        return true;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != NO_GENERATED_KEYS) {
            checkOpen();
            throw SqlStates.unsupported("Generated keys");
        }
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    /**
     * Fails: FyrQL only reads. A text that is no query fails with the
     * message of FyrQL, such as {@code Only queries are supported, not
     * INSERT}.
     */
    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw notAnUpdate(sql);
    }

    /** Returns the failure of an update, after the text went to the parser for its message. */
    SQLException notAnUpdate(String sql) throws SQLException {
        checkOpen();
        if (sql != null) {
            try {
                SqlParser.parse(sql);
            } catch (RuntimeException e) {
                return SqlStates.map(e);
            }
        }
        return SqlStates.create("Updates are not supported by FyrQL; run a query with executeQuery",
                SqlStates.FEATURE_NOT_SUPPORTED, null);
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notAnUpdate(sql);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Batches");
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Batches");
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Batches");
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Batches");
    }

    @Override
    public synchronized ResultSet getResultSet() throws SQLException {
        checkOpen();
        return resultSet;
    }

    /** Returns {@code -1}: a statement has no update counts. */
    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return -1;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return -1;
    }

    /** Closes the current result set; there is never another result. */
    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();
        if (current != CLOSE_CURRENT_RESULT && current != KEEP_CURRENT_RESULT && current != CLOSE_ALL_RESULTS) {
            throw SqlStates.invalidArgument("Unknown argument of getMoreResults: " + current);
        }
        if (current == KEEP_CURRENT_RESULT) {
            synchronized (this) {
                if (resultSet != null) {
                    kept.add(resultSet);
                    resultSet = null;
                }
            }
        } else if (current == CLOSE_ALL_RESULTS) {
            closeAllResultSets();
        } else {
            closeResultSet();
        }
        return false;
    }

    /** Returns an empty result: there are no generated keys. */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        return new FyrQLResultSet(this, BatchSource.of(new Schema(List.of()), List.of()), 0, null, holdability);
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return (int) Math.min(maxRows, Integer.MAX_VALUE);
    }

    /** Sets the most rows a result set returns, from the next run on; {@code 0} is no limit. */
    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw SqlStates.invalidArgument("The maximum number of rows must not be negative: " + max);
        }
        this.maxRows = max;
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    /** Keeps the value; the result sets return the values whole. */
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw SqlStates.invalidArgument("The maximum field size must not be negative: " + max);
        }
        this.maxFieldSize = max;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return maxFieldSize;
    }

    /** Keeps the hint; a result set reads the batches of the engine as they come. */
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

    /** Accepts only {@link ResultSet#FETCH_FORWARD}, and {@link ResultSet#FETCH_UNKNOWN}, which is taken as such. */
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction == ResultSet.FETCH_REVERSE) {
            throw SqlStates.unsupported("Scrollable result sets");
        }
        if (direction != ResultSet.FETCH_FORWARD && direction != ResultSet.FETCH_UNKNOWN) {
            throw SqlStates.invalidArgument("Unknown fetch direction " + direction);
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    /**
     * Sets the seconds a query may run, from the next run on: from its
     * start until its result set is read or closed, which includes the
     * time the application takes between the rows. Then the query is
     * canceled and fails with {@code HYT00}; {@code 0} is no limit.
     */
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw SqlStates.invalidArgument("The query timeout must not be negative: " + seconds);
        }
        this.queryTimeout = seconds;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeout;
    }

    /**
     * Cancels the query of the statement from another thread: its sources
     * are asked to stop, and reading its result set fails with
     * {@code 57014}, after the rows already read. Without a query, or after
     * its result set is read, it does nothing.
     */
    @Override
    public void cancel() throws SQLException {
        checkOpen();
        QueryContext context = running;
        if (context != null) {
            context.cancel();
        }
    }

    /** Accepts either value; the text goes to the engine as it is. */
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Named cursors");
    }

    @Override
    public synchronized SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public synchronized void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        this.poolable = poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
    }

    /** Closes the statement and its result set. */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            closeAllResultSets();
        } finally {
            connection.statementClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The statement does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
