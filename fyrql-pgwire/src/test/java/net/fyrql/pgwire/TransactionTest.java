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
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transactions as the server reports them: FyrQL has none, but the state follows PostgreSQL. */
class TransactionTest {

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
    void afterAnErrorOnlyTheEndOfTheTransactionRuns() throws SQLException {
        for (String parameters : new String[] {"", "?preferQueryMode=simple"}) {
            Connection c = server.connect("mem", parameters);
            c.setAutoCommit(false);
            assertEquals("1", value(c, "SELECT 1"));
            try (Statement s = c.createStatement()) {
                assertThrows(SQLException.class, () -> s.executeQuery("SELECT nope FROM tiny"));
                SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1"));
                assertEquals("25P02", e.getSQLState(), parameters);
            }
            c.rollback();
            assertEquals("2", value(c, "SELECT count(*) FROM tiny"));
            c.setAutoCommit(true);
        }
    }

    @Test
    void theStatementsOfTransactions() throws SQLException {
        Connection c = server.connect();
        try (Statement s = c.createStatement()) {
            s.execute("BEGIN READ ONLY");
            s.execute("BEGIN");
            SQLWarning w = s.getWarnings();
            assertNotNull(w);
            assertEquals("25001", w.getSQLState());
            s.execute("SAVEPOINT a");
            s.execute("RELEASE SAVEPOINT a");
            s.execute("CREATE TABLE written (a INTEGER)");
            s.execute("INSERT INTO written VALUES (1)");
            s.clearWarnings();
            s.execute("ROLLBACK");
            w = s.getWarnings();
            assertNotNull(w);
            assertTrue(w.getMessage().contains("FyrQL has no transactions"), w.getMessage());
            assertEquals("1", value(c, "SELECT count(*) FROM written"));
            s.execute("START TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            assertThrows(SQLException.class, () -> s.execute("SELECT nope"));
            s.execute("ROLLBACK TO SAVEPOINT a");
            assertEquals("1", value(c, "SELECT 1"));
            s.execute("COMMIT");
            s.clearWarnings();
            s.execute("COMMIT");
            assertEquals("25P01", s.getWarnings().getSQLState());
            s.execute("DROP TABLE written");
        }
    }
}
