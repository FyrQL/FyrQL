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
import net.fyrql.PreparedQuery;
import net.fyrql.Session;
import net.fyrql.SqlStatement;
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.ConfigOptions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;

import java.nio.file.Path;
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
import java.time.Duration;
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

/**
 * A connection to an engine, which it shares with the other connections of
 * the driver with the same settings and whose last connection closes it,
 * which it owns and closes with {@code shared=false}, or which belongs to
 * the application if it was opened with
 * {@link FyrQLDriver#connect(FyrQL, boolean)}. The connection is always in
 * auto-commit: a statement that writes is one transaction of the source
 * it writes to, if the source has transactions, and takes effect at once.
 * The methods of transactions are accepted and do nothing, as tools call
 * them when they connect: {@code setAutoCommit(false)} leaves the
 * connection in auto-commit, and {@code rollback()} after a statement that
 * wrote undoes nothing. A connection set read-only
 * refuses statements that write with {@code 25006}. The connection is a
 * {@link Session} of the engine of its own, also over an engine that
 * connections share: {@code SET}, {@code RESET} and {@code USE} change
 * only this connection. The catalog is the default source and the schema
 * the default schema in which the queries of the connection look up names
 * of fewer than three parts, at first those of the engine, the search path
 * of the session; changing them affects only this connection. The
 * connection unwraps to the {@link FyrQL} engine.
 * <p>
 * The queries of a connection run one at a time: starting a query and
 * reading a batch of its result take a lock of the connection.
 */
final class FyrQLConnection implements Connection {

    private final FyrQL engine;
    /** What closing the connection does to the engine: close it, release a share of it, or nothing. */
    private final Runnable owner;
    private final String url;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<FyrQLStatement> statements = ConcurrentHashMap.newKeySet();
    private final Properties clientInfo = new Properties();
    private final Session session;
    private volatile boolean readOnly;
    private SQLWarning warnings;
    private int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private int networkTimeout;

