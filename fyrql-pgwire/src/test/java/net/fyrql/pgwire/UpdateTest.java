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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static net.fyrql.pgwire.TestServer.query;
import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statements that write, their counts, batches, several statements in one text, and the empty statement. */
class UpdateTest {

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
    void statementsThatWriteGiveTheirCounts(@TempDir Path dir) throws Exception {
        Connection c = server.connect();
        try (Statement s = c.createStatement()) {
            assertEquals(0, s.executeUpdate("CREATE TABLE w (a INTEGER, b VARCHAR)"));
            assertEquals(2, s.executeUpdate("INSERT INTO w VALUES (1, 'x'), (2, 'y')"));
            assertEquals(2, s.executeUpdate("CREATE TABLE w2 AS SELECT * FROM w"));
            Path file = dir.resolve("w.csv");
            assertEquals(2, s.executeUpdate("COPY w TO '" + file.toString().replace('\\', '/') + "'"));
            assertTrue(Files.size(file) > 0);
            s.executeUpdate("DROP TABLE w2");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO w VALUES (?, ?)")) {
            for (int i = 3; i < 6; i++) {
                ps.setInt(1, i);
                ps.setString(2, "v" + i);
                ps.addBatch();
            }
            assertArrayEquals(new int[] {1, 1, 1}, ps.executeBatch());
        }
        assertEquals("5", value(c, "SELECT count(*) FROM w"));
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE w");
        }
    }

    @Test
    void severalStatementsInOneText() throws SQLException {
        Connection c = server.connect("mem", "?preferQueryMode=simple");
        try (Statement s = c.createStatement()) {
            assertTrue(s.execute("SELECT 1; SELECT 'a;b' AS \"x;y\" -- c;\n; SET myapp.x = 2; SHOW myapp.x"));
            assertEquals(List.of(List.of("1")), TestServer.strings(s.getResultSet()));
            assertTrue(s.getMoreResults());
            assertEquals(List.of(List.of("a;b")), TestServer.strings(s.getResultSet()));
            assertFalse(s.getMoreResults());
            assertEquals(0, s.getUpdateCount());
            assertTrue(s.getMoreResults());
            assertEquals(List.of(List.of("2")), TestServer.strings(s.getResultSet()));
            // An empty statement and one of only a comment.
            s.execute("");
            s.execute("-- nothing");
            assertEquals(List.of(List.of("$$;$$")), query(c, "SELECT '$$;$$'"));
            assertEquals(List.of(List.of("a;b")), query(c, "SELECT $tag$a;b$tag$"));
        }
    }
}
