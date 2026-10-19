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

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS from the JDK, without native code: a client that trusts the
 * self-signed certificate of the test, which is valid for
 * {@code localhost} for a hundred years, reads a result; a client that
 * does not trust it does not get in.
 */
class TlsTest {

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(TlsTest.class.getResource(name).toURI());
    }

    @Test
    void aClientThatTrustsTheCertificateReadsAResult() throws Exception {
        ServerConfig config = ServerConfig.defaults().withPort(0).withTls(resource("server.pem"),
                resource("server.key"));
        try (TestServer server = new TestServer(config)) {
            assertTrue(server.server.uri().startsWith("grpc+tls://127.0.0.1:"), server.server.uri());
            FlightSqlClient trusting;
            try (InputStream certificate = TlsTest.class.getResourceAsStream("server.pem")) {
                trusting = server.client(FlightClient.builder(server.allocator, server.location())
                        .trustedCertificates(certificate).build());
            }
            assertEquals(List.of(List.of("one")), TestServer.comparable(TestServer.rows(trusting,
                    trusting.execute("SELECT v FROM tiny WHERE k = 1"))));
            FlightSqlClient doubting = server.client();
            TestServer.fails(FlightStatusCode.UNAVAILABLE, "", () -> doubting.execute("SELECT 1"));
        }
    }
}
