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

import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads entity sets of services of version 2 and 4 of the fake server through the connector. */
class ODataConnectorTest {

    static final DataType ADDRESS = DataType.struct(new DataType.Struct.Field("street", DataType.VARCHAR),
            new DataType.Struct.Field("zip", DataType.INTEGER), new DataType.Struct.Field("since", DataType.TIMESTAMP));

    static final DataType STOP = DataType.struct(new DataType.Struct.Field("city", DataType.VARCHAR),
            new DataType.Struct.Field("at", DataType.TIMESTAMP));

    /** A column of every type that version 4 has. */
    static final Schema V4_TYPES = new Schema(List.of(new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("flag", DataType.BOOLEAN, true), new Schema.Field("tiny", DataType.TINYINT, true),
            new Schema.Field("small", DataType.SMALLINT, true), new Schema.Field("octet", DataType.SMALLINT, true),
            new Schema.Field("big", DataType.BIGINT, true), new Schema.Field("real", DataType.FLOAT, true),
            new Schema.Field("dbl", DataType.DOUBLE, true), new Schema.Field("amount", DataType.decimal(12, 2), true),
            new Schema.Field("name", DataType.VARCHAR, true), new Schema.Field("guid", DataType.UUID, true),
            new Schema.Field("bytes", DataType.BLOB, true), new Schema.Field("day", DataType.DATE, true),
            new Schema.Field("at", DataType.TIMESTAMP, true), new Schema.Field("clock", DataType.VARCHAR, true),
            new Schema.Field("address", ADDRESS, true),
            new Schema.Field("tags", DataType.listOf(DataType.VARCHAR), true),
            new Schema.Field("stops", DataType.listOf(STOP), true)));

    /** A column of every type that version 2 has. */
    static final Schema V2_TYPES = new Schema(List.of(new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("flag", DataType.BOOLEAN, true), new Schema.Field("tiny", DataType.TINYINT, true),
            new Schema.Field("small", DataType.SMALLINT, true), new Schema.Field("octet", DataType.SMALLINT, true),
            new Schema.Field("big", DataType.BIGINT, true), new Schema.Field("real", DataType.FLOAT, true),
            new Schema.Field("dbl", DataType.DOUBLE, true), new Schema.Field("amount", DataType.decimal(12, 2), true),
            new Schema.Field("name", DataType.VARCHAR, false), new Schema.Field("guid", DataType.UUID, true),
            new Schema.Field("bytes", DataType.BLOB, true), new Schema.Field("at", DataType.TIMESTAMP, true),
            new Schema.Field("stamp", DataType.TIMESTAMP, true), new Schema.Field("time", DataType.VARCHAR, true),
            new Schema.Field("address", ADDRESS, true)));

    private final FakeODataServer server = FakeODataServer.start();

    @AfterEach
    void stop() {
        server.close();
    }

    static long micros(String timestamp) {
        return Conversions.parseTimestamp(timestamp);
    }

    static int day(String date) {
        return Conversions.parseDate(date);
    }

