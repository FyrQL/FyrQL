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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.connector.Batches;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.sql.SqlException;
import net.fyrql.test.CorpusTables;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.WrittenFiles;
import net.fyrql.test.random.RandomTables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code COPY ... TO} writes the tables of the random queries ({@code p},
 * {@code q} and {@code e}, with the edge values of every type) and of the
 * SQL corpus ({@code t}, {@code u}, {@code w}, and {@code n} with nested
 * columns) into CSV, JSON and Parquet files. DuckDB reads each file, with
 * the column types given for CSV and JSON, and gets the rows of the table;
 * so does FyrQL, and {@code COPY ... FROM} loads the file into a table with
 * the columns of the first one. What the formats lose: CSV keeps no types
 * and holds no nested values, and FyrQL's CSV reader reads an empty string
 * as {@code NULL}, as DuckDB does by default; a Parquet file holds a
 * {@code HUGEINT} as a {@code DECIMAL(38,0)}, which a value of 39 digits
 * does not fit, and which {@code COPY ... FROM} casts back. The engine has
 * only the memory source registered: the sources of the formats come with
 * their modules on the class path.
 */
class CopyRoundTripTest {

    private static final List<String> TABLES = List.of("p", "q", "e", "t", "u", "w", "n");
    private static final List<String> FORMATS = List.of("csv", "json", "parquet");
    private static final String MAX_38_DIGITS = "99999999999999999999999999999999999999";

    @TempDir
    static Path dir;

    private static FyrQL engine;
    private static DuckDbReference duck;

    @BeforeAll
    static void start() {
        MemoryConnector.Builder memory = CorpusTables.builder();
        for (Map.Entry<String, List<Object[]>> t : RandomTables.rows(20261020).entrySet()) {
            memory.table(t.getKey(), RandomTables.SCHEMAS.get(t.getKey()), t.getValue());
        }
        engine = FyrQL.builder().register(memory.build()).build();
        duck = new DuckDbReference();
    }

    @AfterAll
    static void stop() {
        duck.close();
        engine.close();
    }

    static Stream<Arguments> tablesAndFormats() {
        return TABLES.stream().flatMap(t -> FORMATS.stream().map(f -> Arguments.of(t, f)));
    }

