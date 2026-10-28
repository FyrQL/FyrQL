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
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.jdbc.AllTypesTable;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.FlightSqlColumnMetadata;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.flight.sql.util.TableRef;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metadata of the server: sources as catalogs, schemas, tables with
 * their schemas, table types, what the server supports, and the types, each
 * with the fixed schema of Flight SQL and filtered by patterns.
 */
class MetadataTest {

    private static TestServer server;
    private static FlightSqlClient client;

    @BeforeAll
    static void open() throws Exception {
        server = new TestServer();
        client = server.client();
    }

    @AfterAll
    static void close() throws Exception {
        server.close();
    }

    private static List<List<Object>> rows(FlightInfo info, Schema expected) throws Exception {
        assertEquals(expected, TestServer.schema(info));
        return TestServer.comparable(TestServer.rows(client, info));
    }

    @Test
    void theSourcesAreTheCatalogs() throws Exception {
        assertEquals(List.of(List.of("mem")), rows(client.getCatalogs(),
                FlightSqlProducer.Schemas.GET_CATALOGS_SCHEMA));
        assertEquals(List.of(List.of("mem", "main")), rows(client.getSchemas(null, null),
                FlightSqlProducer.Schemas.GET_SCHEMAS_SCHEMA));
        assertEquals(List.of(List.of("mem", "main")), rows(client.getSchemas("mem", "m%"),
                FlightSqlProducer.Schemas.GET_SCHEMAS_SCHEMA));
        assertEquals(List.of(), rows(client.getSchemas("other", null), FlightSqlProducer.Schemas.GET_SCHEMAS_SCHEMA));
        assertEquals(List.of(List.of("TABLE"), List.of("VIEW")), rows(client.getTableTypes(),
                FlightSqlProducer.Schemas.GET_TABLE_TYPES_SCHEMA));
    }

    @Test
    void aViewIsATableOfTheTypeView() throws Exception {
        client.executeUpdate("CREATE VIEW mem.main.small AS SELECT * FROM tiny");
        try {
            assertEquals(List.of(List.of("mem", "main", "small", "VIEW")), rows(client.getTables(null, null, "s%",
                    null, false), FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA));
            assertEquals(List.of(List.of("mem", "main", "small", "VIEW")), rows(client.getTables(null, null, null,
                    List.of("view"), false), FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA));
            assertEquals(List.of(), rows(client.getTables(null, null, "small", List.of("TABLE"), false),
                    FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA));
            FlightInfo info = client.getTables(null, null, "small", null, true);
            List<Object[]> withSchema = TestServer.rows(client, info);
            assertEquals(1, withSchema.size());
        } finally {
            client.executeUpdate("DROP VIEW mem.main.small");
        }
    }

    @Test
    void theTablesMatchThePatterns() throws Exception {
        assertEquals(List.of(List.of("mem", "main", "numbers", "TABLE"), List.of("mem", "main", "t", "TABLE"),
                List.of("mem", "main", "tiny", "TABLE")), sorted(rows(client.getTables(null, null, null, null, false),
                FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA)));
        assertEquals(List.of(List.of("mem", "main", "t", "TABLE"), List.of("mem", "main", "tiny", "TABLE")),
                sorted(rows(client.getTables("mem", "main", "t%", List.of("TABLE"), false),
                        FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA)));
        assertEquals(List.of(), rows(client.getTables(null, null, "t%", List.of("VIEW"), false),
                FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA));
        assertEquals(List.of(List.of("mem", "main", "tiny", "TABLE")), rows(client.getTables(null, null, "TI_Y", null,
                false), FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA));
    }

    @Test
    void aTableComesWithItsSchema() throws Exception {
        FlightInfo info = client.getTables("mem", null, "t", null, true);
        assertEquals(FlightSqlProducer.Schemas.GET_TABLES_SCHEMA, TestServer.schema(info));
        List<Object[]> rows = TestServer.rows(client, info);
        assertEquals(1, rows.size());
        Schema schema = MessageSerializer.deserializeSchema(new ReadChannel(Channels.newChannel(
                new ByteArrayInputStream((byte[]) rows.get(0)[4]))));
        assertEquals(AllTypesTable.SCHEMA, ArrowTypes.toFyrQL(schema));
        FlightSqlColumnMetadata dec = new FlightSqlColumnMetadata(schema.findField("dec").getMetadata());
        assertEquals("DECIMAL(38,10)", dec.getTypeName());
        assertEquals("mem", dec.getCatalogName());
        assertEquals("main", dec.getSchemaName());
        assertEquals("t", dec.getTableName());
    }

