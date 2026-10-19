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

import org.apache.arrow.flight.CancelFlightInfoRequest;
import org.apache.arrow.flight.CancelStatus;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canceling a query of a million rows, which the server sends no faster
 * than the client reads: with {@code CancelFlightInfo}, by closing the
 * stream, and before it runs. Each time the server is free again within
 * two seconds.
 */
class CancelTest {

    /** A million rows. */
    private static final String BIG = "SELECT a.n, b.s FROM numbers a, numbers b";
    private static final long FREE_MILLIS = 2000;

    private TestServer server;
    private FlightSqlClient client;

    @BeforeEach
    void open() throws Exception {
        server = new TestServer();
        client = server.client();
    }

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    private void awaitFree() throws Exception {
        TestServer.awaitTrue("the server runs no query", FREE_MILLIS, () -> server.server.runningQueries() == 0);
        assertEquals(List.of(List.of(1)), TestServer.comparable(TestServer.rows(client,
                client.execute("SELECT k FROM tiny WHERE k = 1"))), "the server answers");
    }

    @Test
    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    void cancelFlightInfoStopsTheQuery() throws Exception {
        FlightInfo info = client.execute(BIG);
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            assertTrue(stream.next());
            assertEquals(1, server.server.runningQueries());
            assertEquals(CancelStatus.CANCELLED, client.cancelFlightInfo(new CancelFlightInfoRequest(info))
                    .getStatus());
            TestServer.fails(FlightStatusCode.CANCELLED, "The query was canceled", () -> {
                while (stream.next()) {
                    // Reads what the server sent before.
                }
            });
        }
        awaitFree();
        assertEquals(CancelStatus.NOT_CANCELLABLE, client.cancelFlightInfo(new CancelFlightInfoRequest(info))
                .getStatus(), "the ticket was redeemed");
    }

    @Test
    void closingTheStreamStopsTheQuery() throws Exception {
        FlightInfo info = client.execute(BIG);
        FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket());
        assertTrue(stream.next());
        assertEquals(1, server.server.runningQueries());
        stream.close();
        awaitFree();
    }

    @Test
    void aQueryCanceledBeforeItRunsDoesNotRun() throws Exception {
        FlightInfo info = client.execute(BIG);
        assertEquals(CancelStatus.CANCELLED, client.cancelFlightInfo(new CancelFlightInfoRequest(info)).getStatus());
        TestServer.fails(FlightStatusCode.CANCELLED, "The query was canceled", () -> TestServer.rows(client, info));
        awaitFree();
    }
}
