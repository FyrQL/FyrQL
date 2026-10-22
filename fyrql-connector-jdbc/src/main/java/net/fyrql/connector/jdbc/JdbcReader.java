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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.SourceRequest;
import net.fyrql.connector.jdbc.ColumnReaders.ColumnReader;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.sql.render.BoundSql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Reads the result of one statement in batches. The statement is sent with
 * the first batch, so that {@link #cancel()} reaches it while the database
 * works on it. The connection, the statement and the result are closed
 * after the last row, on an error, or when the reader is closed early; a
 * connection whose auto-commit the reader turned off is rolled back and
 * gets it back before it is released. A statement with parameters is
 * prepared and its values are bound; one that carries a number of rows
 * gives the driver that number with {@code Statement.setMaxRows} and
 * stops after it all the same.
 */
final class JdbcReader implements BatchReader {

    private final String source;
    /** The text of the statement. */
    private final String sql;
    /** The statement as messages and the recorded statements show it, with its parameters. */
    private final String shown;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final Schema output;
    private final int batchSize;
    /** Guards the statement against being canceled while it is closed. */
    private final Object guard = new Object();
    private Connection connection;
    private boolean autoCommitChanged;
    private Statement statement;
    private ResultSet result;
    private List<ColumnReader> readers;
    private long row;
    /** The rows the statement may still return. */
    private long remaining;
    private boolean done;
    private boolean cancelled;

    private JdbcReader(String source, BoundSql bound, Connections connections, JdbcDialect dialect, Schema output,
            int batchSize) {
        this.source = source;
        this.sql = bound.sql();
        this.shown = bound.toString();
        this.remaining = bound.maxRows().orElse(Long.MAX_VALUE);
        this.connections = connections;
        this.dialect = dialect;
        this.output = output;
        this.batchSize = batchSize;
    }

    /** Prepares the statement on a connection and returns a reader that sends it with the first batch. */
    static JdbcReader open(String source, BoundSql bound, Connections connections, JdbcDialect dialect,
            Schema output, int fetchSize, int batchSize) {
        JdbcReader reader = new JdbcReader(source, bound, connections, dialect, output, batchSize);
        try {
            reader.connection = connections.open();
            boolean autoCommit = reader.connection.getAutoCommit();
            if (bound.parameters().isEmpty()) {
                reader.statement = dialect.statement(reader.connection, fetchSize, connections.owned());
            } else {
                PreparedStatement prepared = dialect.prepare(reader.connection, bound.sql(), fetchSize,
                        connections.owned());
                reader.statement = prepared;
                bind(prepared, bound.parameters());
            }
            if (bound.maxRows().isPresent() && bound.maxRows().getAsLong() > 0) {
                // 0 would be no limit to the driver; the reader stops after the rows anyway.
                reader.statement.setMaxRows((int) Math.min(bound.maxRows().getAsLong(), Integer.MAX_VALUE));
            }
            reader.autoCommitChanged = autoCommit && !reader.connection.getAutoCommit();
            return reader;
        } catch (SQLException e) {
            reader.release();
            throw new JdbcSourceException("The JDBC source " + source + " fails on " + bound + ": " + e.getMessage(),
                    e);
        } catch (RuntimeException e) {
            reader.release();
            throw e;
        }
    }

    /**
     * Binds the values of the parameters with the setters of JDBC 4.0,
     * dates and timestamps with a calendar in UTC.
     */
    static void bind(PreparedStatement statement, List<BoundSql.Parameter> parameters) throws SQLException {
        Calendar utc = ColumnReaders.utc();
        for (int i = 0; i < parameters.size(); i++) {
            BoundSql.Parameter p = parameters.get(i);
            JdbcBinding.bind(statement, i + 1, p.type(), p.value(), utc);
        }
    }

    /** Sends the statement and checks the columns of its result. */
    private void start() {
        try {
            synchronized (guard) {
                if (cancelled) {
                    throw new JdbcSourceException("The JDBC source " + source + " was asked to stop before it ran "
                            + shown);
                }
            }
            SourceRequest call = SourceRequest.start();
            try {
                result = statement instanceof PreparedStatement prepared ? prepared.executeQuery()
                        : statement.executeQuery(sql);
            } finally {
                call.done(source, "query", shown, 0, 0);
            }
            ResultSetMetaData md = result.getMetaData();
            if (md.getColumnCount() != Math.max(1, output.size())) {
                throw new JdbcSourceException("The JDBC source " + source + " returns " + md.getColumnCount()
                        + " columns instead of " + output.size() + " for " + shown);
            }
            List<ColumnReader> columns = new ArrayList<>();
            for (int c = 0; c < output.size(); c++) {
                columns.add(ColumnReaders.of(dialect, output.type(c), md.getColumnType(c + 1),
                        md.getColumnTypeName(c + 1)));
            }
            readers = columns;
        } catch (SQLException e) {
            release();
            throw new JdbcSourceException("The JDBC source " + source + " fails on " + shown + ": " + e.getMessage(),
                    e);
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    @Override
    public Batch next() {
        if (done) {
            return null;
        }
        if (readers == null) {
            start();
        }
        List<Vector.Builder> builders = new ArrayList<>();
        for (int c = 0; c < output.size(); c++) {
            builders.add(Vectors.builder(output.type(c), batchSize));
        }
        int n = 0;
        int column = -1;
        try {
            while (n < batchSize && remaining > 0 && result.next()) {
                row++;
                remaining--;
                for (column = 0; column < readers.size(); column++) {
                    builders.get(column).append(readers.get(column).read(result, column + 1));
                }
                column = -1;
                n++;
            }
        } catch (SQLException e) {
            release();
            throw new JdbcSourceException("The JDBC source " + source + " fails at row " + (row + 1) + " of "
                    + shown + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            release();
            String where = column >= 0 ? ", column " + output.name(column) : "";
            throw new JdbcSourceException("The JDBC source " + source + " returns a value that does not fit at row "
                    + row + where + " of " + shown + ": " + e.getMessage(), e);
        }
        if (n < batchSize) {
            close();
        }
        if (n == 0) {
            return null;
        }
        List<Vector> vectors = new ArrayList<>();
        for (Vector.Builder b : builders) {
            vectors.add(b.build());
        }
        return new Batch(output, n, vectors);
    }

    @Override
    public void close() {
        SQLException failure = release();
        if (failure != null) {
            throw new JdbcSourceException("The JDBC source " + source + " cannot close the result of " + shown
                    + ": " + failure.getMessage(), failure);
        }
    }

    /**
     * Cancels the statement while the database works on it or its result
     * is read, with {@link Statement#cancel()}, as far as the driver of the
     * database supports it; the reader then fails with the error of the
     * driver. A statement that has not been sent is not sent.
     */
    @Override
    public void cancel() {
        synchronized (guard) {
            if (done || cancelled) {
                return;
            }
            cancelled = true;
            try {
                statement.cancel();
            } catch (SQLException e) {
                // At best effort: the query ends at its next batch anyway.
            }
        }
    }

    /** Closes what is open and returns the first failure, if any; the second call does nothing. */
    private SQLException release() {
        synchronized (guard) {
            if (done) {
                return null;
            }
            done = true;
        }
        SQLException failure = null;
        try {
            if (result != null) {
                result.close();
            }
        } catch (SQLException e) {
            failure = e;
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException e) {
            failure = failure == null ? e : failure;
        }
        if (connection != null) {
            try {
                if (autoCommitChanged) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                failure = failure == null ? e : failure;
            }
            try {
                connections.release(connection);
            } catch (SQLException e) {
                failure = failure == null ? e : failure;
            }
        }
        return failure;
    }
}
