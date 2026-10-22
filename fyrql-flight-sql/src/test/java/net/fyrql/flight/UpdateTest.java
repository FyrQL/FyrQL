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

import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statements that write through the server: as updates, with the number
 * of rows written; as queries, which run when the client asks for their
 * flight info; and prepared, once for each row of values.
 */
class UpdateTest {

    private static final Schema ROW = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("v", DataType.VARCHAR));

    private TestServer server;
    private FlightSqlClient client;

    @BeforeEach
    void open() throws Exception {
        server = new TestServer();
        client = server.client();
        assertEquals(0, client.executeUpdate("CREATE TABLE mem.w (k INTEGER NOT NULL, v VARCHAR)"));
    }

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    /** Returns a root with rows of values of {@link #ROW}, which the caller closes. */
    private VectorSchemaRoot values(Object[]... rows) {
        VectorSchemaRoot root = BatchToArrow.root(ROW, server.allocator);
        BatchToArrow.fill(Batch.ofRows(ROW, List.of(rows)), root);
        return root;
    }

    private List<List<Object>> rows(String sql) throws Exception {
        return TestServer.comparable(TestServer.rows(client, client.execute(sql)));
    }

    @Test
    void anUpdateAnswersWithTheRowsWritten() throws Exception {
        assertEquals(2, client.executeUpdate("INSERT INTO mem.w SELECT k, v FROM tiny"));
        assertEquals(TestServer.NUMBERS, client.executeUpdate("CREATE TABLE mem.copied AS SELECT * FROM numbers"));
        assertEquals(List.of(List.of(2L, (long) TestServer.NUMBERS)),
                rows("SELECT (SELECT count(*) FROM w), (SELECT count(*) FROM copied)"));
        assertEquals(0, client.executeUpdate("DROP TABLE mem.copied"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Table with name w already exists",
                () -> client.executeUpdate("CREATE TABLE mem.w AS SELECT 1 AS k"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "NOT NULL constraint failed: w.k",
                () -> client.executeUpdate("INSERT INTO mem.w VALUES (NULL, 'x')"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A query returns rows",
                () -> client.executeUpdate("EXPLAIN INSERT INTO mem.w VALUES (3, 'x')"));
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"));
        assertEquals(0, server.server.runningQueries());
    }

    @Test
    void aStatementThatWritesSentAsAQueryRunsWithItsFlightInfo() throws Exception {
        assertEquals(List.of("Count"), client.getExecuteSchema("INSERT INTO mem.w VALUES (1, 'a')").getSchema()
                .getFields().stream().map(Field::getName).toList());
        assertEquals(List.of(List.of(0L)), rows("SELECT count(*) FROM w"), "the schema does not run it");
        FlightInfo info = client.execute("INSERT INTO mem.w VALUES (1, 'a'), (2, 'b')");
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"), "the flight info runs it");
        assertEquals(List.of("Count"), TestServer.schema(info).getFields().stream().map(Field::getName).toList());
        assertEquals(List.of(List.of(2L)), TestServer.comparable(TestServer.rows(client, info)));
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"), "the ticket does not run it again");
        List<List<Object>> plan = rows("EXPLAIN INSERT INTO mem.w VALUES (3, 'c')");
        assertTrue(String.valueOf(plan.get(0).get(0)).startsWith("Insert into mem.main.w"), plan.toString());
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"));
    }

    @Test
    void explainAnalyzeOfAStatementThatWritesIsAQueryThatWrites() throws Exception {
        assertEquals(List.of("plan"), client.getExecuteSchema("EXPLAIN ANALYZE INSERT INTO mem.w VALUES (1, 'a')")
                .getSchema().getFields().stream().map(Field::getName).toList());
        assertEquals(List.of(List.of(0L)), rows("SELECT count(*) FROM w"), "the schema does not run it");
        List<List<Object>> plan = rows("EXPLAIN ANALYZE INSERT INTO mem.w VALUES (1, 'a'), (2, 'b')");
        assertTrue(String.valueOf(plan.get(0).get(0)).matches("Analyzed in .*: 2 rows written, .*"), plan.toString());
        assertEquals("Insert into mem.main.w", plan.get(1).get(0));
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"));
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A query returns rows",
                () -> client.executeUpdate("EXPLAIN ANALYZE INSERT INTO mem.w VALUES (3, 'c')"));
        assertEquals(List.of(List.of(2L)), rows("SELECT count(*) FROM w"));
        plan = rows("EXPLAIN ANALYZE SELECT v FROM tiny");
        assertTrue(String.valueOf(plan.get(0).get(0)).matches("Analyzed in .*: 2 rows, .*"), plan.toString());
        assertEquals(0, server.server.runningQueries());
    }

    @Test
    void aPreparedStatementWritesOnceForEachRowOfValues() throws Exception {
        try (FlightSqlClient.PreparedStatement p = client.prepare("INSERT INTO mem.w VALUES (?, ?)")) {
            assertTrue(p.getResultSetSchema().getFields().isEmpty(), "a statement that writes has no columns");
            assertEquals(List.of("$1", "$2"), p.getParameterSchema().getFields().stream().map(Field::getName)
                    .toList());
            try (VectorSchemaRoot rows = values(new Object[] {1, "a"}, new Object[] {2, null},
                    new Object[] {3, "c"})) {
                p.setParameters(rows);
                assertEquals(3, p.executeUpdate());
            }
            try (VectorSchemaRoot rows = values(new Object[] {4, "d"}, new Object[] {null, "e"},
                    new Object[] {6, "f"})) {
                p.setParameters(rows);
                TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Row 2 of the values fails, after 1 rows written:"
                        + " NOT NULL constraint failed: w.k", p::executeUpdate);
            }
            try (VectorSchemaRoot row = values(new Object[] {7, "g"})) {
                p.setParameters(row);
                assertEquals(List.of(List.of(1L)), TestServer.comparable(TestServer.rows(client, p.execute())),
                        "a prepared statement that writes sent as a query runs with its flight info");
            }
        }
        try (FlightSqlClient.PreparedStatement p = client.prepare("INSERT INTO mem.w SELECT k + 10, v FROM tiny")) {
            assertEquals(2, p.executeUpdate(), "a statement without parameters runs once");
        }
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT k FROM w")) {
            TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A query returns rows", p::executeUpdate);
        }
        assertEquals(List.of(List.of(1, "a"), Arrays.asList(2, null), List.of(3, "c"), List.of(4, "d"), List.of(7, "g"),
                List.of(11, "one"), List.of(12, "two")), rows("SELECT k, v FROM w ORDER BY k"));
        assertEquals(0, server.server.runningQueries());
    }
}
