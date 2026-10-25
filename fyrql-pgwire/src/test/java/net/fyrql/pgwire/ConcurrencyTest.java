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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Connections at once, each with its session, and the most connections the server takes. */
class ConcurrencyTest {

    @Test
    void connectionsRunAtOnceEachInItsSession() throws Exception {
        // By default the server runs twice as many queries at once as the engine has threads, which may be fewer than 8.
        try (TestServer server = new TestServer(PgWireConfig.defaults().withPort(0).withMaxQueries(8))) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Long>> runs = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    int zone = t;
                    runs.add(pool.submit(() -> {
                        Connection c = server.connect();
                        try (Statement s = c.createStatement()) {
                            s.execute("SET TimeZone = 'Etc/GMT-" + zone + "'");
                        }
                        long sum = 0;
                        try (PreparedStatement ps = c.prepareStatement("SELECT sum(n) FROM numbers WHERE n < ?")) {
                            for (int i = 1; i <= 20; i++) {
                                ps.setLong(1, i * 10L);
                                try (ResultSet rs = ps.executeQuery()) {
                                    rs.next();
                                    sum += rs.getLong(1);
                                }
                            }
                        }
                        assertEquals("Etc/GMT-" + zone, value(c, "SHOW TimeZone"));
                        return sum;
                    }));
                }
                long expected = 0;
                for (int i = 1; i <= 20; i++) {
                    long n = i * 10L;
                    expected += n * (n - 1) / 2;
                }
                for (Future<Long> run : runs) {
                    assertEquals(expected, run.get(60, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void theServerTakesAtMostMaxConnections() throws Exception {
        try (TestServer server = new TestServer(PgWireConfig.defaults().withPort(0).withMaxConnections(2),
                FyrQL.builder())) {
            Connection a = server.connect();
            server.connect();
            SQLException e = assertThrows(SQLException.class, server::connect);
            assertEquals("53300", e.getSQLState());
            a.close();
            for (int attempt = 0; ; attempt++) {
                try {
                    assertEquals("1", value(server.connect(), "SELECT 1"));
                    break;
                } catch (SQLException again) {
                    // The server counts the closed connection off a moment later.
                    if (attempt == 50) {
                        throw again;
                    }
                    Thread.sleep(100);
                }
            }
        }
    }
}
