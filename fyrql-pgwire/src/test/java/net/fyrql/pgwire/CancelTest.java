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

import net.fyrql.FyrQL;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A query that a client cancels, one that runs past its timeout, and the most queries at once. */
class CancelTest {

    /** A query over a billion rows, which runs until something stops it. */
    static final String LONG = "SELECT count(*) FROM numbers a, numbers b, numbers c WHERE a.n + b.n + c.n < 0";

    @Test
    void aClientCancelsItsQuery() throws Exception {
        try (TestServer server = new TestServer()) {
            Connection c = server.connect();
            try (Statement s = c.createStatement()) {
                CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(300);
                        s.cancel();
                    } catch (InterruptedException | SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                PSQLException e = assertThrows(PSQLException.class, () -> s.executeQuery(LONG));
                assertEquals("57014", e.getSQLState());
                assertTrue(e.getMessage().contains("user request"), e.getMessage());
                cancel.get(10, TimeUnit.SECONDS);
            }
            assertEquals("1", value(c, "SELECT 1"));
        }
    }

    @Test
    void aStatementTimeoutCancelsAQuery() throws Exception {
        try (TestServer server = new TestServer()) {
            Connection c = server.connect();
            try (Statement s = c.createStatement()) {
                s.execute("SET statement_timeout = 200");
                PSQLException e = assertThrows(PSQLException.class, () -> s.executeQuery(LONG));
                assertEquals("57014", e.getSQLState());
                assertTrue(e.getMessage().contains("statement timeout"), e.getMessage());
                s.execute("RESET statement_timeout");
            }
            assertEquals("1", value(c, "SELECT 1"));
        }
    }

    /** Runs the long query on a statement until it is canceled, or refused. */
    private static CompletableFuture<Void> runLong(Statement s) {
        return CompletableFuture.runAsync(() -> {
            try {
                s.executeQuery(LONG);
            } catch (SQLException e) {
                // Canceled, or refused.
            }
        });
    }

    @Test
    void theServerRunsAtMostMaxQueriesAtOnce() throws Exception {
        try (TestServer server = new TestServer(PgWireConfig.defaults().withPort(0).withMaxQueries(1),
                FyrQL.builder())) {
            Connection a = server.connect();
            Connection b = server.connect();
            try (Statement s = a.createStatement()) {
                CompletableFuture<Void> running = runLong(s);
                // Until the long query holds the only place, another one may still run; and while that one runs,
                // the long query may be refused itself, and starts again.
                PSQLException e = null;
                for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); e == null
                        && System.nanoTime() < end; ) {
                    if (running.isDone()) {
                        running = runLong(s);
                    }
                    Thread.sleep(100);
                    try {
                        value(b, "SELECT 1");
                    } catch (PSQLException refused) {
                        e = refused;
                    }
                }
                assertNotNull(e, "the second query is refused");
                assertEquals("53000", e.getSQLState());
                s.cancel();
                running.get(10, TimeUnit.SECONDS);
            }
            assertEquals("1", value(b, "SELECT 1"));
        }
    }
}
