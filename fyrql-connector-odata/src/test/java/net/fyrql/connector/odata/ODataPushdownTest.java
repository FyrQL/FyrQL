/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What scans hand over to services of both versions of the fake server:
 * the {@code $filter}, {@code $orderby} and {@code $top} of their queries
 * and the requests of {@code /$count}, as the log of the server shows them,
 * and the rows that come back.
 */
class ODataPushdownTest {

    private static final Schema PEOPLE = new Schema(List.of(new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("name", DataType.VARCHAR, true), new Schema.Field("age", DataType.INTEGER, true),
            new Schema.Field("score", DataType.decimal(5, 1), true), new Schema.Field("at", DataType.TIMESTAMP,
                    true)));

    private static final List<Object[]> ROWS = List.of(
            new Object[] {1, "Ann", 34, new BigDecimal("7.5"), ODataConnectorTest.micros("2024-01-01 10:00:00.0004")},
            new Object[] {2, "Bob", 28, new BigDecimal("9.0"), ODataConnectorTest.micros("2024-01-01 10:00:00.001")},
            new Object[] {3, "ann", null, null, null},
            new Object[] {4, "Cid", 51, new BigDecimal("-1.5"), ODataConnectorTest.micros("2023-06-30 00:00:00")},
            new Object[] {5, "Ann ", 34, new BigDecimal("0.0"), ODataConnectorTest.micros("2025-02-03 04:05:06")},
            new Object[] {6, "Dee", 19, null, ODataConnectorTest.micros("1999-12-31 23:59:59.999")},
            new Object[] {7, null, 62, new BigDecimal("3.3"), null});

    private final FakeODataServer server = FakeODataServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    private FakeODataServer.Service service(String name, ODataVersion version) {
        return server.service(name, version);
    }

    private ODataConnector connector(boolean trust, String... schemas) {
        ODataConnector.Builder b = ODataConnector.builder("erp").trustFilters(trust).rowCounts(true);
        for (String s : schemas) {
            b.service(s, server.url(s));
        }
        return b.build();
    }

    private static TableRef people(String schema) {
        return new TableRef("erp", schema, "People");
    }

    private static Expression.Column col(String name) {
        int c = PEOPLE.indexOf(name);
        return new Expression.Column(c, PEOPLE.type(c));
    }

    private static Expression cmp(ComparisonOp op, Expression a, Object value) {
        DataType type = value instanceof String ? DataType.VARCHAR : value instanceof Long ? DataType.TIMESTAMP
                : DataType.INTEGER;
        return new Expression.Comparison(op, a, new Expression.Literal(type, value));
    }

    private static ScanRequest request(List<Expression> filters, OptionalLong limit, List<PlanNode.SortKey> order,
            Optional<PlanNode.Aggregation> aggregation) {
        return new ScanRequest(ColumnPath.whole(List.of(0, 1, 2, 3, 4)), filters, limit, order, aggregation);
    }

    private static List<Integer> ids(List<List<Object>> rows) {
        List<Integer> ids = new ArrayList<>();
        for (List<Object> row : rows) {
            ids.add((Integer) row.get(0));
        }
        return ids;
    }

    /** Returns the query options of the requests of the entity set {@code People} of a service. */
    private List<Map<String, String>> queries(String service) {
        return server.requests("/" + service + "/People").stream().map(FakeODataServer.Request::parameters).toList();
    }

    @Test
    void filtersGoAsHintsAndAreTakenOverWhenTrusted() {
        service("v2", ODataVersion.V2).caseInsensitive(true).ignoreTrailingSpaces(true).entitySet("People", PEOPLE,
                ROWS, "id");
        service("v4", ODataVersion.V4).entitySet("People", PEOPLE, ROWS, "id");
        Expression age = cmp(ComparisonOp.GT, col("age"), 30);
        Expression name = cmp(ComparisonOp.EQ, col("name"), "Ann");
        ScanRequest request = request(List.of(age, name), OptionalLong.empty(), List.of(), Optional.empty());
        try (ODataConnector c = connector(false, "v2", "v4")) {
            ScanPlan plan = c.plan(people("v2"), request);
            assertEquals(List.of(), plan.acceptedFilters());
            // The service compares without case and spaces at the end: more rows than FyrQL selects.
            assertEquals(List.of(1, 5), ids(ODataConnectorTest.read(c, people("v2"), request)));
            assertEquals(List.of(1), ids(ODataConnectorTest.read(c, people("v4"), request)));
        }
        assertEquals("age gt 30 and name eq 'Ann'", queries("v2").get(0).get("$filter"));
        assertEquals("allpages", queries("v2").get(0).get("$inlinecount"));
        assertEquals("age gt 30 and name eq 'Ann'", queries("v4").get(0).get("$filter"));
        try (ODataConnector c = connector(true, "v4")) {
            assertEquals(List.of(age), c.plan(people("v4"), request).acceptedFilters());
        }
        try (ODataConnector c = ODataConnector.builder("erp").service("v2", server.url("v2"))
                .service("v4", server.url("v4")).trustFilters("v4", true).build()) {
            assertEquals(List.of(), c.plan(people("v2"), request).acceptedFilters());
            assertEquals(List.of(age), c.plan(people("v4"), request).acceptedFilters());
        }
    }