    static List<Object[]> v4Rows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {1, true, (byte) -128, (short) -32768, (short) 255, Long.MIN_VALUE, 1.5f, 1.0E300,
                new BigDecimal("1234567890.12"), "Ann", UUID.fromString("005056a2-0d31-1ee1-b4b6-56d0e1a5c3e8"),
                new byte[] {0, 1, 2, -1}, day("2024-01-02"), micros("2024-01-02 03:04:05.123456"), "13:20:00",
                Arrays.asList("Main St", 12345, micros("1999-12-31 23:59:59")), List.of("a", "b"),
                List.of(Arrays.asList("Rome", micros("2024-05-06 07:08:09")))});
        rows.add(new Object[] {2, false, (byte) 127, (short) 32767, (short) 0, Long.MAX_VALUE, Float.NaN,
                Double.NEGATIVE_INFINITY, new BigDecimal("-0.01"), "O'Neil & \"Sons\" \u00E4 \u20AC",
                UUID.fromString("00000000-0000-0000-0000-000000000000"), new byte[0], day("1969-12-31"),
                micros("1969-12-31 23:59:59.999999"), "00:00:00.5", Arrays.asList(null, null, null),
                Arrays.asList("x", null), List.of()});
        rows.add(new Object[] {3, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null});
        rows.add(new Object[] {4, true, (byte) 0, (short) 0, (short) 1, 0L, 0.0f, Double.POSITIVE_INFINITY,
                new BigDecimal("0.00"), "", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                new byte[] {62, 63, -2}, day("0001-01-01"), micros("2000-02-29 00:00:00"), "23:59:59",
                Arrays.asList("", 0, null), List.of(), Arrays.asList(Arrays.asList(null, null), null)});
        rows.add(new Object[] {5, false, (byte) 1, (short) 1, (short) 2, 1L, -2.5f, -1.0E-300,
                new BigDecimal("99.90"), "last", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new byte[] {1}, day("9999-12-31"), micros("9999-12-31 21:59:59"), "12:00:00",
                Arrays.asList("x", -1, micros("1970-01-01 00:00:00")), List.of("z"), List.of()});
        return rows;
    }

    static List<Object[]> v2Rows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {1, true, (byte) -128, (short) -32768, (short) 255, Long.MIN_VALUE, 1.5f, 1.0E300,
                new BigDecimal("1234567890.12"), "Ann", UUID.fromString("005056a2-0d31-1ee1-b4b6-56d0e1a5c3e8"),
                new byte[] {0, 1, 2, -1, -2, 62}, micros("2024-01-02 03:04:05.123"), micros("2024-01-02 03:04:05"),
                "PT13H20M", Arrays.asList("Main St", 12345, micros("1999-12-31 23:59:59"))});
        rows.add(new Object[] {2, false, (byte) 127, (short) 32767, (short) 0, Long.MAX_VALUE, Float.NaN,
                Double.NEGATIVE_INFINITY, new BigDecimal("-0.01"), "O'Neil & \"Sons\" \u00E4 \u20AC",
                UUID.fromString("00000000-0000-0000-0000-000000000000"), new byte[0],
                micros("1969-12-31 23:59:59.999"), micros("1900-01-01 00:00:00"), "PT0S",
                Arrays.asList(null, null, null)});
        rows.add(new Object[] {3, null, null, null, null, null, null, null, null, "", null, null, null, null, null,
                null});
        rows.add(new Object[] {4, true, (byte) 0, (short) 0, (short) 1, 0L, 0.0f, Double.POSITIVE_INFINITY,
                new BigDecimal("0.00"), " ", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                new byte[] {63}, micros("2000-02-29 00:00:00"), micros("2000-02-29 00:00:00"), "PT23H59M59S",
                Arrays.asList("", 0, null)});
        rows.add(new Object[] {5, false, (byte) 1, (short) 1, (short) 2, 1L, -2.5f, -1.0E-300,
                new BigDecimal("99.90"), "last", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new byte[] {1}, micros("9999-12-31 23:59:59.999"), micros("1970-01-01 00:00:00"), "PT1H",
                Arrays.asList("x", -1, micros("1970-01-01 00:00:00"))});
        return rows;
    }

    /** Adds the entity set {@code Things} of every type of version 2 to a new service. */
    FakeODataServer.Service v2Service(String name) {
        return server.service(name, ODataVersion.V2).edmType("octet", "Edm.Byte")
                .edmType("stamp", "Edm.DateTimeOffset").edmType("time", "Edm.Time")
                .entitySet("Things", V2_TYPES, v2Rows(), "id");
    }

    /** Adds the entity set {@code Things} of every type of version 4 to a new service. */
    FakeODataServer.Service v4Service(String name) {
        return server.service(name, ODataVersion.V4).edmType("octet", "Edm.Byte")
                .edmType("clock", "Edm.TimeOfDay").entitySet("Things", V4_TYPES, v4Rows(), "id");
    }

    ODataConnector.Builder builder(String... services) {
        ODataConnector.Builder b = ODataConnector.builder("erp");
        for (String s : services) {
            b.service(s, server.url(s));
        }
        return b;
    }

    /** Reads what a scan request reads of a table. */
    static List<List<Object>> read(Connector c, TableRef table, ScanRequest request) {
        ScanPlan plan = c.plan(table, request);
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(comparableValues(Arrays.asList(b.row(r))));
            }
        }
        return rows;
    }

    /** Reads whole columns of a table. */
    static List<List<Object>> read(Connector c, TableRef table, Integer... columns) {
        return read(c, table, ScanRequest.columns(List.of(columns)));
    }

    /** Reads all columns of a table. */
    static List<List<Object>> readAll(Connector c, TableRef table) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < c.catalog().schema(table).size(); i++) {
            all.add(i);
        }
        return read(c, table, ScanRequest.columns(all));
    }

    /** Returns rows with the bytes of binary values in hexadecimal, which compare by value. */
    static List<List<Object>> comparable(List<Object[]> rows) {
        List<List<Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            list.add(comparableValues(Arrays.asList(row)));
        }
        return list;
    }

    private static List<Object> comparableValues(List<?> values) {
        List<Object> list = new ArrayList<>();
        for (Object v : values) {
            list.add(v instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : v instanceof List<?> l
                    ? comparableValues(l) : v);
        }
        return list;
    }

    private static TableRef ref(String schema, String table) {
        return new TableRef("erp", schema, table);
    }

    @Test
    void everyTypeOfVersion2OverSeveralPages() {
        v2Service("sales").pageSize(2);
        try (ODataConnector c = builder("sales").batchSize(2).build()) {
            assertEquals(List.of("sales"), c.catalog().schemas());
            assertEquals(List.of("Things"), c.catalog().tables("sales"));
            assertEquals(V2_TYPES, c.catalog().schema(ref("sales", "Things")));
            assertEquals(comparable(v2Rows()), readAll(c, ref("sales", "Things")));
        }
        List<FakeODataServer.Request> queries = server.requests("/sales/Things");
        assertEquals(3, queries.size());
        // A scan of all columns selects nothing.
        assertEquals(Map.of("$inlinecount", "allpages", "$format", "json"), queries.get(0).parameters());
        assertEquals("4", queries.get(2).parameters().get("$skiptoken"));
        for (FakeODataServer.Request r : queries) {
            assertEquals("2.0", r.header("MaxDataServiceVersion"));
            assertEquals("application/json", r.header("Accept"));
            assertNull(r.header("Authorization"));
        }
        FakeODataServer.Request metadata = server.requests("/sales/$metadata").get(0);
        assertEquals("application/xml", metadata.header("Accept"));
        assertNull(metadata.header("MaxDataServiceVersion"));
    }

    @Test
    void everyTypeOfVersion4OverSeveralPages() {
        v4Service("trips").relativeLinks(true);
        try (ODataConnector c = builder("trips").pageSize(2).build()) {
            assertEquals(V4_TYPES, c.catalog().schema(ref("trips", "Things")));
            assertEquals(comparable(v4Rows()), readAll(c, ref("trips", "Things")));
        }
        List<FakeODataServer.Request> queries = server.requests("/trips/Things");
        assertEquals(3, queries.size());
        assertEquals("true", queries.get(0).parameters().get("$count"));
        assertFalse(queries.get(0).parameters().containsKey("$format"));
        for (FakeODataServer.Request r : queries) {
            assertEquals("4.0", r.header("OData-MaxVersion"));
            assertEquals("odata.maxpagesize=2", r.header("Prefer"));
        }
    }

    @Test
    void numbersAsStringsOfVersion4() {
        v4Service("trips").ieee754Compatible(true);
        try (ODataConnector c = builder("trips").build()) {
            assertEquals(comparable(v4Rows()), readAll(c, ref("trips", "Things")));
        }
    }

    @Test
    void servicesThatFailOnSelectingComplexPropertiesAreAskedForWholeEntities() {
        v4Service("trips").refusesStructuredSelect(true);
        int name = V4_TYPES.indexOf("name");
        int address = V4_TYPES.indexOf("address");
        List<List<Object>> expected = new ArrayList<>();
        for (List<Object> row : comparable(v4Rows())) {
            expected.add(Arrays.asList(row.get(0), row.get(name), row.get(address)));
        }
        try (ODataConnector c = builder("trips").build()) {
            TableRef things = ref("trips", "Things");
            assertEquals(expected, read(c, things, 0, name, address));
            assertEquals(expected, read(c, things, 0, name, address));
            assertEquals(5, read(c, things, 0, name).size());
            c.refresh();
            assertEquals(expected, read(c, things, 0, name, address));
        }
        assertEquals(List.of(true, false, false, true, true, false), server.requests("/trips/Things").stream()
                .map(r -> r.parameters().containsKey("$select")).toList());
    }

    @Test
    void theScanSelectsTheColumnsItReads() {
        v4Service("trips");
        v2Service("sales");
        try (ODataConnector c = builder("trips", "sales").build()) {
            int name = V4_TYPES.indexOf("name");
            int address = V4_TYPES.indexOf("address");
            ScanRequest fields = new ScanRequest(List.of(ColumnPath.whole(name), new ColumnPath(address,
                    List.of("zip")), new ColumnPath(address, List.of("street"))), List.of(), OptionalLong.empty(),
                    List.of(), Optional.empty());
            List<List<Object>> rows = read(c, ref("trips", "Things"), fields);
            assertEquals(Arrays.asList("Ann", 12345, "Main St"), rows.get(0));
            assertEquals(Arrays.asList(null, null, null), rows.get(2));
            assertEquals("name,address/zip,address/street", server.requests("/trips/Things").get(0).parameters()
                    .get("$select"));
            // A whole column makes the fields inside it superfluous.
            ScanRequest both = new ScanRequest(List.of(new ColumnPath(address, List.of("zip")),
                    ColumnPath.whole(address)), List.of(), OptionalLong.empty(), List.of(), Optional.empty());
            assertEquals(List.of(12345, Arrays.asList("Main St", 12345, micros("1999-12-31 23:59:59"))),
                    read(c, ref("trips", "Things"), both).get(0));
            assertEquals("address", server.requests("/trips/Things").get(1).parameters().get("$select"));

            // Version 2 selects whole properties only.
            List<List<Object>> v2 = read(c, ref("sales", "Things"), fields);
            assertEquals(List.of("Ann", Arrays.asList("Main St", 12345, micros("1999-12-31 23:59:59"))), v2.get(0));
            assertEquals("name,address", server.requests("/sales/Things").get(0).parameters().get("$select"));

            // Without columns, a scan selects the key and counts the records.
            assertEquals(5, read(c, ref("sales", "Things")).size());
            assertEquals("id", server.requests("/sales/Things").get(1).parameters().get("$select"));
        }
    }

    @Test
    void entitySetsThatCannotCountAreNotAskedToCount() {
        Schema schema = Schema.of(new Schema.Field("id", DataType.INTEGER));
        List<Object[]> rows = List.of(new Object[] {1}, new Object[] {2});
        server.service("sales", ODataVersion.V2).uncountableEntitySet("Things", schema, rows, "id");
        server.service("trips", ODataVersion.V4).uncountableEntitySet("Things", schema, rows, "id");
        try (ODataConnector c = builder("sales", "trips").build()) {
            assertEquals(List.of(List.of(1), List.of(2)), read(c, ref("sales", "Things"), 0));
            assertEquals(List.of(List.of(1), List.of(2)), read(c, ref("trips", "Things"), 0));
        }
        assertEquals(Map.of("$format", "json"), server.requests("/sales/Things").get(0).parameters());
        assertEquals(Map.of(), server.requests("/trips/Things").get(0).parameters());
    }

    @Test
    void theMetadataIsKeptUntilRefreshed() {
        v4Service("trips");
        v2Service("sales");
        try (ODataConnector c = builder("trips", "sales").build()) {
            read(c, ref("trips", "Things"), 0);
            read(c, ref("trips", "Things"), 1);
            assertEquals(1, server.requests("/trips/$metadata").size());
            assertEquals(0, server.requests("/sales/$metadata").size());
            c.refresh("trips");
            read(c, ref("trips", "Things"), 0);
            read(c, ref("sales", "Things"), 0);
            assertEquals(2, server.requests("/trips/$metadata").size());
            c.refresh();
            read(c, ref("sales", "Things"), 0);
            assertEquals(2, server.requests("/sales/$metadata").size());
        }
    }

    @Test
    void aGivenVersionOverridesThatOfTheMetadata() {
        v4Service("trips");
        try (ODataConnector c = ODataConnector.builder("erp").service("trips", server.url("trips"), ODataVersion.V2)
                .build()) {
            ConversionException e = assertThrows(ConversionException.class, () -> read(c, ref("trips", "Things"), 0));
            assertTrue(e.getMessage().startsWith("The JSON pointer \"/d/results\" points to nothing in "
                    + server.url("trips") + "Things: the object has no key \"d\""), e.getMessage());
            assertEquals("json", server.requests("/trips/Things").get(0).parameters().get("$format"));
        }
    }

    @Test
    void basicAndBearerAuthentication() {
        v2Service("sales");
        server.basic("ann", "s3cret");
        try (ODataConnector c = builder("sales").basic("ann", "s3cret").header("X-Trace", "1").build()) {
            assertEquals(5, read(c, ref("sales", "Things"), 0).size());
        }
        for (FakeODataServer.Request r : server.requests()) {
            assertEquals("Basic", r.header("Authorization"));
            assertEquals("1", r.header("X-Trace"));
        }
        try (ODataConnector c = builder("sales").basic("ann", "b4d").build()) {
            ODataSourceException e = assertThrows(ODataSourceException.class,
                    () -> c.catalog().tables("sales"));
            assertEquals("The service sales of the OData source erp answered the request of its $metadata at "
                    + server.url("sales") + "$metadata with the status 401: Not authorized (Unauthorized); the"
                    + " credentials of the source are missing or wrong", e.getMessage());
            assertEquals(401, e.status());
            assertEquals("sales", e.service());
            assertNull(e.entitySet());
            assertEquals("Not authorized (Unauthorized)", e.serviceMessage());
            assertFalse(e.getMessage().contains("b4d"));
        }
        server.bearer("t0k3n");
        try (ODataConnector c = builder("sales").bearer("t0k3n").build()) {
            assertEquals(5, read(c, ref("sales", "Things"), 0).size());
        }
        try (ODataConnector c = builder("sales").build()) {
            assertEquals(401, assertThrows(ODataSourceException.class, () -> c.catalog().tables("sales")).status());
        }
    }

    @Test
    void oauthTokensAreRenewedWhenTheServiceRefusesThem() {
        v4Service("trips");
        server.oauth("client", "secret", 3600);
        try (ODataConnector c = builder("trips").oauth2(server.tokenUrl(), "client", "secret", "api").build()) {
            assertEquals(5, read(c, ref("trips", "Things"), 0).size());
            assertEquals(5, read(c, ref("trips", "Things"), 0).size());
            assertEquals(1, server.issued());
            server.expireTokens();
            assertEquals(5, read(c, ref("trips", "Things"), 0).size());
            assertEquals(2, server.issued());
            assertEquals(4, server.requests("/trips/Things").size());
        }
        // The headers of the source do not go to the token endpoint.
        try (ODataConnector c = builder("trips").oauth2(server.tokenUrl(), "client", "wrong", null, true)
                .header("X-Api-Key", "k").build()) {
            ODataSourceException e = assertThrows(ODataSourceException.class, () -> c.catalog().tables("trips"));
            assertEquals("The token endpoint " + server.tokenUrl() + " of the OData source erp refused the client"
                    + " credentials with the status 401: invalid_client: Bad client credentials", e.getMessage());
        }
        List<FakeODataServer.Request> tokens = server.requests("/oauth/token");
        assertNull(tokens.get(tokens.size() - 1).header("X-Api-Key"));
    }

    @Test
    void servicesThatAreNotThere() throws IOException {
        try (ODataConnector c = builder("nothing").build()) {
            ODataSourceException e = assertThrows(ODataSourceException.class, () -> c.catalog().tables("nothing"));
            assertEquals("The service nothing of the OData source erp answered the request of its $metadata at "
                    + server.url("nothing") + "$metadata with the status 404: No resource /nothing/$metadata"
                    + " (NotFound)", e.getMessage());
        }
        URI closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = URI.create("http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort()
                    + "/svc/?apikey=secret");
        }
        try (ODataConnector c = ODataConnector.builder("erp").url(closed).build()) {
            ODataSourceException e = assertThrows(ODataSourceException.class, () -> c.catalog().tables("main"));
            assertTrue(e.getMessage().startsWith("The service main of the OData source erp did not answer the"
                    + " request of its $metadata: GET " + ODataQuery.shown(closed) + "$metadata failed: "),
                    e.getMessage());
            assertFalse(e.getMessage().contains("secret"), e.getMessage());
            assertEquals(-1, e.status());
        }
    }

    @Test
    void theBuilderChecksWhatItIsGiven() {
        ODataConnector.Builder b = ODataConnector.builder("erp");
        assertEquals("The OData source erp has no service", assertThrows(IllegalArgumentException.class, b::build)
                .getMessage());
        assertEquals("The URL of the service main of the OData source erp must be http or https with a host:"
                + " ftp://h/x", assertThrows(IllegalArgumentException.class, () -> b.url("ftp://h/x")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> b.url("relative/x"));
        assertEquals("The URL of the service main of the OData source erp must not have a fragment",
                assertThrows(IllegalArgumentException.class, () -> b.url("http://h/x#f")).getMessage());
        b.url("http://h/x");
        assertEquals("The OData source erp has the service main twice",
                assertThrows(IllegalArgumentException.class, () -> b.url("http://h/y")).getMessage());
        b.basic("a", "b");
        assertEquals("The OData source erp has two authentications, basic and bearer",
                assertThrows(IllegalArgumentException.class, () -> b.bearer("t")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> b.header("Host", "h"));
        assertThrows(IllegalArgumentException.class, () -> b.pageSize(0));
        assertThrows(IllegalArgumentException.class, () -> b.batchSize(0));
        assertEquals("The token URL of the OData source erp must be http or https with a host: file:/x",
                assertThrows(IllegalArgumentException.class, () -> ODataConnector.builder("erp")
                        .oauth2(URI.create("file:/x"), "a", "b", null)).getMessage());
    }
}
