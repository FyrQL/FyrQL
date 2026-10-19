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

import net.fyrql.FyrQL;
import net.fyrql.catalog.SearchPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryContext;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connection to an engine, which it owns and closes, unless it was
 * opened over an engine of the application with
 * {@link FyrQLDriver#connect(FyrQL, boolean)}. The connection only reads:
 * it is always read-only and in auto-commit; the methods of transactions
 * are accepted and do nothing. The catalog is the default source and the
 * schema the default schema in which the queries of the connection look up
 * names of fewer than three parts, at first those of the engine; changing
 * them affects only this connection. The connection unwraps to the
 * {@link FyrQL} engine.
 * <p>
 * The queries of a connection run one at a time: starting a query and
 * reading a batch of its result take a lock of the connection.
 */
final class FyrQLConnection implements Connection {

    /** The result of {@code EXPLAIN}: the plan, one line per row. */
    private static final Schema PLAN = Schema.of(new Schema.Field("plan", DataType.VARCHAR, false));
    private static final Pattern EXPLAIN = Pattern.compile("\\s*EXPLAIN\\s+(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final FyrQL engine;
    private final boolean closeEngine;
    private final String url;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<FyrQLStatement> statements = ConcurrentHashMap.newKeySet();
    private final Properties clientInfo = new Properties();
    private volatile SearchPath searchPath;
    private SQLWarning warnings;
    private int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private int networkTimeout;

    FyrQLConnection(FyrQL engine, boolean closeEngine, String url) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.closeEngine = closeEngine;
        this.url = url;
        this.searchPath = engine.catalog().searchPath();
    }

    /** Returns the engine. */
    FyrQL engine() {
        return engine;
    }

    /** Returns the URL the connection was opened with. */
    String url() {
        return url;
    }

    /** Returns the search path of the queries of the connection. */
    SearchPath searchPath() {
        return searchPath;
    }

    /** Adds a warning to the chain of the connection. */
    synchronized void addWarning(SQLWarning warning) {
        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    /** Returns the lock that queries start and read their batches under. */
    Lock lock() {
        return lock;
    }

    /**
     * Starts a query that a context cancels: {@code EXPLAIN} and a query
     * gives its plan, anything else goes to the engine. A query canceled
     * while it is planned fails with its first batch.
     */
    BatchSource query(String sql, QueryContext context) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            Matcher m = EXPLAIN.matcher(sql);
            if (m.matches()) {
                List<Object[]> rows = new ArrayList<>();
                for (String line : engine.explain(m.group(1), searchPath).split("\n")) {
                    rows.add(new Object[] {line});
                }
                return BatchSource.of(PLAN, rows);
            }
            return BatchSource.of(engine.execute(engine.plan(sql, searchPath), context), context);
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Plans a query without running it and returns the columns of its
     * result; {@code EXPLAIN} and a query has the column {@code plan}.
     */
    Schema describe(String sql) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            return EXPLAIN.matcher(sql).matches() ? PLAN : engine.plan(sql, searchPath).schema();
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            lock.unlock();
        }
    }

    /** Called by a statement when it closes. */
    void statementClosed(FyrQLStatement statement) {
        statements.remove(statement);
    }

    /** Throws {@code 08003} if the connection is closed. */
    void checkOpen() throws SQLException {
        if (closed.get()) {
            throw SqlStates.connectionClosed();
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(holdability);
    }

    private Statement createStatement(int resultSetHoldability) throws SQLException {
        checkOpen();
        FyrQLStatement statement = new FyrQLStatement(this, resultSetHoldability);
        statements.add(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        checkHoldability(resultSetHoldability);
        return createStatement(resultSetHoldability);
    }

    private static void checkHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT && holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw SqlStates.invalidArgument("Unknown holdability " + holdability);
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepare(sql, holdability);
    }

    private PreparedStatement prepare(String sql, int resultSetHoldability) throws SQLException {
        checkOpen();
        if (sql == null) {
            throw SqlStates.invalidArgument("The query must not be null");
        }
        FyrQLPreparedStatement statement = new FyrQLPreparedStatement(this, resultSetHoldability, sql);
        statements.add(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        checkHoldability(resultSetHoldability);
        return prepare(sql, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            checkOpen();
            throw SqlStates.unsupported("Generated keys");
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Generated keys");
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Stored procedures");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }

    /**
     * Accepts every type and concurrency of result sets, but a result set
     * that is not forward only and read only is one with a warning, since
     * tools ask for scrollable ones without needing them.
     */
    private void checkResultSet(int type, int concurrency) throws SQLException {
        checkOpen();
        if (type != ResultSet.TYPE_FORWARD_ONLY) {
            addWarning(new SQLWarning("Result sets are forward only; the type " + type + " is not supported"));
        }
        if (concurrency != ResultSet.CONCUR_READ_ONLY) {
            addWarning(new SQLWarning("Result sets are read only; the concurrency " + concurrency
                    + " is not supported"));
        }
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
    }

    /** Closes the statements of the connection, and the engine if the connection owns it. */
    @Override
    public void close() throws SQLException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SQLException failure = null;
        for (FyrQLStatement statement : List.copyOf(statements)) {
            try {
                statement.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        statements.clear();
        if (failure != null && !closeEngine) {
            throw failure;
        }
        if (closeEngine) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                SQLException f = new SQLException("Cannot close the sources of the engine: " + e.getMessage(),
                        SqlStates.GENERAL, e);
                if (failure != null) {
                    f.addSuppressed(failure);
                }
                throw f;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new FyrQLDatabaseMetaData(this);
    }

    /** Accepts either value; the connection is always read-only. */
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return true;
    }

    /**
     * Sets the default source, in which names of fewer than three parts are
     * looked up first; the name matches exactly, or else ignoring case if
     * that is unique.
     *
     * @throws SQLException {@code 3D000} if there is no such source
     */
    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        if (catalog == null) {
            return;
        }
        String source = match(engine.sources(), catalog);
        if (source == null) {
            throw new SQLException(unknown("source", catalog, engine.sources()), SqlStates.INVALID_CATALOG);
        }
        searchPath = new SearchPath(source, searchPath.defaultSchema());
    }

    /**
     * Returns the name that matches exactly, or else the only one that
     * matches ignoring case; {@code null} if there is none or more than one.
     */
    private static String match(List<String> names, String name) {
        if (names.contains(name)) {
            return name;
        }
        String match = null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                if (match != null) {
                    return null;
                }
                match = n;
            }
        }
        return match;
    }

    /** Returns the message of a name that matches none or several of the names. */
    private static String unknown(String kind, String name, List<String> names) {
        boolean ambiguous = names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        return (ambiguous ? "The " + kind + " name is ambiguous: " : "There is no " + kind + " ") + name
                + "; the " + kind + "s are " + String.join(", ", names);
    }

    /** Returns the default source, or {@code null} if there is none. */
    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return searchPath.defaultSource();
    }

    /** Accepts every level; there are no transactions. */
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
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
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return new HashMap<>();
    }

    /** Accepts an empty map; there are no user-defined types to map. */
    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
        if (map != null && !map.isEmpty()) {
            throw SqlStates.unsupported("User-defined types");
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        checkHoldability(holdability);
        this.holdability = holdability;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return holdability;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Savepoints");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return setSavepoint();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        setSavepoint();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        setSavepoint();
    }

    @Override
    public Clob createClob() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Clobs");
    }

    @Override
    public Blob createBlob() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Blobs");
    }

    @Override
    public NClob createNClob() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("NClobs");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("SQLXML values");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Arrays created by the connection");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        checkOpen();
        throw SqlStates.unsupported("Structs created by the connection");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw SqlStates.invalidArgument("The timeout must not be negative: " + timeout);
        }
        return !closed.get();
    }

    /** Keeps the client information; the engine does not use it. */
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        if (closed.get()) {
            throw new SQLClientInfoException("The connection is closed", SqlStates.CONNECTION_CLOSED, Map.of());
        }
        synchronized (clientInfo) {
            if (value == null) {
                clientInfo.remove(name);
            } else {
                clientInfo.setProperty(name, value);
            }
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (closed.get()) {
            throw new SQLClientInfoException("The connection is closed", SqlStates.CONNECTION_CLOSED, Map.of());
        }
        synchronized (clientInfo) {
            clientInfo.clear();
            if (properties != null) {
                for (String name : properties.stringPropertyNames()) {
                    clientInfo.setProperty(name, properties.getProperty(name));
                }
            }
        }
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        synchronized (clientInfo) {
            return clientInfo.getProperty(name);
        }
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        synchronized (clientInfo) {
            Properties copy = new Properties();
            copy.putAll(clientInfo);
            return copy;
        }
    }

    /**
     * Sets the default schema, in which one-part names are looked up first
     * in the default source. With a default source, the name matches a
     * schema of it exactly, or else ignoring case if that is unique; without
     * one, it is kept as it is until a catalog is set.
     *
     * @throws SQLException {@code 3F000} if the default source has no such schema
     */
    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
        if (schema == null) {
            return;
        }
        SearchPath path = searchPath;
        String name = schema;
        if (path.defaultSource() != null) {
            List<String> schemas;
            try {
                schemas = engine.connector(path.defaultSource()).catalog().schemas();
            } catch (RuntimeException e) {
                throw SqlStates.map(e);
            }
            name = match(schemas, schema);
            if (name == null) {
                throw new SQLException(unknown("schema", schema, schemas) + " of the source "
                        + path.defaultSource(), SqlStates.INVALID_SCHEMA);
            }
        }
        searchPath = new SearchPath(path.defaultSource(), name);
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return searchPath.defaultSchema();
    }

    /** Closes the connection. */
    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw SqlStates.invalidArgument("The executor of abort must not be null");
        }
        close();
    }

    /** Keeps the timeout; the engine does not wait on a network of its own. */
    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();
        if (milliseconds < 0) {
            throw SqlStates.invalidArgument("The timeout must not be negative: " + milliseconds);
        }
        this.networkTimeout = milliseconds;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeout;
    }

    /** Unwraps to the connection itself or to its {@link FyrQL} engine. */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        if (iface.isInstance(engine)) {
            return iface.cast(engine);
        }
        throw new SQLException("The connection does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this) || iface.isInstance(engine);
    }
}
