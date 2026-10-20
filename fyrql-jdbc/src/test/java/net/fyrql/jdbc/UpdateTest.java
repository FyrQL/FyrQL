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
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.memory.MemoryConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statements that write, through statements, prepared statements and batches of the driver. */
class UpdateTest {

    @TempDir
    Path dir;

    private CancelTest.SlowConnector slow;
    private Connection c;

    @BeforeEach
    void open() {
        slow = new CancelTest.SlowConnector("slow", false);
        c = FyrQLDriver.connect(FyrQL.builder().register(MemoryConnector.builder("mem").build())
                .register(CsvConnector.builder("csv").build()).register(slow).defaultSource("mem").build(), true);
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void aStatementThatWritesHasAnUpdateCount() throws SQLException {
        try (Statement s = c.createStatement()) {
            assertFalse(s.execute("CREATE TABLE t (a INTEGER NOT NULL, s VARCHAR)"));
            assertNull(s.getResultSet());
            assertEquals(0, s.getUpdateCount());
            assertFalse(s.getMoreResults());
            assertEquals(-1, s.getUpdateCount(), "getMoreResults ends the update count");
            assertEquals(2, s.executeUpdate("INSERT INTO t VALUES (1, 'x'), (2, NULL)"));
            assertEquals(2, s.getUpdateCount());
            assertEquals(2L, s.getLargeUpdateCount());
            assertEquals(2L, s.executeLargeUpdate("CREATE TABLE mem.main.u AS SELECT a * 10 AS b FROM t"));
            assertTrue(s.execute("SELECT b FROM u ORDER BY b"));
            assertEquals(-1, s.getUpdateCount(), "a query has no update count");
            assertEquals(List.of("10", "20"), StatementTest.rows(s.getResultSet()));
            assertEquals(0, s.executeUpdate("DROP TABLE u"));
            assertEquals(0, s.executeUpdate("DROP TABLE IF EXISTS u"));
            assertEquals("42P01", assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM u"))
                    .getSQLState());
        }
    }

    @Test
    void aStatementOfTheOtherKindFailsBeforeItRuns() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t AS SELECT 1 AS a");
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("INSERT INTO t VALUES (2)"));
            assertEquals("42809", e.getSQLState());
            assertEquals("executeQuery runs queries; run a statement that writes with executeUpdate or execute",
                    e.getMessage());
            e = assertThrows(SQLException.class, () -> s.executeUpdate("SELECT a FROM t"));
            assertEquals("42809", e.getSQLState());
            assertEquals("42809", assertThrows(SQLException.class, () -> s.executeUpdate("EXPLAIN SELECT 1"))
                    .getSQLState());
            assertEquals(List.of("1"), StatementTest.rows(s.executeQuery("SELECT a FROM t")), "nothing was written");
            List<String> plan = StatementTest.rows(s.executeQuery("EXPLAIN INSERT INTO t VALUES (2)"));
            assertTrue(plan.get(0).startsWith("Insert into mem.main.t"), String.join("\n", plan));
        }
    }

    @Test
    void failuresHaveTheirStates() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (a INTEGER NOT NULL)");
            SQLException e = assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> s.executeUpdate("INSERT INTO t VALUES (1), (NULL)"));
            assertEquals("23502", e.getSQLState());
            assertTrue(e.getMessage().startsWith("NOT NULL constraint failed: t.a"), e.getMessage());
            assertEquals(List.of(), StatementTest.rows(s.executeQuery("SELECT a FROM t")), "the write is abandoned");
            e = assertThrows(SQLException.class, () -> s.executeUpdate("CREATE TABLE t AS SELECT 1 AS a"));
            assertEquals("42P07", e.getSQLState());
            assertTrue(e.getMessage().startsWith("Table with name t already exists"), e.getMessage());
            e = assertThrows(SQLException.class, () -> s.executeUpdate("CREATE TABLE slow.x AS SELECT 1 AS a"));
            assertTrue(e.getMessage().startsWith("Source slow does not write tables"), e.getMessage());
            assertEquals(-1, s.getUpdateCount(), "a failure leaves no update count");
        }
    }

    @Test
    void copyWritesAFile() throws Exception {
        Path file = dir.resolve("t.csv");
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (a INTEGER, s VARCHAR)");
            s.executeUpdate("INSERT INTO t VALUES (2, NULL), (1, 'x')");
            String path = file.toString().replace('\\', '/');
            assertFalse(s.execute("COPY (SELECT * FROM t ORDER BY a) TO '" + path + "'"));
            assertEquals(2, s.getUpdateCount());
            assertEquals("a,s\n1,x\n2,\n", Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void copyFromLoadsAFileWithTheTypesOfTheTable() throws Exception {
        Path file = dir.resolve("in.csv");
        Files.writeString(file, "s;a\n007;1\n;2\n", StandardCharsets.UTF_8);
        String path = file.toString().replace('\\', '/');
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (a INTEGER NOT NULL, s VARCHAR)");
            assertFalse(s.execute("COPY t (s, a) FROM '" + path + "' (DELIMITER ';')"));
            assertEquals(2, s.getUpdateCount());
            // The column s keeps the text of the file, which read_csv would take for a number.
            assertEquals(List.of("1 007", "2 null"), StatementTest.rows(s.executeQuery("SELECT * FROM t ORDER BY a")));
            List<String> plan = StatementTest.rows(s.executeQuery("EXPLAIN COPY t FROM '" + path + "'"));
            assertEquals("Copy from '" + path + "' into mem.main.t [format: csv]", plan.get(0));
        }
    }

    @Test
    void aReadOnlyConnectionRefusesWrites() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t AS SELECT 1 AS a");
            c.setReadOnly(true);
            assertTrue(c.getMetaData().isReadOnly());
            for (String sql : List.of("INSERT INTO t VALUES (2)", "DROP TABLE t", "CREATE TABLE u (a INTEGER)",
                    "COPY t TO '" + dir.resolve("t.csv").toString().replace('\\', '/') + "'")) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute(sql), sql);
                assertEquals("25006", e.getSQLState(), sql);
                assertEquals("The connection is read-only and runs no statement that writes", e.getMessage());
            }
            assertEquals(List.of("1"), StatementTest.rows(s.executeQuery("SELECT a FROM t")));
            assertFalse(StatementTest.rows(s.executeQuery("EXPLAIN INSERT INTO t VALUES (2)")).isEmpty());
            assertFalse(Files.exists(dir.resolve("t.csv")));
            c.setReadOnly(false);
            assertEquals(1, s.executeUpdate("INSERT INTO t VALUES (2)"));
        }
    }

    @Test
    void aBatchRunsItsStatementsOneAfterAnother() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (a INTEGER NOT NULL)");
            s.addBatch("INSERT INTO t VALUES (1)");
            s.addBatch("INSERT INTO t VALUES (2), (3)");
            s.addBatch("CREATE TABLE u AS SELECT a FROM t");
            assertArrayEquals(new int[] {1, 2, 3}, s.executeBatch());
            assertEquals(-1, s.getUpdateCount());
            assertArrayEquals(new long[0], s.executeLargeBatch(), "the batch is empty after it ran");
            s.addBatch("INSERT INTO t VALUES (4)");
            s.addBatch("SELECT a FROM t");
            s.addBatch("INSERT INTO t VALUES (5)");
            BatchUpdateException e = assertThrows(BatchUpdateException.class, s::executeBatch);
            assertEquals("42809", e.getSQLState());
            assertTrue(e.getMessage().startsWith("Statement 2 of the batch fails: A query returns rows"),
                    e.getMessage());
            assertArrayEquals(new int[] {1}, e.getUpdateCounts());
            assertArrayEquals(new long[] {1}, e.getLargeUpdateCounts());
            assertEquals(List.of("1", "2", "3", "4"), StatementTest.rows(s.executeQuery("SELECT a FROM t ORDER BY a")),
                    "the statements before the failure keep what they wrote, those after it do not run");
            s.addBatch("INSERT INTO t VALUES (6)");
            s.clearBatch();
            assertArrayEquals(new int[0], s.executeBatch());
            assertEquals("22023", assertThrows(SQLException.class, () -> s.addBatch(null)).getSQLState());
        }
    }

    @Test
    void aPreparedStatementWritesWithItsValues() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (a INTEGER NOT NULL, s VARCHAR, d DATE)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?, ?, ?)")) {
            ParameterMetaData pm = ps.getParameterMetaData();
            assertEquals(List.of(Types.INTEGER, Types.VARCHAR, Types.DATE), List.of(pm.getParameterType(1),
                    pm.getParameterType(2), pm.getParameterType(3)));
            ps.setInt(1, 1);
            ps.setString(2, "x");
            ps.setDate(3, java.sql.Date.valueOf("2024-02-29"));
            assertNull(ps.getMetaData(), "a statement that writes has no result set");
            assertEquals(1, ps.executeUpdate());
            assertFalse(ps.execute());
            assertEquals(1, ps.getUpdateCount());
            for (int i = 3; i <= 5; i++) {
                ps.setLong(1, i);
                ps.setNull(2, Types.VARCHAR);
                ps.setDate(3, java.sql.Date.valueOf("2024-03-0" + i));
                ps.addBatch();
            }
            assertArrayEquals(new int[] {1, 1, 1}, ps.executeBatch());
            ps.setInt(1, 6);
            ps.addBatch();
            ps.setNull(1, Types.INTEGER);
            ps.addBatch();
            BatchUpdateException e = assertThrows(BatchUpdateException.class, ps::executeBatch);
            assertEquals("23502", e.getSQLState());
            assertArrayEquals(new int[] {1}, e.getUpdateCounts());
            assertEquals("42809", assertThrows(SQLException.class, ps::executeQuery).getSQLState());
        }
        try (Statement s = c.createStatement()) {
            assertEquals(List.of("1 x 2024-02-29", "1 x 2024-02-29", "3 null 2024-03-03", "4 null 2024-03-04",
                    "5 null 2024-03-05", "6 null 2024-03-05"),
                    StatementTest.rows(s.executeQuery("SELECT a, s, d FROM t ORDER BY a")));
        }
    }

    @Test
    void theTimeoutAndCancelEndAWrite() throws Exception {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(1);
            long start = System.nanoTime();
            SQLException e = assertThrows(SQLTimeoutException.class,
                    () -> s.executeUpdate("CREATE TABLE copied AS SELECT * FROM slow.t"));
            assertEquals("HYT00", e.getSQLState());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8));
            assertEquals("42P01", assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM copied"))
                    .getSQLState(), "a canceled write leaves no table");
        }
        CancelTest.SlowConnector again = new CancelTest.SlowConnector("slow", false);
        try (Connection other = FyrQLDriver.connect(FyrQL.builder().register(MemoryConnector.builder("mem").build())
                .register(again).build(), true); Statement s = other.createStatement()) {
            Thread canceler = new Thread(() -> {
                try {
                    again.waiting.await(10, TimeUnit.SECONDS);
                    s.cancel();
                } catch (InterruptedException | SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            canceler.start();
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeUpdate("INSERT INTO mem.main.nothing SELECT * FROM slow.t"));
            assertEquals("42P01", e.getSQLState(), "the target is checked before the rows are read");
            e = assertThrows(SQLException.class,
                    () -> s.executeUpdate("CREATE TABLE mem.copied AS SELECT * FROM slow.t"));
            canceler.join();
            assertEquals("57014", e.getSQLState());
        }
    }

    @Test
    void withoutAConfigurationTablesGoToTheSourceMemory() throws SQLException {
        try (Connection d = new FyrQLDriver().connect("jdbc:fyrql:", null); Statement s = d.createStatement()) {
            assertEquals(3, s.executeUpdate("CREATE TABLE memory.t AS SELECT * FROM (VALUES (1), (2), (3)) v(a)"));
            assertEquals(List.of("6"), StatementTest.rows(s.executeQuery("SELECT sum(a) FROM memory.t")));
        }
    }
}
