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
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.auth2.BearerCredentialWriter;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in: users with passwords and the bearer tokens the server gives
 * them, the token of the server, wrong credentials, handles that only their
 * client finds, and a server without sign-in only on a loopback address.
 */
class SignInTest {

    private static final String SQL = "SELECT v FROM tiny ORDER BY k";
    private static final List<List<Object>> ROWS = List.of(List.of("one"), List.of("two"));
    private static final String TOKEN = "a-token-of-the-server";

    private TestServer server;

    @BeforeEach
    void open() throws Exception {
        server = new TestServer(ServerConfig.defaults().withPort(0)
                .withUsers(Map.of("alice", "wonderland", "bob", "builder")).withToken(TOKEN));
    }

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    /** A client and the option with its credentials. */
    private record Client(FlightSqlClient sql, CallOption credentials) {
        List<List<Object>> rows(String query) throws Exception {
            return TestServer.comparable(TestServer.rows(sql, sql.execute(query, credentials), credentials));
        }
    }

    private Client signIn(String user, String password) {
        FlightClient flight = FlightClient.builder(server.allocator, server.location()).build();
        FlightSqlClient sql = server.client(flight);
        Optional<CredentialCallOption> bearer = flight.authenticateBasicToken(user, password);
        assertTrue(bearer.isPresent(), "the server returns a bearer token");
        return new Client(sql, bearer.get());
    }

    @Test
    void aUserSignsInWithItsPasswordAndGoesOnWithABearerToken() throws Exception {
        assertEquals(ROWS, signIn("alice", "wonderland").rows(SQL));
        assertEquals(ROWS, signIn("bob", "builder").rows(SQL));
    }

    @Test
    void theTokenOfTheServerLetsAClientIn() throws Exception {
        Client c = new Client(server.client(), new CredentialCallOption(new BearerCredentialWriter(TOKEN)));
        assertEquals(ROWS, c.rows(SQL));
    }

    @Test
    void wrongCredentialsAreRejected() {
        TestServer.fails(FlightStatusCode.UNAUTHENTICATED, "Wrong user name or password",
                () -> signIn("alice", "builder"));
        TestServer.fails(FlightStatusCode.UNAUTHENTICATED, "Wrong user name or password",
                () -> signIn("carol", "wonderland"));
        FlightSqlClient anonymous = server.client();
        TestServer.fails(FlightStatusCode.UNAUTHENTICATED, "The server needs a user name and password or a bearer"
                + " token", () -> anonymous.execute(SQL));
        TestServer.fails(FlightStatusCode.UNAUTHENTICATED, "Wrong bearer token", () -> anonymous.execute(SQL,
                new CredentialCallOption(new BearerCredentialWriter("not-" + TOKEN))));
    }

    @Test
    void aClientFindsOnlyItsOwnHandles() throws Exception {
        Client alice = signIn("alice", "wonderland");
        Client bob = signIn("bob", "builder");
        FlightInfo info = alice.sql().execute(SQL, alice.credentials());
        TestServer.fails(FlightStatusCode.NOT_FOUND, "The statement is unknown",
                () -> TestServer.rows(bob.sql(), info, bob.credentials()));
        assertEquals(ROWS, TestServer.comparable(TestServer.rows(alice.sql(), info, alice.credentials())));
    }

    @Test
    void withoutSignInTheServerListensOnlyOnALoopbackAddress() throws Exception {
        try (FyrQL engine = FyrQL.builder().build()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlightSqlServer
                    .builder(engine).config(ServerConfig.defaults().withHost("0.0.0.0")).build());
            assertEquals("The server would listen on 0.0.0.0 without sign-in; without users or a token it listens"
                    + " only on a loopback address such as 127.0.0.1", e.getMessage());
            for (String host : List.of("127.0.0.1", "localhost", "::1")) {
                FlightSqlServer.builder(engine).config(ServerConfig.defaults().withHost(host)).build().close();
            }
            FlightSqlServer.builder(engine).config(ServerConfig.defaults().withHost("0.0.0.0").withToken(TOKEN))
                    .build().close();
        }
    }
}
