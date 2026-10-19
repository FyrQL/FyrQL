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

package net.fyrql.connector.odata;

import net.fyrql.connector.http.HttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authentications of a source, the tokens of OAuth 2.0 from the token endpoint of the fake server. */
class AuthenticationTest {

    private final FakeODataServer server = FakeODataServer.start();
    private final HttpTransport transport = HttpTransport.builder().build();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-18T10:00:00Z"));
    private final InstantSource clock = now::get;

    @AfterEach
    void stop() {
        transport.close();
        server.close();
    }

    private Authentication.ClientCredentials oauth(URI tokenUrl, String secret, String scope, boolean inBody) {
        return new Authentication.ClientCredentials("erp", tokenUrl, "fyrql client", secret, scope, inBody,
                transport, clock);
    }

    private void advance(long seconds) {
        now.set(now.get().plus(Duration.ofSeconds(seconds)));
    }

    @Test
    void fixedHeaders() {
        assertNull(Authentication.None.INSTANCE.header());
        assertFalse(Authentication.None.INSTANCE.renew());
        Authentication basic = new Authentication.Basic("Aladdin", "open sesame");
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", basic.header());
        assertEquals("basic", basic.toString());
        assertEquals("Basic w6RiYzrDtg==", new Authentication.Basic("\u00E4bc", "\u00F6").header());
        assertEquals("The user of HTTP Basic must not contain a colon", assertThrows(IllegalArgumentException.class,
                () -> new Authentication.Basic("a:b", "c")).getMessage());
        Authentication bearer = new Authentication.Bearer(" abc.def ");
        assertEquals("Bearer abc.def", bearer.header());
        assertFalse(bearer.renew());
        assertEquals("bearer", bearer.toString());
        assertThrows(IllegalArgumentException.class, () -> new Authentication.Bearer(" "));
    }

    @Test
    void tokensAreKeptUntilShortlyBeforeTheyExpire() {
        server.oauth("fyrql client", "s3cret&+", 3600);
        Authentication.ClientCredentials auth = oauth(server.tokenUrl(), "s3cret&+", "read write", false);
        assertEquals("Bearer token-1", auth.header());
        advance(3539);
        assertEquals("Bearer token-1", auth.header());
        advance(1);
        assertEquals("Bearer token-2", auth.header());
        assertTrue(auth.renew());
        assertEquals("Bearer token-3", auth.header());
        List<FakeODataServer.Request> requests = server.requests("/oauth/token");
        assertEquals(3, requests.size());
        FakeODataServer.Request r = requests.get(0);
        assertEquals("POST", r.method());
        assertEquals(Map.of("grant_type", "client_credentials", "scope", "read write"), r.parameters());
        assertEquals("Basic", r.header("Authorization"));
        assertEquals("application/x-www-form-urlencoded", r.header("Content-Type"));
        assertEquals("oauth2 client credentials", auth.toString());
    }

    @Test
    void shortLivedTokensAndTokensWithoutLifetime() {
        server.oauth("fyrql client", "s", 100);
        Authentication.ClientCredentials auth = oauth(server.tokenUrl(), "s", null, true);
        assertEquals("Bearer token-1", auth.header());
        advance(49);
        assertEquals("Bearer token-1", auth.header());
        advance(1);
        assertEquals("Bearer token-2", auth.header());
        FakeODataServer.Request r = server.requests("/oauth/token").get(0);
        assertEquals(Map.of("grant_type", "client_credentials", "client_id", "fyrql client", "client_secret", "s"),
                r.parameters());
        assertNull(r.header("Authorization"));

        server.oauth("fyrql client", "s", -1);
        Authentication.ClientCredentials forever = oauth(server.tokenUrl(), "s", "", false);
        assertEquals("Bearer token-3", forever.header());
        advance(1_000_000);
        assertEquals("Bearer token-3", forever.header());
        assertEquals(Map.of("grant_type", "client_credentials"), server.requests("/oauth/token").get(2)
                .parameters());
    }

    @Test
    void refusedAndMissingTokens() throws IOException {
        server.oauth("fyrql client", "right", 3600);
        ODataSourceException e = assertThrows(ODataSourceException.class,
                () -> oauth(server.tokenUrl(), "wrong", null, false).header());
        assertEquals("The token endpoint " + server.tokenUrl() + " of the OData source erp refused the client"
                + " credentials with the status 401: invalid_client: Bad client credentials", e.getMessage());
        assertEquals(401, e.status());
        assertEquals("erp", e.source());
        assertNull(e.service());
        assertEquals("invalid_client: Bad client credentials", e.serviceMessage());

        URI notFound = URI.create(server.url("nothing") + "token?secret=1");
        e = assertThrows(ODataSourceException.class, () -> oauth(notFound, "right", null, false).header());
        assertEquals("The token endpoint " + ODataQuery.shown(notFound) + " of the OData source erp refused the"
                + " client credentials with the status 404", e.getMessage());

        URI closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = URI.create("http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort()
                    + "/token");
        }
        URI unreachable = closed;
        e = assertThrows(ODataSourceException.class, () -> oauth(unreachable, "right", null, false).header());
        assertTrue(e.getMessage().startsWith("The token endpoint " + closed + " of the OData source erp did not"
                + " answer: POST " + closed + " failed: "), e.getMessage());
        assertEquals(-1, e.status());
    }
}
