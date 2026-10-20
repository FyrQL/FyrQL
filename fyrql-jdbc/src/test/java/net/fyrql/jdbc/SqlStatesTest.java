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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SQLState of each kind of error, from a query that makes it. */
class SqlStatesTest {

    /** Two sources with a table {@code t}: an unqualified {@code t} is ambiguous. */
    private ConnectionTest.CountingConnector shop;
    private Connection c;

    @BeforeEach
    void open() {
        shop = new ConnectionTest.CountingConnector("shop", 10, 3);
        FyrQL engine = FyrQL.builder().register(shop).register(new ConnectionTest.CountingConnector("crm")).build();
        c = FyrQLDriver.connect(engine, true);
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void errorsOfTheQueryText() throws SQLException {
        List<String[]> cases = List.of(
                new String[] {"42601", "SELEC 1", "Syntax error"},
                new String[] {"42601", "SELECT 'abc", "Unterminated"},
                new String[] {"42601", "  ", "Empty query"},
                new String[] {"42601", "SELECT 1; SELECT 2", "Only one statement is supported"},
                new String[] {"42P01", "SELECT * FROM shop.nope", "Table with name shop.nope does not exist"},
                new String[] {"42P01", "SELECT * FROM erp.main.t", "Table with name erp.main.t does not exist"},
                new String[] {"42P01", "SELECT * FROM read_nothing('x')", "Table function with name read_nothing"},
                new String[] {"42P01", "SELECT x.id FROM shop.t", "Referenced table \"x\" not found"},
                new String[] {"42712", "SELECT * FROM t", "Table name t is ambiguous"},
                new String[] {"42703", "SELECT price FROM shop.t", "Referenced column \"price\" not found"},
                new String[] {"42702", "SELECT id FROM shop.t a, crm.t b", "Ambiguous reference to column name"},
                new String[] {"42803", "SELECT id, count(*) FROM shop.t", "column \"id\" must appear in the GROUP BY"},
                new String[] {"42883", "SELECT nothing(1)", "Function with name nothing does not exist"},
                new String[] {"42883", "SELECT substring(1)", "No function matches"},
                new String[] {"0A000", "DELETE FROM shop.t", "Only queries, COPY, CREATE TABLE, INSERT INTO and"
                        + " DROP TABLE are supported, not DELETE"},
                new String[] {"0A000", "WITH RECURSIVE r AS (SELECT 1) SELECT * FROM r",
                        "WITH RECURSIVE is not supported"});
        try (Statement s = c.createStatement()) {
            for (String[] k : cases) {
                SQLException e = assertThrows(SQLException.class, () -> s.executeQuery(k[1]), k[1]);
                assertEquals(k[0], e.getSQLState(), k[1] + ": " + e.getMessage());
                assertTrue(e.getMessage().contains(k[2]), k[1] + ": " + e.getMessage());
                Class<?> type = switch (k[0].substring(0, 2)) {
                    case "42" -> SQLSyntaxErrorException.class;
                    case "0A" -> SQLFeatureNotSupportedException.class;
                    case "22" -> SQLDataException.class;
                    default -> SQLException.class;
                };
                assertInstanceOf(type, e, k[1]);
                assertInstanceOf(RuntimeException.class, e.getCause(), "the error of FyrQL stays the cause");
            }
        }
    }

    @Test
    void errorsWhileTheQueryRuns() throws SQLException {
        assertRunFails("22003", "SELECT id + 2147483647 FROM shop.t", "Out of range for INTEGER: 2147483648 in Project");
        assertRunFails("22012", "SELECT log(1.0, id) FROM shop.t", "by zero");
        assertRunFails("22018", "SELECT CAST(name AS INTEGER) FROM shop.t", "");
        assertRunFails("22018", "SELECT CAST('abc' AS INTEGER) FROM shop.t", "");
        shop.failure = new UncheckedIOException(new IOException("The disk is gone"));
        assertRunFails("58030", "SELECT * FROM shop.t", "The disk is gone");
        shop.failure = new IllegalStateException("Something odd");
        assertRunFails("HY000", "SELECT * FROM shop.t", "Something odd");
        shop.failure = null;
        try (Connection small = FyrQLDriver.connect(FyrQL.builder().register(new ConnectionTest.CountingConnector(
                "big", 1000, 100)).memoryBudget(1000).build(), true); Statement s = small.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> {
                try (ResultSet rs = s.executeQuery("SELECT * FROM big.t ORDER BY name")) {
                    while (rs.next()) {
                        rs.getInt(1);
                    }
                }
            });
            assertEquals("53200", e.getSQLState(), e.getMessage());
            assertTrue(e.getMessage().startsWith("Memory budget of 1000 bytes exceeded"), e.getMessage());
        }
    }

    /** Runs a query to its end, which must fail with the state, when it is executed or while it is read. */
    private void assertRunFails(String state, String sql, String message) throws SQLException {
        int closes = shop.readerCloses.get();
        try (Statement s = c.createStatement()) {
            ResultSet[] rs = new ResultSet[1];
            SQLException e = assertThrows(SQLException.class, () -> {
                rs[0] = s.executeQuery(sql);
                while (rs[0].next()) {
                    rs[0].getObject(1);
                }
            }, sql);
            assertEquals(state, e.getSQLState(), sql + ": " + e.getMessage());
            assertTrue(e.getMessage().contains(message), sql + ": " + e.getMessage());
            assertTrue(shop.readerCloses.get() > closes, "a failure releases the source: " + sql);
            assertTrue(rs[0] == null || !rs[0].next(), "after a failure the result set is at its end");
        }
    }

    @Test
    void theStateComesFromTheFirstExceptionInTheChainThatTellsIt() {
        assertEquals("22003", SqlStates.state(new RuntimeException("x", new ArithmeticException("Out of range"))));
        assertEquals("22012", SqlStates.state(new ArithmeticException("divison by zero in based logarithm")));
        assertEquals("58030", SqlStates.state(new RuntimeException(new UncheckedIOException(new IOException()))));
        assertEquals("HY000", SqlStates.state(new IllegalStateException()));
        assertEquals("42000", SqlStates.stateOf("Subquery returns 2 columns - expected 1"));
        assertEquals("54001", SqlStates.stateOf("Query is nested too deeply"));
        assertEquals("22018", SqlStates.stateOf("Could not convert string 'a' to INT32"));
        assertEquals("0A000", SqlStates.stateOf("Not supported: a subquery here"));
        assertEquals("0A000", SqlStates.stateOf("UNNEST not supported here"));
        SQLException e = SqlStates.map(new IllegalStateException());
        assertEquals("java.lang.IllegalStateException", e.getMessage());
        IllegalStateException cause = new IllegalStateException("odd");
        assertSame(cause, SqlStates.map(cause).getCause());
    }
}