    @Test
    void thereAreNoKeysButPrimaryKeys() throws Exception {
        TableRef t = TableRef.of("mem", "main", "t");
        assertEquals(List.of(), rows(client.getPrimaryKeys(t), FlightSqlProducer.Schemas.GET_PRIMARY_KEYS_SCHEMA));
        client.executeUpdate("CREATE TABLE mem.keyed (a INTEGER, b VARCHAR, PRIMARY KEY (b, a))");
        try {
            assertEquals(List.of(List.of("mem", "main", "keyed", "b", 1, "keyed_pkey"),
                    List.of("mem", "main", "keyed", "a", 2, "keyed_pkey")), rows(client.getPrimaryKeys(
                            TableRef.of(null, null, "keyed")), FlightSqlProducer.Schemas.GET_PRIMARY_KEYS_SCHEMA));
        } finally {
            client.executeUpdate("DROP TABLE mem.keyed");
        }
        assertEquals(List.of(), rows(client.getExportedKeys(t), FlightSqlProducer.Schemas.GET_EXPORTED_KEYS_SCHEMA));
        assertEquals(List.of(), rows(client.getImportedKeys(t), FlightSqlProducer.Schemas.GET_IMPORTED_KEYS_SCHEMA));
        assertEquals(List.of(), rows(client.getCrossReference(t, t),
                FlightSqlProducer.Schemas.GET_CROSS_REFERENCE_SCHEMA));
    }

    @Test
    void theTypesHaveTheCodesOfJdbc() throws Exception {
        FlightInfo info = client.getXdbcTypeInfo();
        assertEquals(FlightSqlProducer.Schemas.GET_TYPE_INFO_SCHEMA, TestServer.schema(info));
        List<Batch> batches = TestServer.batches(client, info);
        List<Object[]> rows = batches.get(0).rows();
        assertEquals(DataType.Scalar.values().length + 4, rows.size());
        Map<String, Integer> codes = new HashMap<>();
        int last = Integer.MIN_VALUE;
        for (Object[] row : rows) {
            codes.put((String) row[0], (Integer) row[1]);
            assertTrue((Integer) row[1] >= last, "ordered by the code");
            last = (Integer) row[1];
        }
        assertEquals(Types.INTEGER, codes.get("INTEGER"));
        assertEquals(Types.DECIMAL, codes.get("DECIMAL"));
        assertEquals(Types.ARRAY, codes.get("LIST"));
        assertEquals(Types.VARCHAR, codes.get("VARCHAR"));
        List<Object[]> decimal = TestServer.rows(client, client.getXdbcTypeInfo(Types.DECIMAL));
        assertEquals(1, decimal.size());
        assertEquals(List.of("precision", "scale"), decimal.get(0)[5]);
        assertEquals(38, decimal.get(0)[14]);
    }

    @Test
    void theServerTellsWhatItSupports() throws Exception {
        FlightInfo info = client.getSqlInfo(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_VERSION, FlightSql.SqlInfo.FLIGHT_SQL_SERVER_ARROW_VERSION,
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY, FlightSql.SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR);
        assertEquals(FlightSqlProducer.Schemas.GET_SQL_INFO_SCHEMA, TestServer.schema(info));
        Map<Integer, Object> values = sqlInfo(info);
        assertEquals("FyrQL", String.valueOf(values.get(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE)));
        assertEquals(FlightSqlServer.VERSION, String.valueOf(values.get(
                FlightSql.SqlInfo.FLIGHT_SQL_SERVER_VERSION_VALUE)));
        assertEquals("19.0.0", String.valueOf(values.get(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_ARROW_VERSION_VALUE)));
        assertEquals(false, values.get(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY_VALUE));
        assertEquals("\"", String.valueOf(values.get(FlightSql.SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR_VALUE)));
        assertTrue(sqlInfo(client.getSqlInfo(new int[0])).size() > 20, "all infos without a list");
    }

    /** Reads the values of a flight of infos, which come in a union that FyrQL does not read. */
    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    private static Map<Integer, Object> sqlInfo(FlightInfo info) throws Exception {
        Map<Integer, Object> values = new HashMap<>();
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                UInt4Vector names = (UInt4Vector) root.getVector("info_name");
                DenseUnionVector value = (DenseUnionVector) root.getVector("value");
                for (int r = 0; r < root.getRowCount(); r++) {
                    values.put(names.get(r), value.getObject(r));
                }
            }
        }
        return values;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        return rows.stream().sorted((a, b) -> String.valueOf(a.get(2)).compareTo(String.valueOf(b.get(2)))).toList();
    }
}
