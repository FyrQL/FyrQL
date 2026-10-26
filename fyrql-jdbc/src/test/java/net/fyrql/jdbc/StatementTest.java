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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statements and the movement through their result sets. */
class StatementTest {

    /** Ten rows in batches of three. */
    private ConnectionTest.CountingConnector shop;
    private Connection c;

    @BeforeEach
    void open() {
        shop = new ConnectionTest.CountingConnector("shop", 10, 3);
        c = FyrQLDriver.connect(FyrQL.builder().register(shop).defaultSource("shop").build(), true);
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void executeGivesOneResultSetAndNoUpdateCount() throws SQLException {
        try (Statement s = c.createStatement()) {
            assertTrue(s.execute("SELECT id, name FROM t WHERE id > 8 ORDER BY id"));
            ResultSet rs = s.getResultSet();
            assertSame(s, rs.getStatement());
            assertEquals(-1, s.getUpdateCount());
            assertEquals(-1, s.getLargeUpdateCount());
            assertEquals(List.of("9 r9", "10 r10"), rows(rs));
            assertFalse(s.getMoreResults());
            assertTrue(rs.isClosed(), "getMoreResults closes the current result set");
            assertNull(s.getResultSet());
            assertEquals(-1, s.getUpdateCount());
            ResultSet first = s.executeQuery("SELECT 1");
            ResultSet second = s.executeQuery("SELECT 2");
            assertTrue(first.isClosed(), "a new run closes the result set before");
            assertFalse(second.isClosed());
            assertFalse(s.getMoreResults(Statement.KEEP_CURRENT_RESULT));
            assertFalse(second.isClosed());
            assertNull(s.getResultSet());
            assertFalse(s.getMoreResults(Statement.CLOSE_ALL_RESULTS));
            assertTrue(second.isClosed());
        }
    }

    @Test
    void statementsFailWithTheMessageOfFyrQL() throws SQLException {
        try (Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO t VALUES (1, 'x')"));
            assertEquals("42000", e.getSQLState());
            assertTrue(e.getMessage().startsWith("Source shop does not write tables"), e.getMessage());
            e = assertThrows(SQLFeatureNotSupportedException.class, () -> s.execute("DELETE FROM t"));
            assertEquals("0A000", e.getSQLState());
            assertTrue(e.getMessage().startsWith("Only queries, COPY, CREATE TABLE, CREATE VIEW, INSERT INTO, DROP"
                    + " TABLE and DROP VIEW are supported, not DELETE"), e.getMessage());
            e = assertThrows(SQLException.class, () -> s.executeLargeUpdate("SELECT 1"));
            assertEquals("42809", e.getSQLState());
            assertEquals("A query returns rows, not a number of rows written; run it with executeQuery or execute",
                    e.getMessage());
            assertEquals("42601", assertThrows(SQLException.class, () -> s.executeUpdate("SELEC 1")).getSQLState());
            for (ThrowingCall call : List.<ThrowingCall>of(() -> s.execute("SELECT 1", Statement.RETURN_GENERATED_KEYS),
                    () -> s.execute("SELECT 1", new int[] {1}), () -> s.executeUpdate("SELECT 1", new int[] {1}),
                    () -> s.executeLargeUpdate("SELECT 1", Statement.RETURN_GENERATED_KEYS),
                    () -> s.setCursorName("c"))) {
                assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class, call::run).getSQLState());
            }
            assertTrue(s.execute("SELECT 1", Statement.NO_GENERATED_KEYS));
            try (ResultSet keys = s.getGeneratedKeys()) {
                assertEquals(0, keys.getMetaData().getColumnCount());
                assertFalse(keys.next());
            }
        }
    }

    @Test
    void oneStatementAtATime() throws SQLException {
        try (Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1; SELECT 2"));
            assertEquals("42601", e.getSQLState());
            assertTrue(e.getMessage().startsWith("Only one statement is supported"), e.getMessage());
            assertInstanceOf(net.fyrql.sql.SqlException.class, e.getCause());
            assertEquals(List.of("1"), rows(s.executeQuery("SELECT 1;")));
        }
    }

    @Test
    void maxRowsLimitsTheResultAndReleasesTheSourceEarly() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.setMaxRows(4);
            assertEquals(4, s.getMaxRows());
            ResultSet rs = s.executeQuery("SELECT id FROM t");
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getInt(1));
                assertEquals(ids.size() == 4, rs.isLast());
            }
            assertEquals(List.of(1, 2, 3, 4), ids);
            assertEquals(1, shop.readerCloses.get(), "the source is released before the result set is closed");
            assertFalse(rs.isClosed());
            rs.close();
            assertEquals(1, shop.readerCloses.get());
            s.setLargeMaxRows(0);
            assertEquals(10, rows(s.executeQuery("SELECT id FROM t")).size());
            assertEquals("22023", assertThrows(SQLException.class, () -> s.setMaxRows(-1)).getSQLState());
        }
    }

    @Test
    void theCursorMovesForwardOverBatches() throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM t")) {
            assertEquals(0, rs.getRow());
            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.isFirst());
            assertEquals("24000", assertThrows(SQLException.class, () -> rs.getInt(1)).getSQLState());
            int n = 0;
            while (rs.next()) {
                n++;
                assertEquals(n, rs.getRow());
                assertEquals(n, rs.getInt("ID"));
                assertEquals(n == 1, rs.isFirst());
                assertEquals(n == 10, rs.isLast());
                assertFalse(rs.isBeforeFirst());
                assertFalse(rs.isAfterLast());
            }
            assertEquals(10, n);
            assertTrue(rs.isAfterLast());
            assertEquals(0, rs.getRow());
            assertFalse(rs.next());
            assertEquals("24000", assertThrows(SQLException.class, () -> rs.getInt(1)).getSQLState());
            for (ThrowingCall call : List.<ThrowingCall>of(rs::previous, rs::first, rs::last, rs::beforeFirst,
                    rs::afterLast, () -> rs.absolute(1), () -> rs.relative(1), () -> rs.updateInt(1, 5),
                    rs::insertRow, rs::deleteRow, () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE))) {
                assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class, call::run).getSQLState());
            }
            rs.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM t WHERE id > 99")) {
            assertFalse(rs.isBeforeFirst(), "an empty result is never before its first row");
            assertFalse(rs.next());
            assertFalse(rs.isAfterLast(), "nor after its last");
        }
    }

    @Test
    void columnsAreFoundByLabelIgnoringCase() throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT id AS Total, name, id AS total FROM t WHERE id = 3")) {
            assertTrue(rs.next());
            assertEquals(1, rs.findColumn("TOTAL"), "the first column of a label wins");
            assertEquals("r3", rs.getString("NAME"));
            SQLException e = assertThrows(SQLException.class, () -> rs.findColumn("price"));
            assertEquals("42703", e.getSQLState());
            assertEquals("The result has no column price; the columns are Total, name, total", e.getMessage());
            assertEquals("22023", assertThrows(SQLException.class, () -> rs.getInt(4)).getSQLState());
            assertEquals("22023", assertThrows(SQLException.class, () -> rs.getInt(0)).getSQLState());
        }
    }

    @Test
    void closingReleasesWhatIsOpen() throws SQLException {
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM t");
        assertTrue(rs.next());
        assertEquals(0, shop.readerCloses.get());
        s.close();
        assertTrue(rs.isClosed(), "closing the statement closes its result set");
        assertEquals(1, shop.readerCloses.get());
        assertEquals("HY010", assertThrows(SQLException.class, rs::next).getSQLState());
        SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1"));
        assertEquals("HY010", e.getSQLState());
        assertEquals("The statement is closed", e.getMessage());
        s.close();

        Statement t = c.createStatement();
        t.closeOnCompletion();
        ResultSet r = t.executeQuery("SELECT id FROM t");
        t.executeQuery("SELECT id FROM t").close();
        assertTrue(r.isClosed());
        assertTrue(t.isClosed(), "the statement closes with its last result set");

        Statement u = c.createStatement();
        ResultSet open = u.executeQuery("SELECT id FROM t");
        assertTrue(open.next());
        c.close();
        assertTrue(u.isClosed(), "closing the connection closes its statements");
        assertTrue(open.isClosed());
        assertEquals(4, shop.readerCloses.get(), "a query opens its source while it is executed");
        assertEquals(1, shop.closes.get());
    }

    @Test
    void explainGivesThePlan() throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("  explain\nSELECT name FROM t WHERE id = 1")) {
            assertEquals("plan", rs.getMetaData().getColumnName(1));
            assertEquals("VARCHAR", rs.getMetaData().getColumnTypeName(1));
            List<String> lines = new ArrayList<>();
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
            assertEquals(List.of(c.unwrap(FyrQL.class).explain("SELECT name FROM t WHERE id = 1").split("\n")),
                    lines);
            assertTrue(lines.size() > 1, lines.toString());
        }
    }

    @Test
    void settersCheckTheirArguments() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.setFetchSize(100);
            assertEquals(100, s.getFetchSize());
            s.setQueryTimeout(5);
            assertEquals(5, s.getQueryTimeout());
            s.setMaxFieldSize(10);
            assertEquals(10, s.getMaxFieldSize());
            s.setPoolable(true);
            assertTrue(s.isPoolable());
            s.setEscapeProcessing(false);
            s.setFetchDirection(ResultSet.FETCH_UNKNOWN);
            assertEquals(ResultSet.FETCH_FORWARD, s.getFetchDirection());
            assertSame(c, s.getConnection());
            assertEquals(ResultSet.CONCUR_READ_ONLY, s.getResultSetConcurrency());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, s.getResultSetHoldability());
            for (ThrowingCall call : List.<ThrowingCall>of(() -> s.setFetchSize(-1), () -> s.setQueryTimeout(-1),
                    () -> s.setMaxFieldSize(-1), () -> s.setFetchDirection(42), () -> s.getMoreResults(42),
                    () -> s.executeQuery(null))) {
                assertEquals("22023", assertThrows(SQLException.class, call::run).getSQLState());
            }
            s.cancel();
        }
        try (Statement s = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT); ResultSet rs = s.executeQuery("SELECT 1")) {
            assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, rs.getHoldability());
        }
    }

    @Test
    void theSettingsOfTheSessionAreStatements() throws SQLException {
        try (Statement s = c.createStatement()) {
            assertFalse(s.execute("SET TimeZone = 'Europe/Berlin'"));
            assertEquals(0, s.getUpdateCount());
            assertTrue(s.execute("SHOW TimeZone"));
            assertEquals("TimeZone", s.getResultSet().getMetaData().getColumnName(1));
            assertEquals(List.of("Europe/Berlin"), rows(s.getResultSet()));
            assertEquals(0, s.executeUpdate("RESET TimeZone"));
            ResultSet all = s.executeQuery("SHOW ALL");
            assertEquals("name", all.getMetaData().getColumnName(1));
            assertEquals("setting", all.getMetaData().getColumnName(2));
            assertEquals(3, rows(all).size());
            assertEquals(0, s.executeUpdate("USE shop"));
            assertEquals("shop", c.getCatalog());
            assertEquals("42809", assertThrows(SQLException.class, () -> s.executeQuery("SET TimeZone = 'UTC'"))
                    .getSQLState());
            assertEquals("42809", assertThrows(SQLException.class, () -> s.executeUpdate("SHOW ALL")).getSQLState());
        }
    }

    /** Returns the rows as text, the values separated by a space; closes the result set. */
    static List<String> rows(ResultSet rs) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (rs) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    b.append(i > 1 ? " " : "").append(rs.getString(i));
                }
                rows.add(b.toString());
            }
        }
        return rows;
    }

    /** A call of a method that may throw. */
    interface ThrowingCall {
        void run() throws SQLException;
    }
}
