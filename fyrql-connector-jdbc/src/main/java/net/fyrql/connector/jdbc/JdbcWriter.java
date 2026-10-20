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

import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.render.Dialect;
import net.fyrql.sql.render.SqlRenderer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.BitSet;
import java.util.function.Consumer;

/**
 * Writes the tables of a JDBC source. A sink is one transaction over one
 * connection: {@code CREATE TABLE} with the type names of the dialect,
 * then {@code INSERT INTO t ("a", ...) VALUES (?, ...)} as a prepared
 * statement, its rows sent in batches of a number of rows, and the commit;
 * a sink closed before its commit rolls back. A database whose
 * {@code CREATE TABLE} commits at once, such as MySQL, keeps the empty
 * table after a rollback; the writer then drops it, as far as it can. The
 * catalog of the source reads the database again after a commit and after
 * a table is dropped.
 */
final class JdbcWriter implements Writer {

    private final String source;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final Dialect sql;
    private final JdbcCatalog catalog;
    private final int batchRows;
    /** Records a statement before it is sent. */
    private final Consumer<String> record;

    JdbcWriter(String source, Connections connections, JdbcDialect dialect, Dialect sql, JdbcCatalog catalog,
            int batchRows, Consumer<String> record) {
        this.source = source;
        this.connections = connections;
        this.dialect = dialect;
        this.sql = sql;
        this.catalog = catalog;
        this.batchRows = batchRows;
        this.record = record;
    }

