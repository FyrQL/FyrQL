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

package net.fyrql.connector.parquet;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Parquet files that {@code COPY ... TO} writes, through {@link ParquetConnector#openFile}. */
class ParquetWriteTest {

    private static final DataType.Struct POINT = DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
            new DataType.Struct.Field("tags", DataType.listOf(DataType.VARCHAR)),
            new DataType.Struct.Field("at", DataType.struct(new DataType.Struct.Field("d", DataType.DATE))));

    /** A column of each type, nested ones too, and one that is NOT NULL. */
    private static final Schema ALL_TYPES = Schema.of(new Schema.Field("id", DataType.BIGINT, false),
            new Schema.Field("b", DataType.BOOLEAN), new Schema.Field("ti", DataType.TINYINT),
            new Schema.Field("si", DataType.SMALLINT), new Schema.Field("i", DataType.INTEGER),
            new Schema.Field("bi", DataType.BIGINT), new Schema.Field("f", DataType.FLOAT),
            new Schema.Field("d", DataType.DOUBLE), new Schema.Field("d9", DataType.decimal(9, 2)),
            new Schema.Field("d18", DataType.decimal(18, 4)), new Schema.Field("d38", DataType.decimal(38, 10)),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("bl", DataType.BLOB),
            new Schema.Field("dt", DataType.DATE), new Schema.Field("ts", DataType.TIMESTAMP),
            new Schema.Field("u", DataType.UUID), new Schema.Field("st", POINT),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
            new Schema.Field("ll", DataType.listOf(DataType.listOf(DataType.VARCHAR))),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.DOUBLE)));

    @TempDir
    Path dir;

    private static ParquetConnector connector() {
        return ParquetConnector.builder("pq").build();
    }

    private static long write(Path file, Schema schema, Map<String, String> options, List<Batch> batches) {
        try (Sink sink = connector().openFile("parquet", file, schema, options)) {
            for (Batch b : batches) {
                sink.write(b);
            }
            return sink.commit();
        }
    }

    /** Reads a file with the connector: its schema and its rows. */
    private static List<Object[]> read(Path file, Schema[] schema) {
        ParquetConnector c = ParquetConnector.builder("pq").path("t", file).build();
        TableRef t = TableRef.of("pq", "t");
        schema[0] = c.catalog().schema(t);
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < schema[0].size(); i++) {
            columns.add(i);
        }
        ScanPlan plan = c.plan(t, ScanRequest.columns(columns));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            for (Batch b : Batches.readAll(c.open(split))) {
                rows.addAll(b.rows());
            }
        }
        return rows;
    }

    /** Runs a query in DuckDB and returns each value as text. */
    private static List<List<String>> duckdb(String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The values as text, as their cast to {@code VARCHAR}, which is DuckDB's text of them too. */
    private static List<List<String>> text(Schema schema, List<Object[]> rows) {
        List<List<String>> text = new ArrayList<>();
        for (Object[] row : rows) {
            List<String> t = new ArrayList<>();
            for (int c = 0; c < row.length; c++) {
                t.add(row[c] == null ? null : Casts.format(row[c], schema.type(c)));
            }
            text.add(t);
        }
        return text;
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(Path::getFileName).sorted().toList();
        }
    }

    @Test
    void fyrqlAndDuckDbReadTheValuesOfEveryTypeBack() throws SQLException {
        List<Object[]> rows = DataGenerator.withSeed(20261020).generate(ALL_TYPES, 700);
        for (int r = 0; r < rows.size(); r++) {
            rows.get(r)[0] = (long) r;
        }
        List<Batch> batches = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += 128) {
            batches.add(Batch.ofRows(ALL_TYPES, rows.subList(from, Math.min(rows.size(), from + 128))));
        }
        Path file = dir.resolve("all.parquet");
        assertEquals(700, write(file, ALL_TYPES, Map.of("row_group_size", "300"), batches));

        Schema[] schema = new Schema[1];
        List<Object[]> back = read(file, schema);
        assertEquals(ALL_TYPES, schema[0]);
        assertEquals(text(ALL_TYPES, rows), text(schema[0], back));

        // DuckDB prints a NaN with its sign and a FLOAT with more digits; the values are compared as doubles.
        List<String> columns = ALL_TYPES.fields().stream().map(f -> {
            String c = "\"" + f.name() + "\"";
            return f.type() == DataType.FLOAT || f.type() == DataType.DOUBLE ? "CASE WHEN isnan(" + c
                    + ") THEN 'nan' ELSE CAST(CAST(" + c + " AS DOUBLE) AS VARCHAR) END" : "CAST(" + c + " AS VARCHAR)";
        }).toList();
        List<List<String>> expected = new ArrayList<>();
        for (Object[] row : rows) {
            List<String> t = new ArrayList<>();
            for (int c = 0; c < row.length; c++) {
                t.add(switch (row[c]) {
                    case null -> null;
                    case Float f -> f.isNaN() ? "nan" : Casts.format(f.doubleValue(), DataType.DOUBLE);
                    case Double d -> d.isNaN() ? "nan" : Casts.format(d, DataType.DOUBLE);
                    default -> Casts.format(row[c], ALL_TYPES.type(c));
                });
            }
            expected.add(t);
        }
        assertEquals(expected, duckdb("SELECT " + String.join(", ", columns) + " FROM read_parquet("
                + ParquetTestFiles.sql(file) + ") ORDER BY id"));
        assertEquals(List.of(List.of("3", "300", "100", "ZSTD")), duckdb("SELECT count(DISTINCT row_group_id),"
                + " max(row_group_num_rows), min(row_group_num_rows), any_value(compression) FROM parquet_metadata("
                + ParquetTestFiles.sql(file) + ")"));
    }

    @Test
    void theSchemaIsWhatDuckDbWritesButHugeintIsADecimal() throws SQLException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER, false),
                new Schema.Field("t", DataType.TINYINT), new Schema.Field("h", DataType.HUGEINT),
                new Schema.Field("d", DataType.decimal(30, 5)), new Schema.Field("ts", DataType.TIMESTAMP),
                new Schema.Field("u", DataType.UUID), new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
                new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));
        Path file = dir.resolve("s.parquet");
        write(file, schema, Map.of(), List.of(Batch.ofRows(schema, List.<Object[]>of(new Object[] {1, (byte) 2,
            new BigInteger("-99999999999999999999999999999999999999"), null, 0L, null, List.of(), Map.of()}))));
        assertEquals(List.of(
                Arrays.asList("fyrql_schema", null, null, null, null),
                Arrays.asList("n", "INT32", "REQUIRED", null, null),
                Arrays.asList("t", "INT32", "OPTIONAL", "INT_8", null),
                Arrays.asList("h", "FIXED_LEN_BYTE_ARRAY", "OPTIONAL", "DECIMAL", "38"),
                Arrays.asList("d", "FIXED_LEN_BYTE_ARRAY", "OPTIONAL", "DECIMAL", "30"),
                Arrays.asList("ts", "INT64", "OPTIONAL", "TIMESTAMP_MICROS", null),
                Arrays.asList("u", "FIXED_LEN_BYTE_ARRAY", "OPTIONAL", null, null),
                Arrays.asList("l", null, "OPTIONAL", "LIST", null),
                Arrays.asList("list", null, "REPEATED", null, null),
                Arrays.asList("element", "INT32", "OPTIONAL", null, null),
                Arrays.asList("m", null, "OPTIONAL", "MAP", null),
                Arrays.asList("key_value", null, "REPEATED", null, null),
                Arrays.asList("key", "BYTE_ARRAY", "REQUIRED", "UTF8", null),
                Arrays.asList("value", "INT32", "OPTIONAL", null, null)),
                duckdb("SELECT name, type, repetition_type, converted_type, CAST(precision AS VARCHAR) FROM"
                        + " parquet_schema(" + ParquetTestFiles.sql(file) + ")"));
        assertEquals(List.of(List.of("writer.model.name", "fyrql")), duckdb("SELECT decode(key), decode(value) FROM"
                + " parquet_kv_metadata(" + ParquetTestFiles.sql(file) + ")"));
        assertEquals(List.of(List.of("INTEGER", "TINYINT", "DECIMAL(38,0)", "DECIMAL(30,5)", "TIMESTAMP", "UUID",
                "INTEGER[]", "MAP(VARCHAR, INTEGER)")), List.of(duckdb("DESCRIBE SELECT * FROM read_parquet("
                        + ParquetTestFiles.sql(file) + ")").stream().map(r -> r.get(1)).toList()));
    }

    @Test
    void theCodecsTheRowGroupsAndVersion2AreOptions() throws SQLException {
        Schema schema = Schema.of(new Schema.Field("id", DataType.BIGINT), new Schema.Field("s", DataType.VARCHAR));
        List<Object[]> rows = new ArrayList<>();
        for (long i = 0; i < 1000; i++) {
            rows.add(new Object[] {i, i % 7 == 0 ? null : "row " + i});
        }
        List<Batch> batches = List.of(Batch.ofRows(schema, rows));
        for (String codec : List.of("snappy", "gzip", "lz4", "lz4_raw", "uncompressed", "none", "ZSTD")) {
            Path file = dir.resolve(codec + ".parquet");
            write(file, schema, Map.of("codec", codec, "row_group_size", "250", "parquet_version", "V2"), batches);
            String expected = switch (codec) {
                case "lz4" -> "LZ4_RAW";
                case "none" -> "UNCOMPRESSED";
                default -> codec.toUpperCase(java.util.Locale.ROOT);
            };
            assertEquals(List.of(List.of("4", expected)), duckdb("SELECT count(DISTINCT row_group_id),"
                    + " any_value(compression) FROM parquet_metadata(" + ParquetTestFiles.sql(file) + ")"), codec);
            assertEquals(List.of(List.of("1000", "499500", "857")), duckdb("SELECT count(*), sum(id), count(s) FROM"
                    + " read_parquet(" + ParquetTestFiles.sql(file) + ")"), codec);
            Schema[] read = new Schema[1];
            assertEquals(text(schema, rows), text(schema, read(file, read)), codec);
        }

        // Row groups by bytes: a small limit makes many.
        Path file = dir.resolve("bytes.parquet");
        write(file, schema, Map.of("compression", "uncompressed", "row_group_size_bytes", "1KB"), batches);
        assertEquals(1000, Integer.parseInt(duckdb("SELECT sum(row_group_num_rows) FROM (SELECT DISTINCT row_group_id,"
                + " row_group_num_rows FROM parquet_metadata(" + ParquetTestFiles.sql(file) + "))").get(0).get(0)));
        assertEquals(true, Integer.parseInt(duckdb("SELECT count(DISTINCT row_group_id) FROM parquet_metadata("
                + ParquetTestFiles.sql(file) + ")").get(0).get(0)) > 1);
    }

    @Test
    void valuesTheFileCannotHoldAreErrorsThatLeaveNoTrace() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER, false),
                new Schema.Field("h", DataType.HUGEINT));
        Path file = dir.resolve("e.parquet");
        Batch huge = Batch.ofRows(schema, List.<Object[]>of(new Object[] {1, BigInteger.TEN.pow(38)}));
        assertEquals("The value 100000000000000000000000000000000000000 of the column h has more than 38 digits;"
                + " a Parquet file holds a HUGEINT as a DECIMAL(38,0)", assertThrows(IllegalArgumentException.class,
                        () -> write(file, schema, Map.of(), List.of(huge))).getMessage());
        Batch nullInRequired = Batch.ofRows(schema, List.<Object[]>of(new Object[] {null, BigInteger.ONE}));
        assertEquals("The column n is NOT NULL but holds a NULL", assertThrows(IllegalArgumentException.class,
                () -> write(file, schema, Map.of(), List.of(nullInRequired))).getMessage());
        assertEquals("The Parquet file " + file + " has an unknown option array; known are compression, codec,"
                + " row_group_size, row_group_size_bytes, parquet_version", assertThrows(IllegalArgumentException.class,
                        () -> write(file, schema, Map.of("array", "true"), List.of())).getMessage());
        assertEquals("The option compression of the Parquet file " + file + " must be zstd, snappy, gzip, lz4_raw or"
                + " uncompressed, not brotli", assertThrows(IllegalArgumentException.class, () -> write(file, schema,
                        Map.of("compression", "brotli"), List.of())).getMessage());
        assertEquals("The option parquet_version of the Parquet file " + file + " must be v1 or v2: v3",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("parquet_version", "v3"),
                        List.of())).getMessage());
        assertEquals("The option row_group_size of the Parquet file " + file + " must be a positive number of rows: 0",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("row_group_size", "0"),
                        List.of())).getMessage());
        assertEquals("The Parquet source pq writes no files of the format csv",
                assertThrows(IllegalArgumentException.class, () -> connector().openFile("csv", file, schema,
                        Map.of())).getMessage());
        assertEquals(List.of(), files());
        assertEquals(List.of("parquet"), connector().fileFormats());
    }

    @Test
    void aCommitReplacesTheFileThatTheReaderThenReadsAnew() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER));
        Path file = dir.resolve("n.parquet");
        write(file, schema, Map.of(), List.of(Batch.ofRows(schema, List.<Object[]>of(new Object[] {1}))));
        ParquetConnector reader = ParquetConnector.builder("pq").path("t", file).build();
        TableRef t = TableRef.of("pq", "t");
        assertEquals(1, reader.catalog().rowCount(t).orElseThrow());

        try (Sink sink = connector().openFile("parquet", file, schema, Map.of())) {
            sink.write(Batch.ofRows(schema, List.<Object[]>of(new Object[] {2}, new Object[] {3})));
            assertEquals(2, files().size());
        }
        assertEquals(List.of(Path.of("n.parquet")), files());

        write(file, schema, Map.of(), List.of(Batch.ofRows(schema, List.<Object[]>of(new Object[] {2},
                new Object[] {3}))));
        assertEquals(List.of(Path.of("n.parquet")), files());
        List<Object> values = new ArrayList<>();
        ScanPlan plan = reader.plan(t, ScanRequest.columns(List.of(0)));
        for (Split split : reader.splits(plan)) {
            for (Batch b : Batches.readAll(reader.open(split))) {
                values.add(b.column(0).get(0));
                values.add(b.column(0).get(1));
            }
        }
        assertEquals(List.of(2, 3), values);
    }
}
