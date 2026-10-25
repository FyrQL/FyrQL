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
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SQLSTATEs and positions of errors, as the simple and the extended protocol report them. */
class ErrorsTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static PSQLException error(Connection c, String sql) {
        return assertThrows(PSQLException.class, () -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }, sql);
    }

    @Test
    void errorsHaveTheStatesOfPostgreSql() throws SQLException {
        Map<String, String> cases = Map.of(
                "SELECT FROM WHERE", "42601",
                "SELECT * FROM nope", "42P01",
                "SELECT nope FROM tiny", "42703",
                "SELECT CAST('x' AS INTEGER)", "22018",
                "SELECT nope(1)", "42883",
                "LISTEN x", "0A000",
                "COPY tiny TO STDOUT", "0A000",
                "SELECT 'unterminated", "42601");
        for (String parameters : new String[] {"", "?preferQueryMode=simple"}) {
            Connection c = server.connect("mem", parameters);
            for (Map.Entry<String, String> e : cases.entrySet()) {
                assertEquals(e.getValue(), error(c, e.getKey()).getSQLState(), e.getKey() + parameters);
            }
        }
    }

    @Test
    void anErrorInTheTextHasItsPosition() throws SQLException {
        Connection c = server.connect();
        PSQLException e = error(c, "SELECT k,\n  nope\nFROM tiny");
        assertEquals(13, e.getServerErrorMessage().getPosition(), e.getMessage());
        assertTrue(e.getServerErrorMessage().getMessage().contains("nope"), e.getMessage());
        try (PreparedStatement ps = c.prepareStatement("SELECT k FROM tiny WHERE nope = ?")) {
            ps.setInt(1, 1);
            PSQLException p = assertThrows(PSQLException.class, ps::executeQuery);
            assertEquals("42703", p.getSQLState());
        }
        // The connection goes on after errors.
        assertEquals("2", TestServer.value(c, "SELECT count(*) FROM tiny"));
    }
}
