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
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A profile of a kind of server, as one of SAP Gateway would be: it adds
 * a query option and a header to every request, of which the log of the
 * fake server tells, gives a column of {@code Edm.DateTime} the type
 * {@code DATE} and leaves a column out.
 */
class ODataProfileTest {

    private static final Schema PEOPLE = new Schema(List.of(new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("name", DataType.VARCHAR, true), new Schema.Field("born", DataType.TIMESTAMP, true),
            new Schema.Field("secret", DataType.VARCHAR, true)));

    private static final List<Object[]> ROWS = List.of(
            new Object[] {1, "Ann", ODataConnectorTest.micros("1990-05-17 00:00:00"), "x"},
            new Object[] {2, "Bob", null, "y"},
            new Object[] {3, "Cid", ODataConnectorTest.micros("1969-12-31 00:00:00"), null},
            new Object[] {4, "Dee", ODataConnectorTest.micros("2001-01-01 00:00:00"), "z"},
            new Object[] {5, "Eve", ODataConnectorTest.micros("1990-05-17 00:00:00"), "w"});

    /** The profile of the test: the client 100 and the kind of each request in a header. */
    private static final class TestProfile implements ODataProfile {

        @Override
        public void decorate(RequestSpec request) {
            request.parameter("sap-client", "100").header("X-Request-Kind", request.kind().name());
        }

        @Override
        public DataType type(EntitySet set, Property property, DataType mapped) {
            if (property.name().equals("secret")) {
                return null;
            }
            return property.name().equals("born") && property.type().equals("Edm.DateTime") ? DataType.DATE : mapped;
        }
    }

    private final FakeODataServer server = FakeODataServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    private ODataConnector connector(URI v2, URI v4) {
        return ODataConnector.builder("erp").service("v2", v2).service("v4", v4).profile(new TestProfile())
                .trustFilters(true).rowCounts(true).build();
    }

    private void services() {
        server.service("v2", ODataVersion.V2).requireOption("sap-client", "100").pageSize(2)
                .entitySet("People", PEOPLE, ROWS, "id");
        server.service("v4", ODataVersion.V4).requireOption("sap-client", "100").pageSize(2)
                .entitySet("People", PEOPLE, ROWS, "id");
    }

    @Test
    void everyRequestCarriesTheOptionAndTheHeaderOfTheProfile() {
        services();
        try (ODataConnector c = connector(server.url("v2"), server.url("v4"))) {
            for (String service : List.of("v2", "v4")) {
                TableRef people = new TableRef("erp", service, "People");
                assertEquals(OptionalLong.of(5), c.catalog().rowCount(people));
                assertEquals(5, ODataConnectorTest.readAll(c, people).size());
            }
        }
        Set<RequestSpec.Kind> kinds = EnumSet.noneOf(RequestSpec.Kind.class);
        for (FakeODataServer.Request r : server.requests()) {
            assertEquals("100", r.parameters().get("sap-client"), r.path());
            kinds.add(RequestSpec.Kind.valueOf(r.header("X-Request-Kind")));
        }
        assertEquals(EnumSet.allOf(RequestSpec.Kind.class), kinds);
        // The metadata, the count and three pages of each service.
        assertEquals(10, server.requests().size());
    }

    @Test
    void anOptionThatTheUrlHasIsNotAddedTwice() {
        services();
        URI v2 = URI.create(server.url("v2") + "?sap-client=100");
        URI v4 = URI.create(server.url("v4") + "?sap-client=100");
        try (ODataConnector c = connector(v2, v4)) {
            for (String service : List.of("v2", "v4")) {
                assertEquals(5, ODataConnectorTest.readAll(c, new TableRef("erp", service, "People")).size());
            }
        }
    }

    @Test
    void columnsOfOtherTypesAreReadAsSuchAndNeitherFilteredNorSorted() {
        services();
        Schema schema = new Schema(List.of(new Schema.Field("id", DataType.INTEGER, false),
                new Schema.Field("name", DataType.VARCHAR, true), new Schema.Field("born", DataType.DATE, true)));
        Expression born = new Expression.Comparison(ComparisonOp.EQ, new Expression.Column(2, DataType.DATE),
                new Expression.Literal(DataType.DATE, ODataConnectorTest.day("1990-05-17")));
        Expression id = new Expression.Comparison(ComparisonOp.LT, new Expression.Column(0, DataType.INTEGER),
                new Expression.Literal(DataType.INTEGER, 5));
        ScanRequest request = new ScanRequest(ColumnPath.whole(List.of(0, 1, 2)), List.of(born, id),
                OptionalLong.empty(), List.of(new PlanNode.SortKey(2, true, false)), Optional.empty());
        try (ODataConnector c = connector(server.url("v2"), server.url("v4"))) {
            TableRef people = new TableRef("erp", "v2", "People");
            assertEquals(schema, c.catalog().schema(people));
            assertEquals(List.of(List.of(1, "Ann", ODataConnectorTest.day("1990-05-17")),
                    Arrays.asList(2, "Bob", null), List.of(3, "Cid", -1),
                    List.of(4, "Dee", ODataConnectorTest.day("2001-01-01")), List.of(5, "Eve",
                    ODataConnectorTest.day("1990-05-17"))), ODataConnectorTest.readAll(c, people));
            ScanPlan plan = c.plan(people, request);
            assertEquals(List.of(id), plan.acceptedFilters());
            assertFalse(plan.orderAccepted());
            server.clearRequests();
            ODataConnectorTest.read(c, people, request);
            assertEquals("id lt 5", server.requests("/v2/People").get(0).parameters().get("$filter"));
            // Of version 4 the column is of Edm.DateTimeOffset, an instant.
            assertEquals(new Schema(List.of(PEOPLE.fields().get(0), PEOPLE.fields().get(1),
                    new Schema.Field("born", DataType.TIMESTAMPTZ, true))),
                    c.catalog().schema(new TableRef("erp", "v4", "People")));
        }
    }

    @Test
    void theOptionsAndHeadersOfARequest() {
        RequestSpec r = new RequestSpec(RequestSpec.Kind.QUERY, "main", "People",
                URI.create("http://host/svc/People?$filter=name%20eq%20%27a+b%27&x"), Map.of("Accept", "a/b"));
        assertEquals("name eq 'a+b'", r.parameter("$filter"));
        assertEquals("", r.parameter("x"));
        assertNull(r.parameter("y"));
        r.parameter("$filter", "other").parameter("y", "1 & 2");
        assertEquals("http://host/svc/People?$filter=name%20eq%20%27a+b%27&x&y=1%20%26%202", r.uri().toString());
        assertEquals("1 & 2", r.parameter("y"));
        assertEquals(URI.create("http://host/m?a=1"), new RequestSpec(RequestSpec.Kind.METADATA, "main", null,
                URI.create("http://host/m"), Map.of()).parameter("a", "1").uri());
        r.header("accept", "c/d");
        assertEquals(Map.of("accept", "c/d"), r.headers());
        assertThrows(IllegalArgumentException.class, () -> r.header("Host", "other"));
        assertThrows(UnsupportedOperationException.class, () -> r.headers().clear());
    }
}
