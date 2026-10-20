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

package net.fyrql.test.flight;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.flight.FlightSqlServer;
import net.fyrql.flight.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statements that write through the Flight SQL server and the Flight SQL
 * JDBC driver of Arrow. The driver prepares every statement and takes one
 * whose result has no columns for an update, which it sends with the rows
 * of its parameters; a batch of a prepared statement goes in one update,
 * whose count the driver gives as the only one.
 */
class FlightSqlJdbcWriteTest {

    private FyrQL engine;
    private FlightSqlServer server;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        engine = FyrQL.builder().register(MemoryConnector.builder("mem").build()).defaultSource("mem").build();
        server = FlightSqlServer.builder(engine).config(ServerConfig.defaults().withPort(0)).build().start();
        connection = DriverManager.getConnection("jdbc:arrow-flight-sql://localhost:" + server.port()
                + "/?useEncryption=false");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
            if (server != null) {
                server.close();
            }
        } finally {
            engine.close();
        }
    }

    private List<String> rows(String sql) {
        return engine.rows(sql).stream().map(Arrays::toString).toList();
    }

    @Test
    void aStatementWritesAndGivesTheCount() throws SQLException {
        try (Statement s = connection.createStatement()) {
            assertEquals(0, s.executeUpdate("CREATE TABLE t (k INTEGER NOT NULL, v VARCHAR)"));
            assertEquals(2, s.executeUpdate("INSERT INTO t VALUES (1, 'a'), (2, NULL)"));
            assertFalse(s.execute("INSERT INTO t SELECT k + 10, v FROM t"));
            assertEquals(2, s.getUpdateCount());
            assertEquals(4, s.executeUpdate("CREATE TABLE u AS SELECT * FROM t"));
            assertEquals(0, s.executeUpdate("DROP TABLE u"));
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeUpdate("INSERT INTO t VALUES (NULL, 'x')"));
            assertTrue(e.getMessage().contains("NOT NULL constraint failed: t.k"), e.getMessage());
        }
        assertEquals(List.of("[1, a]", "[2, null]", "[11, a]", "[12, null]"), rows("SELECT * FROM t ORDER BY k"));
    }

    @Test
    void aPreparedStatementWritesItsRows() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE t (k INTEGER NOT NULL, v VARCHAR)");
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "a");
            assertEquals(1, ps.executeUpdate());
            for (int k = 2; k <= 4; k++) {
                ps.setInt(1, k);
                ps.setString(2, k == 3 ? null : "r" + k);
                ps.addBatch();
            }
            assertEquals(3, Arrays.stream(ps.executeBatch()).sum());
        }
        assertEquals(List.of("[1, a]", "[2, r2]", "[3, null]", "[4, r4]"), rows("SELECT * FROM t ORDER BY k"));
    }
}