    /**
     * Creates a table in the types the dialect writes, see
     * {@link Dialect#typeName}.
     *
     * @throws IllegalArgumentException if the dialect has no type for a
     *         column, such as a {@code STRUCT}, or {@code HUGEINT} in
     *         SQLite and in the generic dialect
     */
    @Override
    public Sink create(TableRef table, Schema schema) {
        for (int c = 0; c < schema.size(); c++) {
            try {
                sql.typeName(schema.type(c));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The JDBC source " + source + " cannot create " + table + ": "
                        + e.getMessage() + " (column " + schema.name(c) + ")");
            }
        }
        String name = catalog.qualifiedName(table);
        return open(table, name, schema, new BitSet(), renderer().createTable(name, schema));
    }

    @Override
    public Sink append(TableRef table) {
        return open(table, catalog.qualifiedName(table), catalog.schema(table), catalog.withTimeZone(table), null);
    }

    @Override
    public void drop(TableRef table) {
        String statement = "DROP TABLE " + catalog.qualifiedName(table);
        try {
            Transaction t = new Transaction();
            try {
                t.execute(statement);
                t.commit();
            } catch (SQLException e) {
                try {
                    t.rollback();
                } catch (SQLException again) {
                    e.addSuppressed(again);
                }
                throw e;
            } finally {
                t.end();
                catalog.refresh();
            }
        } catch (SQLException e) {
            throw fails(statement, e);
        }
    }

    private SqlRenderer renderer() {
        return new SqlRenderer(sql, catalog::qualifiedName);
    }

    /** Opens a sink, which runs the {@code CREATE TABLE} if there is one. */
    private Sink open(TableRef table, String name, Schema schema, BitSet withTimeZone, String create) {
        String insert = renderer().insertPrepared(name, schema);
        Transaction t;
        try {
            t = new Transaction();
        } catch (SQLException e) {
            throw fails(create != null ? create : insert, e);
        }
        RowSink sink = new RowSink(table, t, insert);
        try {
            if (create != null) {
                t.execute(create);
                sink.created = true;
            }
            record.accept(insert);
            sink.statement = t.connection.prepareStatement(insert);
            sink.setters = JdbcBinding.setters(dialect, sink.statement, schema.types(), withTimeZone);
            return sink;
        } catch (SQLException e) {
            JdbcSourceException failure = fails(sink.created || create == null ? insert : create, e);
            sink.abandon(failure);
            throw failure;
        } catch (RuntimeException e) {
            sink.abandon(e);
            throw e;
        }
    }

    private JdbcSourceException fails(String statement, SQLException e) {
        return new JdbcSourceException("The JDBC source " + source + " fails on " + statement + ": "
                + e.getMessage(), e);
    }

    /**
     * A connection whose auto-commit is off until {@link #end()}, which
     * gives it back.
     */
    private final class Transaction {
        final Connection connection;
        private final boolean autoCommitChanged;

        Transaction() throws SQLException {
            connection = connections.open();
            try {
                autoCommitChanged = connection.getAutoCommit();
                if (autoCommitChanged) {
                    connection.setAutoCommit(false);
                }
            } catch (SQLException | RuntimeException e) {
                connections.release(connection);
                throw e;
            }
        }

        void execute(String statement) throws SQLException {
            record.accept(statement);
            try (Statement s = connection.createStatement()) {
                s.execute(statement);
            }
        }

        void commit() throws SQLException {
            connection.commit();
        }

        void rollback() throws SQLException {
            connection.rollback();
        }

        /** Turns the auto-commit back on if it was on, and releases the connection. */
        void end() throws SQLException {
            SQLException failure = null;
            try {
                if (autoCommitChanged) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                failure = e;
            }
            try {
                connections.release(connection);
            } catch (SQLException e) {
                failure = failure == null ? e : failure;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** The sink of one write: the rows of the batches, sent in batches of {@code batchRows}. */
    private final class RowSink implements Sink {
        private final TableRef table;
        private final Transaction transaction;
        private final String insert;
        PreparedStatement statement;
        JdbcBinding.Setter[] setters;
        /** Whether the sink ran a {@code CREATE TABLE}. */
        boolean created;
        private long rows;
        /** The rows added to the statement since it was last sent. */
        private int pending;
        private boolean done;

        RowSink(TableRef table, Transaction transaction, String insert) {
            this.table = table;
            this.transaction = transaction;
            this.insert = insert;
        }

        @Override
        public void write(Batch batch) {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            int column = -1;
            try {
                for (int r = 0; r < batch.rowCount(); r++) {
                    for (column = 0; column < setters.length; column++) {
                        setters[column].set(statement, column + 1, batch.column(column).get(r));
                    }
                    column = -1;
                    statement.addBatch();
                    rows++;
                    if (++pending == batchRows) {
                        send();
                    }
                }
            } catch (SQLException e) {
                String where = column >= 0 ? " at row " + (rows + 1) + ", column " + (column + 1)
                        : " in the rows " + (rows - pending + 1) + " to " + rows;
                throw new JdbcSourceException("The JDBC source " + source + " fails on " + insert + where + ": "
                        + e.getMessage(), e);
            }
        }

        /** Sends the rows added since the last time. */
        private void send() throws SQLException {
            if (pending > 0) {
                statement.executeBatch();
                pending = 0;
            }
        }

        @Override
        public long commit() {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            try {
                send();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " fails on " + insert + " in the rows "
                        + (rows - pending + 1) + " to " + rows + ": " + e.getMessage(), e);
            }
            try {
                transaction.commit();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot commit the rows of " + table
                        + ": " + e.getMessage(), e);
            }
            done = true;
            try {
                release();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot release its connection after"
                        + " the rows of " + table + ": " + e.getMessage(), e);
            } finally {
                catalog.refresh();
            }
            return rows;
        }

        /** Rolls back unless committed; the first failure is thrown, see {@link #abandon}. */
        @Override
        public void close() {
            if (done) {
                return;
            }
            JdbcSourceException failure = new JdbcSourceException("The JDBC source " + source
                    + " cannot roll back the rows of " + table);
            abandon(failure);
            if (failure.getSuppressed().length > 0) {
                throw failure;
            }
        }

        /**
         * Rolls back, gives the connection back and drops a table that
         * stays after the rollback; a failure of it is added to the one
         * given. A table that cannot be dropped is left behind.
         */
        void abandon(Throwable failure) {
            done = true;
            try {
                transaction.rollback();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            try {
                release();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            if (created) {
                catalog.refresh();
                try {
                    if (catalog.tables(table.schema()).contains(table.table())) {
                        // The CREATE TABLE committed at once.
                        drop(table);
                    }
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
        }

        private void release() throws SQLException {
            try {
                if (statement != null) {
                    statement.close();
                }
            } finally {
                transaction.end();
            }
        }
    }
}
