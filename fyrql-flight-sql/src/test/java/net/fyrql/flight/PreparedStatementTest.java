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
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.parse.Parameters;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prepared statements through the server: the schemas of the result and of
 * the parameters, a row of values of every type, the rows of the query
 * with literals, values that keep their own Arrow types, a column of the
 * type {@code Null}, and errors.
 */
class PreparedStatementTest {

    /** A value of a type. */
    private record Value(DataType type, Object value) {
    }

    private static final DataType STRUCT = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
            new DataType.Struct.Field("b", DataType.VARCHAR));

    private static final List<Value> VALUES = List.of(
            new Value(DataType.BOOLEAN, true),
            new Value(DataType.TINYINT, (byte) -7),
            new Value(DataType.SMALLINT, (short) 300),
            new Value(DataType.INTEGER, Integer.MIN_VALUE),
            new Value(DataType.BIGINT, Long.MAX_VALUE),
            new Value(DataType.HUGEINT, BigInteger.TEN.pow(37).negate()),
            new Value(DataType.FLOAT, 1.5f),
            new Value(DataType.DOUBLE, -2.25e-300),
            new Value(DataType.decimal(10, 2), new BigDecimal("-12.34")),
            new Value(DataType.decimal(38, 10), new BigDecimal("1234567890123456789012345678.0123456789")),
            new Value(DataType.VARCHAR, "it's ? -- not a comment"),
            new Value(DataType.BLOB, new byte[] {0, (byte) 0xff, 39}),
            new Value(DataType.DATE, (int) LocalDate.of(2024, 2, 29).toEpochDay()),
            new Value(DataType.TIMESTAMP, Conversions.toMicros(LocalDateTime.of(1999, 12, 31, 23, 59, 58,
                    123_456_000))),
            new Value(DataType.UUID, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
            new Value(DataType.listOf(DataType.INTEGER), Arrays.asList(1, null, 3)),
            new Value(DataType.listOf(DataType.VARCHAR), List.of("a", "'b'")),
            new Value(STRUCT, Arrays.asList(1, "x")));

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

    /** Returns a root with one row of values of the types, which the caller closes. */
    private VectorSchemaRoot row(List<DataType> types, Object... values) {
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            fields.add(new Schema.Field("p" + i, types.get(i), true));
        }
        Schema schema = new Schema(fields);
        VectorSchemaRoot root = BatchToArrow.root(schema, server.allocator);
        BatchToArrow.fill(Batch.ofRows(schema, List.<Object[]>of(values)), root);
        return root;
    }

    private List<Object[]> run(FlightSqlClient.PreparedStatement p, VectorSchemaRoot parameters) throws Exception {
        p.setParameters(parameters);
        return TestServer.rows(client, p.execute());
    }

    @Test
    void everyTypeGoesInAsAParameterAndComesOutAsAValue() throws Exception {
        for (Value v : VALUES) {
            String what = v.type().sqlName();
            String sql = "SELECT ? = x AS same, coalesce(?, x) AS c FROM (SELECT "
                    + Parameters.literal(v.value(), v.type()) + " AS x) s";
            try (FlightSqlClient.PreparedStatement p = client.prepare(sql);
                    VectorSchemaRoot values = row(List.of(v.type(), v.type()), v.value(), v.value())) {
                assertEquals(List.of(v.type(), v.type()), ArrowTypes.toFyrQL(p.getParameterSchema()).types(), what);
                assertEquals(List.of(DataType.BOOLEAN, v.type()), ArrowTypes.toFyrQL(p.getResultSetSchema()).types(),
                        what);
                List<Object[]> rows = run(p, values);
                assertEquals(1, rows.size(), what);
                assertEquals(true, rows.get(0)[0], what);
                assertEquals(Casts.format(v.value(), v.type()), Casts.format(rows.get(0)[1], v.type()), what);
            }
        }
    }

    @Test
    void aPreparedStatementGivesTheRowsOfTheQueryWithTheLiterals() throws Exception {
        String sql = "SELECT n * ? AS p, s || ? AS t FROM numbers WHERE n BETWEEN ? AND ? ORDER BY n";
        try (FlightSqlClient.PreparedStatement p = client.prepare(sql)) {
            List<DataType> types = ArrowTypes.toFyrQL(p.getParameterSchema()).types();
            assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.BIGINT, DataType.BIGINT), types);
            try (VectorSchemaRoot first = row(types, 2L, "!", 10L, 12L)) {
                assertEquals(TestServer.comparable(server.engine.rows("SELECT n * 2 AS p, s || '!' AS t FROM numbers"
                        + " WHERE n BETWEEN 10 AND 12 ORDER BY n")), TestServer.comparable(run(p, first)));
            }
            try (VectorSchemaRoot second = row(types, 3L, "?", 998L, 2000L)) {
                assertEquals(List.of(List.of(2994L, "n998?"), List.of(2997L, "n999?")),
                        TestServer.comparable(run(p, second)));
            }
        }
    }

    @Test
    void valuesKeepTheirArrowTypesAsSettersOfJdbcDo() throws Exception {
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT k * ? AS p FROM tiny WHERE k = ?")) {
            assertEquals(List.of(DataType.INTEGER, DataType.INTEGER),
                    ArrowTypes.toFyrQL(p.getParameterSchema()).types());
            try (VectorSchemaRoot longs = row(List.of(DataType.BIGINT, DataType.BIGINT), 3_000_000_000L, 2L)) {
                p.setParameters(longs);
                FlightInfo info = p.execute();
                assertEquals(DataType.BIGINT, ArrowTypes.toFyrQL(TestServer.schema(info)).type(0));
                assertEquals(List.of(List.of(6_000_000_000L)), TestServer.comparable(TestServer.rows(client, info)));
            }
            try (VectorSchemaRoot ints = row(List.of(DataType.INTEGER, DataType.INTEGER), 5, 1)) {
                p.setParameters(ints);
                FlightInfo info = p.execute();
                assertEquals(DataType.INTEGER, ArrowTypes.toFyrQL(TestServer.schema(info)).type(0));
                assertEquals(List.of(List.of(5)), TestServer.comparable(TestServer.rows(client, info)));
            }
        }
    }

    @Test
    void aColumnOfTheTypeNullIsANullOfItsParameter() throws Exception {
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT k, coalesce(?, v) AS w FROM tiny ORDER BY k");
                VectorSchemaRoot nulls = new VectorSchemaRoot(List.of(new NullVector("p0", 1)))) {
            nulls.setRowCount(1);
            p.setParameters(nulls);
            FlightInfo info = p.execute();
            assertEquals(DataType.VARCHAR, ArrowTypes.toFyrQL(TestServer.schema(info)).type(1));
            assertEquals(List.of(List.of(1, "one"), List.of(2, "two")),
                    TestServer.comparable(TestServer.rows(client, info)));
        }
    }

    @Test
    void aQueryWithoutParametersRunsAsItIs() throws Exception {
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT v FROM tiny ORDER BY k")) {
            assertEquals(0, p.getParameterSchema().getFields().size());
            for (int run = 0; run < 2; run++) {
                assertEquals(List.of(List.of("one"), List.of("two")),
                        TestServer.comparable(TestServer.rows(client, p.execute())));
            }
        }
        try (FlightSqlClient.PreparedStatement p = client.prepare("EXPLAIN SELECT v FROM tiny")) {
            assertEquals(List.of("plan"), p.getResultSetSchema().getFields().stream().map(f -> f.getName()).toList());
            assertEquals(server.engine.explain("SELECT v FROM tiny").split("\n").length,
                    TestServer.rows(client, p.execute()).size());
        }
        try (FlightSqlClient.PreparedStatement p = client.prepare("EXPLAIN ANALYZE SELECT v FROM tiny")) {
            assertEquals(List.of("plan"), p.getResultSetSchema().getFields().stream().map(f -> f.getName()).toList());
            List<List<Object>> plan = TestServer.comparable(TestServer.rows(client, p.execute()));
            assertTrue(String.valueOf(plan.get(0).get(0)).startsWith("Analyzed in "), plan.toString());
        }
    }

    @Test
    void wrongValuesAreErrors() throws Exception {
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT k FROM tiny WHERE k BETWEEN ? AND ?")) {
            TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "The prepared statement has 2 parameters and no"
                    + " values", p::execute);
            try (VectorSchemaRoot one = row(List.of(DataType.INTEGER), 1)) {
                TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "The query has 2 parameters, not 1 values",
                        () -> run(p, one));
            }
            List<DataType> types = List.of(DataType.INTEGER, DataType.INTEGER);
            try (VectorSchemaRoot two = BatchToArrow.root(new Schema(List.of(new Schema.Field("a", DataType.INTEGER),
                    new Schema.Field("b", DataType.INTEGER))), server.allocator)) {
                BatchToArrow.fill(Batch.ofRows(ArrowTypes.toFyrQL(two.getSchema()), List.of(new Object[] {1, 2},
                        new Object[] {3, 4})), two);
                TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A prepared statement of FyrQL takes one row of"
                        + " values, not 2", () -> run(p, two));
            }
            try (VectorSchemaRoot good = row(types, 1, 1)) {
                assertEquals(List.of(List.of(1)), TestServer.comparable(run(p, good)));
            }
        }
        TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "Not supported: a parameter in LIMIT or OFFSET",
                () -> client.prepare("SELECT k FROM tiny LIMIT ? + 1"));
        try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT k FROM tiny")) {
            TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "A query returns rows, not a number of rows written",
                    p::executeUpdate);
        }
    }

    @Test
    void closingAPreparedStatementForgetsIt() throws Exception {
        FlightSqlClient.PreparedStatement p = client.prepare("SELECT k FROM tiny");
        assertEquals(1, server.server.handles());
        TestServer.rows(client, p.execute());
        assertEquals(1, server.server.handles(), "the ticket of the run is redeemed");
        p.close();
        assertEquals(0, server.server.handles());
    }
}
