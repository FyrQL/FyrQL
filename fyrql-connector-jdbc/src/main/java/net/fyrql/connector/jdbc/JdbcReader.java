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

package net.fyrql.connector.jdbc;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.jdbc.ColumnReaders.ColumnReader;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the result of one statement in batches. The connection, the
 * statement and the result are closed after the last row, on an error, or
 * when the reader is closed early; a connection whose auto-commit the
 * reader turned off is rolled back and gets it back before it is released.
 */
final class JdbcReader implements BatchReader {

    private final String source;
    private final String sql;
    private final Connections connections;
    private final Schema output;
    private final int batchSize;
    private Connection connection;
    private boolean autoCommitChanged;
    private Statement statement;
    private ResultSet result;
    private List<ColumnReader> readers;
    private long row;
    private boolean done;

    private JdbcReader(String source, String sql, Connections connections, Schema output, int batchSize) {
        this.source = source;
        this.sql = sql;
        this.connections = connections;
        this.output = output;
        this.batchSize = batchSize;
    }

    /** Sends the statement and returns a reader of its result. */
    static JdbcReader open(String source, String sql, Connections connections, JdbcDialect dialect, Schema output,
            int fetchSize, int batchSize) {
        JdbcReader reader = new JdbcReader(source, sql, connections, output, batchSize);
        try {
            reader.connection = connections.open();
            boolean autoCommit = reader.connection.getAutoCommit();
            reader.statement = dialect.prepare(reader.connection, fetchSize, connections.owned());
            reader.autoCommitChanged = autoCommit && !reader.connection.getAutoCommit();
            reader.result = reader.statement.executeQuery(sql);
            ResultSetMetaData md = reader.result.getMetaData();
            if (md.getColumnCount() != Math.max(1, output.size())) {
                throw new JdbcSourceException("The JDBC source " + source + " returns " + md.getColumnCount()
                        + " columns instead of " + output.size() + " for " + sql);
            }
            reader.readers = new ArrayList<>();
            for (int c = 0; c < output.size(); c++) {
                reader.readers.add(ColumnReaders.of(dialect, output.type(c), md.getColumnType(c + 1),
                        md.getColumnTypeName(c + 1)));
            }
            return reader;
        } catch (SQLException e) {
            reader.release();
            throw new JdbcSourceException("The JDBC source " + source + " fails on " + sql + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            reader.release();
            throw e;
        }
    }

    @Override
    public Batch next() {
        if (done) {
            return null;
        }
        List<Vector.Builder> builders = new ArrayList<>();
        for (int c = 0; c < output.size(); c++) {
            builders.add(Vectors.builder(output.type(c), batchSize));
        }
        int n = 0;
        int column = -1;
        try {
            while (n < batchSize && result.next()) {
                row++;
                for (column = 0; column < readers.size(); column++) {
                    builders.get(column).append(readers.get(column).read(result, column + 1));
                }
                column = -1;
                n++;
            }
        } catch (SQLException e) {
            release();
            throw new JdbcSourceException("The JDBC source " + source + " fails at row " + (row + 1) + " of " + sql
                    + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            release();
            String where = column >= 0 ? ", column " + output.name(column) : "";
            throw new JdbcSourceException("The JDBC source " + source + " returns a value that does not fit at row "
                    + row + where + " of " + sql + ": " + e.getMessage(), e);
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
            throw new JdbcSourceException("The JDBC source " + source + " cannot close the result of " + sql + ": "
                    + failure.getMessage(), failure);
        }
    }

    /** Closes what is open and returns the first failure, if any; the second call does nothing. */
    private SQLException release() {
        if (done) {
            return null;
        }
        done = true;
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
