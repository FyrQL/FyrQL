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

package net.fyrql.flight;

import net.fyrql.FyrQL;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many clients at once, each with its own queries; the most queries that
 * run at once; and the memory budget of the engine, which all queries
 * share.
 */
class ConcurrencyTest {

    private static final int CLIENTS = 12;
    private static final int QUERIES = 20;

    @Test
    void manyClientsRunTheirQueriesAtOnce() throws Exception {
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0).withMaxQueries(CLIENTS))) {
            ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int c = 0; c < CLIENTS; c++) {
                    FlightSqlClient client = server.client();
                    int first = c;
                    results.add(pool.submit(() -> {
                        int checked = 0;
                        for (int q = 0; q < QUERIES; q++) {
                            long below = (first * QUERIES + q) * 4L;
                            List<Object[]> rows = TestServer.rows(client, client.execute(
                                    "SELECT count(*) AS c, sum(n) AS s FROM numbers WHERE n < " + below));
                            assertEquals(below, rows.get(0)[0]);
                            checked++;
                        }
                        return checked;
                    }));
                }
                for (Future<Integer> f : results) {
                    assertEquals(QUERIES, f.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(0, server.server.runningQueries());
        }
    }

    @Test
    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    void moreQueriesThanAllowedAreRejected() throws Exception {
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0).withMaxQueries(1))) {
            assertEquals(1, server.server.maxQueries());
            FlightSqlClient first = server.client();
            FlightSqlClient second = server.client();
            FlightInfo big = first.execute("SELECT a.n, b.s FROM numbers a, numbers b");
            FlightInfo small = second.execute("SELECT k FROM tiny");
            try (FlightStream stream = first.getStream(big.getEndpoints().get(0).getTicket())) {
                assertTrue(stream.next());
                TestServer.fails(FlightStatusCode.RESOURCE_EXHAUSTED, "The server runs 1 queries, the most it runs at"
                        + " once (maxQueries)", () -> TestServer.rows(second, small));
            }
            server.awaitNoQuery();
            assertEquals(2, TestServer.rows(second, second.execute("SELECT k FROM tiny")).size());
        }
    }

    @Test
    void aQueryReadToItsEndLeavesItsPlaceAtOnce() throws Exception {
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0).withMaxQueries(1))) {
            FlightSqlClient client = server.client();
            for (int q = 0; q < QUERIES; q++) {
                assertEquals(2, TestServer.rows(client, client.execute("SELECT k FROM tiny")).size());
                TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "", () -> TestServer.rows(client,
                        client.execute("SELECT CAST(v AS INTEGER) FROM tiny")));
                assertEquals(0, server.server.runningQueries());
            }
        }
    }

    @Test
    void theMemoryBudgetOfTheEngineIsExhausted() throws Exception {
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0),
                FyrQL.builder().memoryBudget(1_000_000).spill(false))) {
            FlightSqlClient client = server.client();
            FlightInfo info = client.execute("SELECT a.n, b.s FROM numbers a, numbers b ORDER BY b.s, a.n");
            TestServer.fails(FlightStatusCode.RESOURCE_EXHAUSTED, "", () -> TestServer.rows(client, info));
            assertEquals(2, TestServer.rows(client, client.execute("SELECT k FROM tiny")).size());
        }
    }
}
