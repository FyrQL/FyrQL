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

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Creates OData sources from options, as a configuration gives them. */
class ODataConnectorFactoryTest {

    private static final ConnectorFactory FACTORY = ServiceLoader.load(ConnectorFactory.class).stream()
            .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("odata")).findFirst().orElseThrow();

    private final FakeODataServer server = FakeODataServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    private void numbers(String service, ODataVersion version) {
        server.service(service, version).entitySet("Numbers", Schema.of(new Schema.Field("n", DataType.INTEGER,
                false)), List.of(new Object[] {1}, new Object[] {2}, new Object[] {3}), "n");
    }

    private static Map<String, String> options(String... pairs) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            options.put(pairs[i], pairs[i + 1]);
        }
        return options;
    }

    private static String error(Map<String, String> options) {
        return assertThrows(IllegalArgumentException.class, () -> FACTORY.create("erp", options)).getMessage();
    }

    @Test
    void theFactoryIsFoundByItsType() {
        assertInstanceOf(ODataConnectorFactory.class, FACTORY);
    }

    @Test
    void oneServiceWithBasicAuthenticationAndHeaders() {
        numbers("v4", ODataVersion.V4);
        server.basic("ann", "pw");
        try (Connector c = FACTORY.create("erp", options("url", server.url("v4").toString(), "user", "ann",
                "password", "pw", "headers.Accept-Language", "de", "timeout", "5s", "pageSize", "2", "batchSize",
                "1"))) {
            assertEquals(List.of("main"), c.catalog().schemas());
            assertEquals(List.of(List.of(1), List.of(2), List.of(3)), ODataConnectorTest.read(c,
                    new TableRef("erp", "main", "Numbers"), 0));
        }
        FakeODataServer.Request query = server.requests("/v4/Numbers").get(0);
        assertEquals("de", query.header("Accept-Language"));
        assertEquals("odata.maxpagesize=2", query.header("Prefer"));
        assertEquals("Basic", query.header("Authorization"));
        assertEquals(2, server.requests("/v4/Numbers").size());
    }

    @Test
    void severalServicesWithAToken() {
        numbers("sales", ODataVersion.V2);
        numbers("trips", ODataVersion.V4);
        server.bearer("t");
        try (Connector c = FACTORY.create("erp", options("services.sales", server.url("sales").toString(),
                "services.trips.url", server.url("trips").toString(), "services.trips.version", "4.0",
                "token", "t"))) {
            assertEquals(List.of("sales", "trips"), c.catalog().schemas());
            assertEquals(3, ODataConnectorTest.read(c, new TableRef("erp", "sales", "Numbers"), 0).size());
            assertEquals(3, ODataConnectorTest.read(c, new TableRef("erp", "trips", "Numbers"), 0).size());
        }
    }

    @Test
    void oauth() {
        numbers("trips", ODataVersion.V4);
        server.oauth("client", "secret", 3600);
        try (Connector c = FACTORY.create("erp", options("url", server.url("trips").toString(), "oauth.tokenUrl",
                server.tokenUrl().toString(), "oauth.clientId", "client", "oauth.clientSecret", "secret",
                "oauth.scope", "api", "oauth.clientAuthentication", "POST"))) {
            assertEquals(3, ODataConnectorTest.read(c, new TableRef("erp", "main", "Numbers"), 0).size());
        }
        assertEquals(Map.of("grant_type", "client_credentials", "scope", "api", "client_id", "client",
                "client_secret", "secret"), server.requests("/oauth/token").get(0).parameters());
    }

    @Test
    void filtersAreTrustedBySourceAndByServiceAndRowsCountedOnRequest() {
        numbers("sales", ODataVersion.V2);
        numbers("trips", ODataVersion.V4);
        Expression filter = new Expression.Comparison(Expression.ComparisonOp.GT, new Expression.Column(0,
                DataType.INTEGER), new Expression.Literal(DataType.INTEGER, 1));
        ScanRequest request = new ScanRequest(ColumnPath.whole(List.of(0)), List.of(filter), OptionalLong.empty(),
                List.of(), Optional.empty());
        try (Connector c = FACTORY.create("erp", options("services.sales", server.url("sales").toString(),
                "services.trips.url", server.url("trips").toString(), "services.trips.trustFilters", "off",
                "trustFilters", "on", "rowCounts", "true"))) {
            assertEquals(List.of(filter), c.plan(new TableRef("erp", "sales", "Numbers"), request).acceptedFilters());
            assertEquals(List.of(), c.plan(new TableRef("erp", "trips", "Numbers"), request).acceptedFilters());
            assertEquals(OptionalLong.of(3), c.catalog().rowCount(new TableRef("erp", "trips", "Numbers")));
        }
        try (Connector c = FACTORY.create("erp", options("url", server.url("trips").toString()))) {
            assertEquals(List.of(), c.plan(new TableRef("erp", "main", "Numbers"), request).acceptedFilters());
            assertEquals(OptionalLong.empty(), c.catalog().rowCount(new TableRef("erp", "main", "Numbers")));
        }
        assertEquals(1, server.requests("/trips/Numbers/$count").size());
    }

    @Test
    void optionsThatDoNotFit() {
        assertEquals("The OData source erp needs the option url or services.<schema>.url", error(options()));
        assertEquals("The OData source erp has both url and services; url is the one service of a source",
                error(options("url", "http://h/a/", "services.b", "http://h/b/")));
        assertEquals("The service b of the OData source erp has an unknown attribute uri; known are url, version,"
                + " trustFilters",
                error(options("services.b.uri", "http://h/b/")));
        assertEquals("The service b of the OData source erp needs services.b.url",
                error(options("services.b.version", "v2")));
        assertEquals("The service b of the OData source erp has both services.b and services.b.url",
                error(options("services.b", "http://h/b/", "services.b.url", "http://h/b/")));
        assertEquals("The option services.b.version of the OData source erp must be v2 or v4: 3",
                error(options("services.b.url", "http://h/b/", "services.b.version", "3")));
        assertEquals("The OData source erp has more than one authentication: user and password, token, oauth; it"
                + " may have one of them", error(options("url", "http://h/a/", "user", "u", "password", "p",
                        "token", "t", "oauth.scope", "s")));
        assertEquals("The OData source erp needs both user and password",
                error(options("url", "http://h/a/", "password", "p")));
        assertEquals("The OData source erp needs oauth.tokenUrl, oauth.clientId and oauth.clientSecret for OAuth 2.0",
                error(options("url", "http://h/a/", "oauth.clientId", "c", "oauth.clientSecret", "s")));
        assertEquals("The option oauth.clientAuthentication of the OData source erp must be basic or post: jwt",
                error(options("url", "http://h/a/", "oauth.clientAuthentication", "jwt")));
        assertEquals("The OData source erp has an unknown option trustfilters; known are url,"
                + " services.<schema>.<attribute>, user, password, token, oauth.tokenUrl, oauth.clientId,"
                + " oauth.clientSecret, oauth.scope, oauth.clientAuthentication, timeout, pageSize, batchSize,"
                + " trustFilters, rowCounts, headers.<name>", error(options("url", "http://h/a/", "trustfilters",
                        "true")));
        assertEquals("The option trustFilters of the OData source erp must be true, false, on or off: yes",
                error(options("url", "http://h/a/", "trustFilters", "yes")));
        assertEquals("The option services.b.trustFilters of the OData source erp must be true, false, on or off:"
                + " always", error(options("services.b.url", "http://h/b/", "services.b.trustFilters", "always")));
    }
}