    /**
     * @param owner what closing the connection does to the engine, such as
     *        {@code engine::close}; it may throw a runtime exception if a
     *        source cannot be closed
     */
    FyrQLConnection(FyrQL engine, Runnable owner, String url) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.url = url;
        this.session = engine.newSession();
    }

    /**
     * Sets the settings of the session that the URL and the properties give
     * instead of the engine: {@code timeZone}, {@code defaultSource},
     * {@code defaultSchema} and {@code queryTimeout}. {@code RESET} returns
     * to the settings of the engine, as in a session of pgwire.
     *
     * @param settings the values by the names of their keys
     * @throws SQLException {@code 08001} if a value is invalid
     */
    void startWith(Map<String, String> settings) throws SQLException {
        String zone = settings.get(UrlSettings.TIME_ZONE);
        String timeout = settings.get(UrlSettings.QUERY_TIMEOUT);
        String source = settings.get(UrlSettings.DEFAULT_SOURCE);
        String schema = settings.get(UrlSettings.DEFAULT_SCHEMA);
        try {
            if (zone != null) {
                session.set("TimeZone", zone);
            }
            if (timeout != null) {
                Duration t = new ConfigOptions("the connection", Map.of(UrlSettings.QUERY_TIMEOUT, timeout),
                        Path.of("")).timeLimit(UrlSettings.QUERY_TIMEOUT).orElseThrow();
                session.set("statement_timeout", t.toMillis() + "ms");
            }
        } catch (SqlException | IllegalArgumentException e) {
            throw SqlStates.connectionFailure(e.getMessage(), e);
        }
        if (source != null && !engine.sources().contains(source)) {
            throw SqlStates.connectionFailure("The default source " + source + " is none of the sources "
                    + String.join(", ", engine.sources()), null);
        }
        if (source != null || schema != null) {
            SearchPath path = session.searchPath();
            session.use(new SearchPath(source != null ? source : path.defaultSource(),
                    schema != null ? schema : path.defaultSchema()));
        }
    }

    /** Returns the engine. */
    FyrQL engine() {
        return engine;
    }

    /** Returns the URL the connection was opened with. */
    String url() {
        return url;
    }

    /** Returns the session of the connection. */
    Session session() {
        return session;
    }

    /** Returns the search path of the queries of the connection. */
    SearchPath searchPath() {
        return session.searchPath();
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

    /** The kinds of statements a method of a statement runs. */
    enum Kind {
        /** A query or a statement that writes, as {@code execute} runs them. */
        ANY,
        /** A query, as {@code executeQuery} runs it. */
        QUERY,
        /** A statement that writes, as {@code executeUpdate} runs it. */
        UPDATE
    }

    /**
     * What a statement gives: the rows of a query, or the number of rows a
     * statement that writes wrote.
     *
     * @param rows the rows of a query, {@code null} for a statement that writes
     * @param count the rows written, {@code -1} for a query
     */
    record Outcome(BatchSource rows, long count) {
    }

    /**
     * Runs a statement that a context cancels: a query starts and gives its
     * rows, {@code EXPLAIN} gives the plan of its statement, after running
     * it for {@code EXPLAIN ANALYZE}, and a statement that writes runs to
     * its end and gives the rows it wrote. A statement of another kind than
     * the one expected fails with {@code 42809} before it runs, and one that
     * writes, also under {@code EXPLAIN ANALYZE}, fails with {@code 25006}
     * if the connection is read-only. A query canceled while it is planned
     * fails with its first batch.
     */
    Outcome run(String sql, QueryContext context, Kind expected) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            SqlStatement statement = session.statement(sql);
            boolean query = statement.isQuery();
            expect(expected, query ? Kind.QUERY : Kind.UPDATE);
            if (readOnly && statement.writes()) {
                throw SqlStates.create("The connection is read-only and runs no statement that writes",
                        SqlStates.READ_ONLY, null);
            }
            if (query) {
                return new Outcome(BatchSource.of(session.execute(statement, context), context,
                        statement.origins()), -1);
            }
            return new Outcome(null, session.update(statement, context));
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            lock.unlock();
        }
    }

    /** Fails with {@code 42809} if a statement is of another kind than the one a method runs. */
    private static void expect(Kind expected, Kind actual) throws SQLException {
        if (expected == Kind.QUERY && actual == Kind.UPDATE) {
            throw SqlStates.create("executeQuery runs queries; run a statement that writes with executeUpdate or"
                    + " execute", SqlStates.WRONG_OBJECT_TYPE, null);
        }
        if (expected == Kind.UPDATE && actual == Kind.QUERY) {
            throw SqlStates.create("A query returns rows, not a number of rows written; run it with executeQuery or"
                    + " execute", SqlStates.WRONG_OBJECT_TYPE, null);
        }
    }

    /**
     * Binds a statement without running it and returns the columns of its
     * result, or {@code null} for a statement that writes; {@code EXPLAIN}
     * has the column {@code plan}.
     */
    Schema describe(String sql) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            SqlStatement statement = session.statement(sql);
            return statement.isQuery() ? statement.schema() : null;
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            lock.unlock();
        }
    }

    /** Returns whether the connection is read-only, without the check of {@link #isReadOnly()}. */
    boolean readOnly() {
        return readOnly;
    }

    /**
     * Prepares a query with parameters without running it and returns the
     * types of its parameters, or {@code null} if the engine cannot prepare
     * it, e.g. for {@code ORDER BY ?}; for {@code EXPLAIN}, those of its statement
     * of the query.
     */
    List<DataType> parameterTypes(String sql) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            return session.prepare(sql).parameterTypes();
        } catch (SqlException e) {
            return null;
        } catch (RuntimeException e) {
            throw SqlStates.map(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Prepares a query with parameters without running it and returns the
     * columns of its result, or {@code null} for a statement that writes and
     * if the engine cannot prepare it, e.g. for {@code ORDER BY ?};
     * {@code EXPLAIN} has the column {@code plan}.
     */
    Schema preparedColumns(String sql) throws SQLException {
        lock.lock();
        try {
            checkOpen();
            PreparedQuery query = session.prepare(sql);
            return query.writes() ? null : query.schema();
        } catch (SqlException e) {
            return null;
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

    /**
     * Closes the statements of the connection, and the engine if the
     * connection owns it, or gives back its share of an engine that
     * connections share.
     */
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
        try {
            owner.run();
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

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new FyrQLDatabaseMetaData(this);
    }

    /**
     * Sets whether the connection refuses statements that write, with
     * {@code 25006}; it does not at first. Queries and {@code EXPLAIN} run
     * either way, but not {@code EXPLAIN ANALYZE} of a statement that writes.
     */
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        this.readOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
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
        session.use(new SearchPath(source, session.searchPath().defaultSchema()));
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
        return session.searchPath().defaultSource();
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
        SearchPath path = session.searchPath();
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
        session.use(new SearchPath(path.defaultSource(), name));
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return session.searchPath().defaultSchema();
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
