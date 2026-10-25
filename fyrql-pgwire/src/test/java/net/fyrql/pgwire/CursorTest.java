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

package net.fyrql.pgwire;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static net.fyrql.pgwire.TestServer.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows in parts: portals that the client reads with a fetch size, and cursors of SQL. */
class CursorTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void aFetchSizeReadsAPortalInParts() throws SQLException {
        Connection c = server.connect();
        c.setAutoCommit(false);
        for (String sql : List.of("SELECT n FROM numbers ORDER BY n", "SELECT n FROM numbers WHERE n < ? ORDER BY n")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(7);
                if (sql.contains("?")) {
                    ps.setLong(1, 500);
                }
                long expected = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertEquals(expected++, rs.getLong(1));
                    }
                }
                assertEquals(sql.contains("?") ? 500 : TestServer.NUMBERS, expected);
            }
        }
        c.commit();
    }

    @Test
    void cursorsOfSql() throws SQLException {
        Connection c = server.connect();
        try (Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> s.execute("DECLARE c CURSOR FOR SELECT n FROM numbers"));
            assertEquals("25P01", e.getSQLState());
            s.execute("BEGIN");
            s.execute("DECLARE c NO SCROLL CURSOR FOR SELECT n FROM numbers ORDER BY n");
            assertEquals(List.of(List.of("0"), List.of("1"), List.of("2")), query(c, "FETCH 3 FROM c"));
            assertEquals(List.of(List.of("3"), List.of("4")), query(c, "FETCH FORWARD 2 IN c"));
            assertFalse(s.execute("MOVE 5 c"));
            assertEquals(5, s.getUpdateCount());
            assertEquals(List.of(List.of("10")), query(c, "FETCH NEXT FROM c"));
            assertEquals(TestServer.NUMBERS - 11, query(c, "FETCH ALL FROM c").size());
            assertTrue(query(c, "FETCH c").isEmpty());
            e = assertThrows(SQLException.class, () -> s.execute("FETCH BACKWARD 1 FROM c"));
            assertEquals("0A000", e.getSQLState());
            s.execute("ROLLBACK");
            s.execute("DECLARE h CURSOR WITH HOLD FOR SELECT k FROM tiny ORDER BY k");
            assertEquals(List.of(List.of("1")), query(c, "FETCH 1 FROM h"));
            s.execute("CLOSE h");
            e = assertThrows(SQLException.class, () -> s.execute("FETCH 1 FROM h"));
            assertEquals("34000", e.getSQLState());
        }
    }
}
