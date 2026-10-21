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

package net.fyrql.connector.iceberg;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DuckDB with its extension {@code iceberg} as the second reference. The
 * extension is only loaded from the extension cache, never installed: once
 * {@code INSTALL iceberg} in any DuckDB 1.5.6 makes these tests run, without
 * it they are skipped.
 *
 * <p>DuckDB 1.5.6 crashes the JVM on manifests compressed with zstandard;
 * such tables must not be read here.
 */
public final class DuckDbIceberg implements AutoCloseable {

    private final Connection connection;

    private DuckDbIceberg(Connection connection) {
        this.connection = connection;
    }

    /** Opens DuckDB and loads the extension, or returns nothing if it is not in the cache. */
    public static Optional<DuckDbIceberg> open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET autoinstall_known_extensions = false");
            statement.execute("LOAD iceberg");
        } catch (SQLException e) {
            connection.close();
            return Optional.empty();
        }
        return Optional.of(new DuckDbIceberg(connection));
    }

    /** Returns the text of a location for SQL. */
    public static String literal(Path location) {
        return "'" + location.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "'";
    }

    /** Runs a query and returns its rows as lists of the values of the DuckDB driver. */
    public List<List<Object>> query(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    /** Returns the number of rows of the current snapshot of a table, given by its metadata file. */
    public long count(Path metadata) throws SQLException {
        return ((Number) query("SELECT count(*) FROM iceberg_scan(" + literal(metadata) + ")").get(0).get(0))
                .longValue();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
