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

import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryContext;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A statement that runs one statement of SQL at a time; each run closes
 * the result set of the one before. A query is one {@code SELECT},
 * {@code WITH}, {@code VALUES} or set operation, or {@code EXPLAIN} or
 * {@code EXPLAIN ANALYZE} and a statement, whose result is the plan in a
 * column {@code plan}, one line per row; {@code EXPLAIN ANALYZE} runs the
 * statement first, and one that writes writes. A statement that writes,
 * {@code COPY ... TO}, {@code COPY ... FROM}, {@code CREATE TABLE},
 * {@code INSERT INTO} or {@code DROP TABLE}, runs to its end before the method returns, and the
 * rows it wrote are the update count: {@link #execute(String)} runs either
 * kind and returns {@code false} for a statement that writes,
 * {@link #executeUpdate(String)} runs only those, and
 * {@link #executeQuery(String)} only queries; a statement of the other
 * kind fails with {@code 42809} before it runs.
 * Every other statement fails with the message of FyrQL. A batch holds
 * statements that write, which run one after another.
 */
class FyrQLStatement implements Statement {

    final FyrQLConnection connection;

    /** Returns the time zone of the session of the connection. */
    ZoneId zone() {
        return connection.session().timeZone();
    }
    private final int holdability;
    private FyrQLResultSet resultSet;
    private final List<FyrQLResultSet> kept = new ArrayList<>();
    /** The rows the last statement wrote, {@code -1} after a query. */
    private long updateCount = -1;
    private final List<String> batch = new ArrayList<>();
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
     * Runs a statement. A query becomes the current result set: it runs
     * until its first rows before this returns, and then until its result
     * set is read or closed. A statement that writes runs to its end, and
     * the rows it wrote become the update count. {@link #cancel()} and the
     * query timeout end either on the way, from another thread.
     *
     * @param expected the kind of statement the method runs
     * @return the result set of a query, {@code null} for a statement that writes
     */
    FyrQLResultSet run(String sql, FyrQLConnection.Kind expected) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw SqlStates.invalidArgument("The query must not be null");
        }
        closeResultSet();
        updateCount = -1;
        QueryContext context = queryTimeout > 0 ? QueryContext.withTimeout(Duration.ofSeconds(queryTimeout))
                : new QueryContext();
        running = context;
        FyrQLConnection.Outcome outcome = connection.run(sql, context, expected);
        BatchSource source = outcome.rows();
        if (source == null) {
            updateCount = outcome.count();
            return null;
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

    /** Runs a query; a statement that writes fails with {@code 42809} before it runs. */
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return run(sql, FyrQLConnection.Kind.QUERY);
    }

    /**
     * Runs a query or a statement that writes, see {@link FyrQLStatement}.
     *
     * @return {@code true} for a query, {@code false} for a statement that
     *         writes, whose count {@link #getUpdateCount()} returns
     */
    @Override
    public boolean execute(String sql) throws SQLException {
        return run(sql, FyrQLConnection.Kind.ANY) != null;
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
     * Runs a statement that writes and returns the rows it wrote, at most
     * {@link Integer#MAX_VALUE}; a query fails with {@code 42809} before it
     * runs.
     */
    @Override
    public int executeUpdate(String sql) throws SQLException {
        return clamp(executeLargeUpdate(sql));
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return clamp(executeLargeUpdate(sql, autoGeneratedKeys));
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return clamp(executeLargeUpdate(sql, columnIndexes));
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return clamp(executeLargeUpdate(sql, columnNames));
    }

    /** Runs a statement that writes and returns the rows it wrote, see {@link #executeUpdate(String)}. */
    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        run(sql, FyrQLConnection.Kind.UPDATE);
        return updateCount;
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != NO_GENERATED_KEYS) {
            checkOpen();
            throw SqlStates.unsupported("Generated keys");
        }
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    /** Returns a number of rows as an {@code int}, at most {@link Integer#MAX_VALUE}. */
    static int clamp(long rows) {
        return (int) Math.min(rows, Integer.MAX_VALUE);
    }

    /** Adds a statement that writes to the batch, see {@link #executeLargeBatch()}. */
    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw SqlStates.invalidArgument("The query must not be null");
        }
        batch.add(sql);
    }

    /** Adds a text to the batch, after the checks of the caller. */
    void addToBatch(String sql) {
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    /** Runs the batch, see {@link #executeLargeBatch()}; each count is at most {@link Integer#MAX_VALUE}. */
    @Override
    public int[] executeBatch() throws SQLException {
        long[] counts = executeLargeBatch();
        int[] result = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            result[i] = clamp(counts[i]);
        }
        return result;
    }

    /**
     * Runs the statements of the batch one after another, each on its own
     * as {@link #executeLargeUpdate(String)} runs it, and empties the
     * batch. The first that fails, or is a query, ends the batch with a
     * {@link BatchUpdateException} that holds the counts of the statements
     * before it, whose writes stay.
     */
    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        List<String> statements = List.copyOf(batch);
        batch.clear();
        long[] counts = new long[statements.size()];
        for (int i = 0; i < counts.length; i++) {
            try {
                run(statements.get(i), FyrQLConnection.Kind.UPDATE);
            } catch (SQLException e) {
                throw new BatchUpdateException("Statement " + (i + 1) + " of the batch fails: " + e.getMessage(),
                        e.getSQLState(), e.getErrorCode(), Arrays.copyOf(counts, i), e);
            }
            counts[i] = updateCount;
        }
        updateCount = -1;
        return counts;
    }

    @Override
    public synchronized ResultSet getResultSet() throws SQLException {
        checkOpen();
        return resultSet;
    }

    /**
     * Returns the rows the last statement wrote, at most
     * {@link Integer#MAX_VALUE}; {@code -1} after a query, after a batch
     * and after {@link #getMoreResults()}.
     */
    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return clamp(updateCount);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    /** Closes the current result set and ends the update count; there is never another result. */
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
        updateCount = -1;
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
     * canceled and fails with {@code HYT00}. {@code 0} takes the timeout
     * of the engine, the setting {@code queryTimeout}, which is no limit by
     * default; the setting {@code maxQueryTimeout} shortens any timeout.
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