    private static String path(Path file) {
        return WrittenFiles.path(file);
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("tablesAndFormats")
    void duckDbAndFyrqlReadTheRowsOfTheTableFromTheFile(String table, String format) {
        String query = "SELECT * FROM memory." + table;
        Path file = dir.resolve(table + "." + format);
        if (format.equals("parquet") && table.equals("e")) {
            assertTrue(assertThrows(SqlException.class, () -> engine.update("COPY memory.e TO '" + path(file) + "'"))
                    .getMessage().contains(" of the column eh has more than 38 digits; a Parquet file holds a HUGEINT"
                            + " as a DECIMAL(38,0)"));
            query += " WHERE eh IS NULL OR eh BETWEEN -" + MAX_38_DIGITS + " AND " + MAX_38_DIGITS;
        }
        Schema schema;
        List<Object[]> rows;
        try (QueryResult r = engine.sql(query)) {
            schema = r.schema();
            rows = r.rows();
        }
        assertEquals(rows.size(), engine.update("COPY (" + query + ") TO '" + path(file) + "'"));

        WrittenFiles.ReadBack duckDb = WrittenFiles.duckDb(duck, file, format, schema, rows);
        same(duckDb.schema(), duckDb.expected(), duckDb.actual(), "DuckDB reads " + file.getFileName());

        switch (format) {
            case "csv" -> {
                // FyrQL reads nested values as text, and an empty string as NULL.
                List<Schema.Field> text = new ArrayList<>();
                for (Schema.Field f : schema.fields()) {
                    text.add(f.type() instanceof DataType.Scalar ? f : new Schema.Field(f.name(), DataType.VARCHAR));
                }
                Schema textSchema = new Schema(text);
                List<Object[]> expected = new ArrayList<>();
                for (Object[] row : rows) {
                    Object[] e = row.clone();
                    for (int c = 0; c < e.length; c++) {
                        if (e[c] != null && !(schema.type(c) instanceof DataType.Scalar)) {
                            e[c] = Casts.format(e[c], schema.type(c));
                        } else if ("".equals(e[c])) {
                            e[c] = null;
                        }
                    }
                    expected.add(e);
                }
                same(textSchema, expected, read(CsvConnector.builder("back").header(true).schema(table, textSchema)
                        .file(table, file).build(), table), "FyrQL reads " + file.getFileName());
                if (schema.fields().stream().noneMatch(f -> f.type().isNested())) {
                    same(schema, emptyAsNull(rows), copyFrom(file, schema, "", ""), "COPY FROM "
                            + file.getFileName());
                } else {
                    assertTrue(assertThrows(SqlException.class, () -> copyFrom(file, schema, "", "")).getMessage()
                            .startsWith("A CSV file holds no values of the type "));
                }
            }
            case "json" -> {
                same(schema, rows, read(JsonConnector.builder("back").schema(table, schema).file(table, file)
                        .build(), table), "FyrQL reads " + file.getFileName());
                same(schema, rows, copyFrom(file, schema, "", ""), "COPY FROM " + file.getFileName());
            }
            default -> {
                // A HUGEINT comes back as a DECIMAL(38,0).
                Connector back = ParquetConnector.builder("back").path(table, file).build();
                Schema read = back.catalog().schema(TableRef.of("back", table));
                List<Object[]> expected = new ArrayList<>();
                for (Object[] row : rows) {
                    Object[] e = row.clone();
                    for (int c = 0; c < e.length; c++) {
                        if (e[c] instanceof java.math.BigInteger h) {
                            e[c] = new java.math.BigDecimal(h);
                        }
                    }
                    expected.add(e);
                }
                same(read, expected, read(back, table), "FyrQL reads " + file.getFileName());
                // COPY ... FROM casts the DECIMAL(38,0) back to a HUGEINT.
                same(schema, rows, copyFrom(file, schema, "", ""), "COPY FROM " + file.getFileName());
            }
        }
    }

    /**
     * Loads a file with {@code COPY ... FROM} into a new table of the memory
     * source with the columns of a schema, all of them nullable, and returns
     * the rows of the table, which is dropped again.
     *
     * @param columns the column list of the statement, such as {@code " (v, k)"}, or empty
     * @param options the options of the statement, such as {@code " (HEADER false)"}, or empty
     */
    private static List<Object[]> copyFrom(Path file, Schema schema, String columns, String options) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            fields.add(new Schema.Field(f.name(), f.type(), true));
        }
        Writer writer = engine.connector("memory").writer().orElseThrow();
        try (Sink sink = writer.create(TableRef.of("memory", "back"), new Schema(fields))) {
            sink.commit();
        }
        try {
            long count = engine.update("COPY memory.back" + columns + " FROM '" + path(file) + "'" + options);
            List<Object[]> rows = engine.rows("SELECT * FROM memory.back");
            assertEquals(rows.size(), count, "rows loaded from " + file.getFileName());
            return rows;
        } finally {
            engine.update("DROP TABLE memory.back");
        }
    }

    /** Returns the rows with an empty string as {@code NULL}, as FyrQL reads them from a CSV file. */
    private static List<Object[]> emptyAsNull(List<Object[]> rows) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] e = row.clone();
            for (int c = 0; c < e.length; c++) {
                if ("".equals(e[c])) {
                    e[c] = null;
                }
            }
            out.add(e);
        }
        return out;
    }

    private static void same(Schema schema, List<Object[]> expected, List<Object[]> actual, String what) {
        ResultComparison.Outcome outcome = ResultComparison.compare(schema, expected, actual, false);
        assertTrue(outcome.same(), what + ":\n" + outcome.report());
    }

    /** Reads all rows of a table of a connector. */
    private static List<Object[]> read(Connector c, String table) {
        TableRef ref = TableRef.of(c.name(), table);
        ScanPlan plan = c.plan(ref, ScanRequest.columns(IntStream.range(0, c.catalog().schema(ref).size()).boxed()
                .toList()));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            Batches.readAll(c.open(split)).forEach(b -> rows.addAll(b.rows()));
        }
        return rows;
    }

    @Test
    void theFormatComesFromTheNameOrTheOptionAndOptionsReachTheWriter() throws IOException {
        Path gz = dir.resolve("q.tsv.gz");
        assertEquals(30, engine.update("COPY memory.q TO '" + path(gz) + "'"));
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            assertEquals("k\ti\tm\tv\tdt\tx\tn", new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .findFirst().orElseThrow());
        }

        Path data = dir.resolve("u.data");
        assertEquals(5, engine.update("COPY (SELECT a, f FROM memory.u ORDER BY f) TO '" + path(data)
                + "' (FORMAT json, ARRAY true)"));
        assertEquals("[\n\t{\"a\":1,\"f\":\"p\"},\n\t{\"a\":3,\"f\":\"p\"},\n\t{\"a\":4,\"f\":\"q\"},\n"
                + "\t{\"a\":null,\"f\":\"r\"},\n\t{\"a\":3,\"f\":\"s\"}\n]\n", Files.readString(data));

        Path parquet = dir.resolve("t.parquet");
        assertEquals(6, engine.update("COPY memory.t TO '" + path(parquet) + "' (COMPRESSION snappy,"
                + " ROW_GROUP_SIZE 4)"));
        assertEquals(List.of("2", "SNAPPY"), List.of(duck.query("SELECT CAST(count(DISTINCT row_group_id) AS"
                + " VARCHAR), any_value(compression) FROM parquet_metadata('" + path(parquet) + "')", Schema.of(
                        new Schema.Field("n", DataType.VARCHAR), new Schema.Field("c", DataType.VARCHAR))).get(0))
                .stream().map(Object::toString).toList());

        assertEquals("The CSV file " + dir.resolve("x.csv") + " has an unknown option rows; known are header,"
                + " delimiter, delim, sep, quote, escape, nullstr, null, dateformat, timestampformat, compression",
                assertThrows(SqlException.class, () -> engine.update("COPY memory.t TO '" + path(dir.resolve("x.csv"))
                        + "' (ROWS 3)")).getMessage().replace('/', java.io.File.separatorChar).split(" at line")[0]);
    }

    @Test
    void copyFromReadsWhatCopyToWritesWithTheSameOptionsAndColumns() {
        Schema schema = RandomTables.SCHEMAS.get("q");
        List<Object[]> rows = engine.rows("SELECT * FROM memory.q");

        String csv = " (DELIMITER ';', HEADER false, DATEFORMAT '%d.%m.%Y')";
        Path gz = dir.resolve("q-options.csv.gz");
        assertEquals(30, engine.update("COPY memory.q TO '" + path(gz) + "'" + csv));
        same(schema, emptyAsNull(rows), copyFrom(gz, schema, "", csv), "COPY FROM " + gz.getFileName());

        Path data = dir.resolve("q-options.data");
        assertEquals(30, engine.update("COPY memory.q TO '" + path(data) + "' (FORMAT json, ARRAY true,"
                + " DATEFORMAT '%d.%m.%Y')"));
        same(schema, rows, copyFrom(data, schema, "", " (FORMAT json, DATEFORMAT '%d.%m.%Y')"), "COPY FROM "
                + data.getFileName());

        // The columns of the file are the ones of the column list, in its order; the others are NULL.
        Path tsv = dir.resolve("q-columns.tsv");
        assertEquals(30, engine.update("COPY memory.q (v, k) TO '" + path(tsv) + "'"));
        List<Object[]> some = new ArrayList<>();
        for (Object[] row : emptyAsNull(rows)) {
            Object[] e = new Object[row.length];
            e[0] = row[0];
            e[3] = row[3];
            some.add(e);
        }
        same(schema, some, copyFrom(tsv, schema, " (v, k)", ""), "COPY FROM " + tsv.getFileName());
        String error = assertThrows(RuntimeException.class, () -> copyFrom(tsv, schema, "", "")).getMessage();
        assertTrue(error.contains(" has 2 fields, the header 7"), error);
    }
}
