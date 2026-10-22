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

import net.fyrql.arrow.ArrowTypes;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.jdbc.AllTypesTable;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.FlightSqlColumnMetadata;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queries through the server: the schema of the flight and of the stream,
 * the rows of every type as the engine gives them, many batches, an empty
 * result, {@code EXPLAIN}, and errors with the status and the message of
 * FyrQL.
 */
class StatementTest {

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

    @Test
    void everyTypeComesAsTheEngineGivesIt() throws Exception {
        String sql = "SELECT * FROM t";
        PlanNode plan = server.engine.plan(sql);
        FlightInfo info = client.execute(sql);
        assertEquals(plan.schema(), ArrowTypes.toFyrQL(TestServer.schema(info)));
        assertEquals(1, info.getEndpoints().size());
        assertTrue(info.getEndpoints().get(0).getLocations().isEmpty(), "the endpoint is on this server");
        List<Batch> batches = TestServer.batches(client, info);
        assertEquals(plan.schema(), batches.get(0).schema());
        assertEquals(TestServer.comparable(server.engine.rows(plan)), TestServer.comparable(batches.stream()
                .flatMap(b -> b.rows().stream()).toList()));
        assertEquals(0, server.server.handles(), "the ticket is redeemed once");
    }

    @Test
    void eachColumnCarriesItsTypeOfFyrQL() throws Exception {
        FlightInfo info = client.execute("SELECT dec, hi, u, m FROM t");
        List<String> names = TestServer.schema(info).getFields().stream()
                .map(f -> new FlightSqlColumnMetadata(f.getMetadata()).getTypeName()).toList();
        assertEquals(List.of("DECIMAL(38,10)", "HUGEINT", "UUID", "MAP(VARCHAR, STRUCT(x DATE))"), names);
        Field dec = TestServer.schema(info).getFields().get(0);
        assertEquals(38, new FlightSqlColumnMetadata(dec.getMetadata()).getPrecision());
        assertEquals(10, new FlightSqlColumnMetadata(dec.getMetadata()).getScale());
        assertEquals(DataType.HUGEINT, ArrowTypes.toFyrQL(TestServer.schema(info).getFields().get(1)));
        assertEquals(TestServer.schema(info), client.getExecuteSchema("SELECT dec, hi, u, m FROM t").getSchema());
        TestServer.rows(client, info);
    }

    @Test
    void aLargeResultComesInManyBatches() throws Exception {
        List<Batch> batches = TestServer.batches(client, client.execute("SELECT n, s FROM numbers"));
        assertTrue(batches.size() > 1, batches.size() + " batches");
        List<Object[]> rows = new ArrayList<>();
        for (Batch b : batches) {
            rows.addAll(b.rows());
        }
        rows.sort(Comparator.comparingLong(r -> (Long) r[0]));
        assertEquals(TestServer.NUMBERS, rows.size());
        for (int n = 0; n < rows.size(); n++) {
            assertEquals((long) n, rows.get(n)[0]);
            assertEquals("n" + n, rows.get(n)[1]);
        }
    }

    @Test
    void anEmptyResultHasItsSchema() throws Exception {
        FlightInfo info = client.execute("SELECT k, v FROM tiny WHERE k > 5");
        assertEquals(List.of("k", "v"), TestServer.schema(info).getFields().stream().map(Field::getName).toList());
        assertEquals(List.of(), TestServer.rows(client, info));
    }

    @Test
    void explainGivesThePlanOneLinePerRow() throws Exception {
        List<Object[]> rows = TestServer.rows(client, client.execute("explain SELECT k FROM tiny WHERE k = 1"));
        String plan = server.engine.explain("SELECT k FROM tiny WHERE k = 1");
        assertEquals(List.of(plan.split("\n")), rows.stream().map(r -> (String) r[0]).toList());
    }

    @Test
    void errorsHaveTheStatusAndTheMessageOfFyrQL() throws Exception {
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Syntax error", () -> client.execute("SELEC 1"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Table with name nope does not exist",
                () -> client.execute("SELECT * FROM nope"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Not supported: parameters outside a prepared query",
                () -> client.execute("SELECT ? + 1"));
        FlightInfo info = client.execute("SELECT CAST(v AS INTEGER) FROM tiny");
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "", () -> TestServer.rows(client, info));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Only queries, COPY, CREATE TABLE, INSERT INTO and"
                + " DROP TABLE are supported, not DELETE", () -> client.executeUpdate("DELETE FROM tiny"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A query returns rows, not a number of rows written",
                () -> client.executeUpdate("SELECT 1"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Table tiny of the memory source mem holds Java objects",
                () -> client.executeUpdate("INSERT INTO tiny VALUES (3, 'three')"));
        TestServer.fails(FlightStatusCode.NOT_FOUND, "The statement is unknown",
                () -> TestServer.rows(client, info));
        server.awaitNoQuery();
    }

    @Test
    void aRowOfAllTypesTableComesBackAsItWent() throws Exception {
        List<Object[]> rows = TestServer.rows(client, client.execute("SELECT * FROM t"));
        assertEquals(TestServer.comparable(TestServer.allTypes()), TestServer.comparable(rows));
        assertEquals(AllTypesTable.SCHEMA.size(), rows.get(0).length);
    }
}