    @Test
    void timestampsOfVersion2CompareByTheirMilliseconds() {
        service("v2", ODataVersion.V2).entitySet("People", PEOPLE, ROWS, "id");
        long at = ODataConnectorTest.micros("2024-01-01 10:00:00");
        ScanRequest request = request(List.of(cmp(ComparisonOp.LE, col("at"), at)), OptionalLong.empty(), List.of(),
                Optional.empty());
        try (ODataConnector c = connector(true, "v2")) {
            // The service holds 10:00:00.0004 and sends 10:00:00.000, which FyrQL selects.
            assertEquals(List.of(1, 4, 6), ids(ODataConnectorTest.read(c, people("v2"), request)));
        }
        assertEquals("at lt datetime'2024-01-01T10:00:00.001'", queries("v2").get(0).get("$filter"));
    }

    @Test
    void anOrderALimitAndCountStar() {
        service("v4", ODataVersion.V4).pageSize(2).entitySet("People", PEOPLE, ROWS, "id");
        PlanNode.SortKey byAgeDescending = new PlanNode.SortKey(2, false, false);
        try (ODataConnector c = connector(true, "v4")) {
            ScanRequest top = request(List.of(cmp(ComparisonOp.GE, col("age"), 20)), OptionalLong.of(3),
                    List.of(byAgeDescending, PlanNode.SortKey.asc(0)), Optional.empty());
            ScanPlan plan = c.plan(people("v4"), top);
            assertTrue(plan.orderAccepted() && plan.limitAccepted());
            assertEquals(List.of(7, 4, 1), ids(ODataConnectorTest.read(c, people("v4"), top)));
            assertEquals(Map.of("$filter", "age ge 20", "$orderby", "age desc,id asc", "$top", "3", "$count", "true"),
                    queries("v4").get(0));
            // Two pages of two records for three.
            assertEquals(2, queries("v4").size());

            // NULLs come first in an ascending order of OData, last in one of FyrQL.
            ScanPlan nullsLast = c.plan(people("v4"), request(List.of(), OptionalLong.of(2),
                    List.of(PlanNode.SortKey.asc(2)), Optional.empty()));
            assertFalse(nullsLast.orderAccepted());
            assertFalse(nullsLast.limitAccepted());
            assertTrue(c.plan(people("v4"), request(List.of(), OptionalLong.of(2),
                    List.of(new PlanNode.SortKey(2, true, true)), Optional.empty())).orderAccepted());
            assertFalse(c.plan(people("v4"), request(List.of(), OptionalLong.of(2), List.of(PlanNode.SortKey.asc(1)),
                    Optional.empty())).orderAccepted());
            // A hint keeps the limit in the engine.
            assertFalse(c.plan(people("v4"), request(List.of(cmp(ComparisonOp.EQ, col("name"), "Ann")),
                    OptionalLong.of(2), List.of(), Optional.empty())).limitAccepted());

            server.clearRequests();
            PlanNode.Aggregation count = new PlanNode.Aggregation(List.of(), List.of(
                    new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false, "n", null, List.of()),
                    new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false, "m", null,
                            List.of())));
            ScanRequest counting = request(List.of(cmp(ComparisonOp.LT, col("age"), 40)), OptionalLong.empty(),
                    List.of(), Optional.of(count));
            assertTrue(c.plan(people("v4"), counting).aggregationAccepted());
            assertEquals(List.of(List.of(4L, 4L)), ODataConnectorTest.read(c, people("v4"), counting));
            assertEquals(List.of(Map.of("$filter", "age lt 40")), server.requests("/v4/People/$count").stream()
                    .map(FakeODataServer.Request::parameters).toList());
            assertEquals("text/plain", server.requests("/v4/People/$count").get(0).header("Accept"));
            assertEquals(List.of(), queries("v4"));
            PlanNode.Aggregation sum = new PlanNode.Aggregation(List.of(), List.of(new PlanNode.AggregateCall(
                    PlanNode.AggregateFunction.SUM, 2, false, "s", null, List.of())));
            assertFalse(c.plan(people("v4"), request(List.of(), OptionalLong.empty(), List.of(), Optional.of(sum)))
                    .aggregationAccepted());
        }
    }

    @Test
    void aServiceThatIgnoresTopStillGivesTheLimitAndTheCountChecksTheRecordsOfTheLimit() {
        service("v2", ODataVersion.V2).ignoreTop(true).pageSize(3).entitySet("People", PEOPLE, ROWS, "id");
        service("cut", ODataVersion.V2).cutShort(true).pageSize(2).entitySet("People", PEOPLE, ROWS, "id");
        ScanRequest limited = request(List.of(), OptionalLong.of(4), List.of(), Optional.empty());
        try (ODataConnector c = connector(false, "v2", "cut")) {
            assertEquals(List.of(1, 2, 3, 4), ids(ODataConnectorTest.read(c, people("v2"), limited)));
            assertEquals(2, queries("v2").size());
            assertEquals(List.of(), ODataConnectorTest.read(c, people("v2"), request(List.of(), OptionalLong.of(0),
                    List.of(), Optional.empty())));
            assertEquals(2, queries("v2").size());
            ODataSourceException e = assertThrows(ODataSourceException.class,
                    () -> ODataConnectorTest.read(c, people("cut"), limited));
            assertEquals("The service cut of the OData source erp answered the query of the entity set People with 2"
                    + " of 4 records and no link to the next page; the answer is incomplete", e.getMessage());
            assertEquals(List.of(1, 2), ids(ODataConnectorTest.read(c, people("cut"), request(List.of(),
                    OptionalLong.of(2), List.of(), Optional.empty()))));
        }
    }

    @Test
    void whatTheMetadataRestrictsIsNotAskedFor() {
        for (ODataVersion version : ODataVersion.values()) {
            String name = version.name().toLowerCase(Locale.ROOT);
            service(name, version).nonFilterable("name").nonSortable("age").topUnsupported(true)
                    .uncountableEntitySet("People", PEOPLE, ROWS, "id");
            try (ODataConnector c = connector(true, name)) {
                ScanRequest request = request(List.of(cmp(ComparisonOp.EQ, col("name"), "Ann"),
                        cmp(ComparisonOp.GT, col("id"), 1)), OptionalLong.empty(), List.of(), Optional.empty());
                assertEquals(List.of(request.filters().get(1)), c.plan(people(name), request).acceptedFilters());
                assertEquals(List.of(2, 3, 4, 5, 6, 7), ids(ODataConnectorTest.read(c, people(name), request)));
                assertEquals("id gt 1", queries(name).get(0).get("$filter"), version.name());
                assertNull(queries(name).get(0).get(version == ODataVersion.V2 ? "$inlinecount" : "$count"));
                assertFalse(c.plan(people(name), request(List.of(), OptionalLong.empty(),
                        List.of(new PlanNode.SortKey(2, false, false)), Optional.empty())).orderAccepted());
                assertTrue(c.plan(people(name), request(List.of(), OptionalLong.empty(),
                        List.of(PlanNode.SortKey.asc(0)), Optional.empty())).orderAccepted());
                assertFalse(c.plan(people(name), request(List.of(), OptionalLong.of(1), List.of(), Optional.empty()))
                        .limitAccepted());
                PlanNode.Aggregation count = new PlanNode.Aggregation(List.of(), List.of(new PlanNode.AggregateCall(
                        PlanNode.AggregateFunction.COUNT_STAR, -1, false, "n", null, List.of())));
                assertFalse(c.plan(people(name), request(List.of(), OptionalLong.empty(), List.of(),
                        Optional.of(count))).aggregationAccepted());
                assertEquals(OptionalLong.empty(), c.catalog().rowCount(people(name)));
            }
            assertEquals(List.of(), server.requests("/" + name + "/People/$count"));
        }
    }

    @Test
    void rowCountsAreAskedForOnceUntilRefreshed() {
        service("v2", ODataVersion.V2).entitySet("People", PEOPLE, ROWS, "id");
        try (ODataConnector c = connector(false, "v2")) {
            assertEquals(OptionalLong.of(7), c.catalog().rowCount(people("v2")));
            assertEquals(OptionalLong.of(7), c.catalog().rowCount(people("v2")));
            assertEquals(1, server.requests("/v2/People/$count").size());
            assertEquals(Map.of(), server.requests("/v2/People/$count").get(0).parameters());
            assertEquals("2.0", server.requests("/v2/People/$count").get(0).header("MaxDataServiceVersion"));
            c.refresh();
            assertEquals(OptionalLong.of(7), c.catalog().rowCount(people("v2")));
            assertEquals(2, server.requests("/v2/People/$count").size());
        }
        try (ODataConnector c = ODataConnector.builder("erp").service("v2", server.url("v2")).build()) {
            assertEquals(OptionalLong.empty(), c.catalog().rowCount(people("v2")));
            assertEquals(2, server.requests("/v2/People/$count").size());
        }
    }

    @Test
    void failedCounts() {
        service("v4", ODataVersion.V4).failing(503).entitySet("People", PEOPLE, ROWS, "id");
        try (ODataConnector c = connector(false, "v4")) {
            ODataSourceException e = assertThrows(ODataSourceException.class, () -> c.catalog().rowCount(
                    people("v4")));
            assertEquals("The service v4 of the OData source erp answered the query of the entity set People at "
                    + server.url("v4") + "People/$count with the status 503: The count of People failed (Failed)",
                    e.getMessage());
        }
    }

    @Test
    void theBuilderChecksTheServicesItTrusts() {
        assertEquals("The OData source erp has no service other to trust with filters",
                assertThrows(IllegalArgumentException.class, () -> ODataConnector.builder("erp")
                        .url("http://h/s/").trustFilters("other", true).build()).getMessage());
    }
}
