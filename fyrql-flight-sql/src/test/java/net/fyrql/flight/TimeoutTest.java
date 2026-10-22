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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeout of the engine ends the queries of clients with
 * {@code TIMED_OUT}: while the query runs, while the client reads its
 * rows, and for a statement that writes. The server is free again after.
 */
class TimeoutTest {

    /** A billion rows, which no query counts within the timeout. */
    private static final String HUGE = "FROM numbers a, numbers b, numbers c";

    @Test
    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    void theTimeoutOfTheEngineEndsTheQueriesOfClients() throws Exception {
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0),
                FyrQL.builder().queryTimeout(Duration.ofMillis(300)))) {
            FlightSqlClient client = server.client();
            long start = System.nanoTime();
            TestServer.fails(FlightStatusCode.TIMED_OUT, "The query timed out", () -> TestServer.rows(client,
                    client.execute("SELECT count(*) " + HUGE)));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis >= 250 && millis < 5000, "the query timed out after " + millis + " ms");
            server.awaitNoQuery();

            FlightInfo info = client.execute("SELECT a.n, b.s " + HUGE);
            try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
                assertTrue(stream.next());
                Thread.sleep(600);
                TestServer.fails(FlightStatusCode.TIMED_OUT, "The query timed out", () -> {
                    while (stream.next()) {
                        // Reads what the server sent before.
                    }
                });
            }
            server.awaitNoQuery();

            TestServer.fails(FlightStatusCode.TIMED_OUT, "The query timed out",
                    () -> client.executeUpdate("CREATE TABLE mem.huge AS SELECT a.n " + HUGE));
            server.awaitNoQuery();
            assertEquals(2, TestServer.rows(client, client.execute("SELECT k FROM tiny")).size(),
                    "a query that ends in time");
        }
    }
}
