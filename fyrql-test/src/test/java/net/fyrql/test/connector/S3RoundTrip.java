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
import net.fyrql.connector.FileObject;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.optimizer.Optimizer;
import net.fyrql.sql.SqlException;
import net.fyrql.test.CorpusTables;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.WrittenFiles;
import net.fyrql.test.random.RandomTables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the tables of the random queries and of the SQL corpus with
 * {@code COPY ... TO 's3://...'} into a store of S3 as CSV, JSON and
 * Parquet and reads them back: DuckDB reads a downloaded copy of each
 * object, and FyrQL reads the object with {@code FROM 's3://...'} or a
 * table function and loads it with {@code COPY ... FROM}, with the rows of
 * the table, as {@code CopyRoundTripTest} does for local files. Patterns
 * read several Parquet objects, and a filter that excludes every row group
 * reads none.
 * The subclasses say which store: the fake of the tests or a running
 * MinIO.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3RoundTrip {

    static final List<String> TABLES = List.of("p", "q", "e", "t", "u", "w", "n");
    static final List<String> FORMATS = List.of("csv", "json", "parquet");
    private static final String MAX_38_DIGITS = "99999999999999999999999999999999999999";
    static final int BIG_ROWS = 10_000;

    @TempDir
    Path dir;

    FyrQL engine;
    S3Connector s3;
    ParquetConnector parquet;
    DuckDbReference duck;

    /** Returns the source of S3, with the store the class tests. */
    abstract S3Connector connect();

    /** Returns the bucket the objects go to, which exists once {@link #connect()} is done. */
    abstract String bucket();

    /** Removes what the tests left, after the engine is closed. */
    void cleanUp() throws IOException {
    }

    @BeforeAll
    void start() {
        MemoryConnector.Builder memory = CorpusTables.builder();
        for (Map.Entry<String, List<Object[]>> t : RandomTables.rows(20261020).entrySet()) {
            memory.table(t.getKey(), RandomTables.SCHEMAS.get(t.getKey()), t.getValue());
        }
        List<Object[]> big = new ArrayList<>();
        for (long id = 0; id < BIG_ROWS; id++) {
            big.add(new Object[] {id, "name " + id});
        }
        memory.table("big", Schema.of(new Schema.Field("id", DataType.BIGINT), new Schema.Field("name",
                DataType.VARCHAR)), big);
        s3 = connect();
        parquet = ParquetConnector.builder("parquet").recordReads(true).build();
        engine = FyrQL.builder().register(memory.build()).register(parquet).register(s3).build();
        duck = new DuckDbReference();
    }

    @AfterAll
    void stop() throws IOException {
        if (duck != null) {
            duck.close();
        }
        try {
            cleanUp();
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    /** Tells whether the engines of the run push filters to the sources, which then skip row groups by them. */
    static boolean pushdown() {
        return Optimizer.Options.fromSystemProperty().equals(Optimizer.Options.DEFAULT);
    }

    /** Returns the location of a key in the bucket. */
    String location(String key) {
        return "s3://" + bucket() + "/" + key;
    }

    /** Downloads an object into a local file of its name. */
    Path download(String location) throws IOException {
        FileObject object = s3.fileStore().orElseThrow().object(location);
        Path file = dir.resolve(object.name());
        try (InputStream in = object.open()) {
            Files.write(file, in.readAllBytes());
        }
        return file;
    }

    Stream<Arguments> tablesAndFormats() {
        return TABLES.stream().flatMap(t -> FORMATS.stream().map(f -> Arguments.of(t, f)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("tablesAndFormats")
    void aTableGoesToS3AndBack(String table, String format) throws IOException {
        String query = "SELECT * FROM memory." + table;
        if (format.equals("parquet") && table.equals("e")) {
            query += " WHERE eh IS NULL OR eh BETWEEN -" + MAX_38_DIGITS + " AND " + MAX_38_DIGITS;
        }
        Schema schema;
        List<Object[]> rows;
        try (QueryResult r = engine.sql(query)) {
            schema = r.schema();
            rows = r.rows();
        }
        String location = location("round-trip/" + table + "." + format);
        assertEquals(rows.size(), engine.update("COPY (" + query + ") TO '" + location + "'"));

        WrittenFiles.ReadBack duckDb = WrittenFiles.duckDb(duck, download(location), format, schema, rows);
        same(duckDb.schema(), duckDb.expected(), duckDb.actual(), "DuckDB reads " + location);

        switch (format) {
            case "csv" -> {
                assertEquals(List.of((long) rows.size()), engine.rows("SELECT count(*) FROM read_csv('" + location
                        + "', header = true)").stream().map(r -> r[0]).toList());
                if (schema.fields().stream().noneMatch(f -> f.type().isNested())) {
                    same(schema, emptyAsNull(rows), copyFrom(location, schema, ""), "COPY FROM " + location);
                } else {
                    assertTrue(assertThrows(SqlException.class, () -> copyFrom(location, schema, "")).getMessage()
                            .startsWith("A CSV file holds no values of the type "));
                }
                return;
            }
            case "json" -> {
                assertEquals(List.of((long) rows.size()), engine.rows("SELECT count(*) FROM '" + location + "'")
                        .stream().map(r -> r[0]).toList());
                same(schema, rows, copyFrom(location, schema, ""), "COPY FROM " + location);
                return;
            }
            default -> {
            }
        }
        List<Object[]> expected = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] e = row.clone();
            for (int c = 0; c < e.length; c++) {
                // A Parquet file holds a HUGEINT as a DECIMAL(38,0).
                if (e[c] instanceof BigInteger h) {
                    e[c] = new BigDecimal(h);
                }
            }
            expected.add(e);
        }
        try (QueryResult r = engine.sql("SELECT * FROM '" + location + "'")) {
            same(r.schema(), expected, r.rows(), "FROM " + location);
        }
        assertEquals(List.of((long) rows.size()), engine.rows("SELECT count(*) FROM read_parquet('" + location
                + "')").stream().map(r -> r[0]).toList());
        same(schema, rows, copyFrom(location, schema, ""), "COPY FROM " + location);
    }

    /** Returns the rows with an empty string as {@code NULL}, as FyrQL reads them from a CSV file. */
    static List<Object[]> emptyAsNull(List<Object[]> rows) {
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

    @Test
    void optionsAndGzipReachTheObjects() {
        Schema schema = RandomTables.SCHEMAS.get("q");
        List<Object[]> rows = engine.rows("SELECT * FROM memory.q");
        String csv = " (DELIMITER ';', HEADER false, DATEFORMAT '%d.%m.%Y')";
        String gz = location("options/q.csv.gz");
        assertEquals(30, engine.update("COPY memory.q TO '" + gz + "'" + csv));
        same(schema, emptyAsNull(rows), copyFrom(gz, schema, csv), "COPY FROM " + gz);
        assertEquals(List.of(30L), engine.rows("SELECT count(*) FROM read_csv('" + gz + "', header = false,"
                + " delim = ';')").stream().map(r -> r[0]).toList());

        String json = location("options/q.data");
        assertEquals(30, engine.update("COPY memory.q TO '" + json + "' (FORMAT json, ARRAY true)"));
        same(schema, rows, copyFrom(json, schema, " (FORMAT json)"), "COPY FROM " + json);
        String ndjson = location("options/q.ndjson.gz");
        assertEquals(30, engine.update("COPY memory.q TO '" + ndjson + "' (FORMAT json)"));
        assertEquals(List.of(30L), engine.rows("SELECT count(*) FROM read_ndjson('" + ndjson + "')").stream()
                .map(r -> r[0]).toList());
    }

    /**
     * Loads an object with {@code COPY ... FROM} into a new table of the
     * memory source with the columns of a schema, all of them nullable, and
     * returns the rows of the table, which is dropped again.
     *
     * @param options the options of the statement, such as {@code " (HEADER false)"}, or empty
     */
    List<Object[]> copyFrom(String location, Schema schema, String options) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            fields.add(new Schema.Field(f.name(), f.type(), true));
        }
        Writer writer = engine.connector("memory").writer().orElseThrow();
        try (Sink sink = writer.create(TableRef.of("memory", "back"), new Schema(fields))) {
            sink.commit();
        }
        try {
            long count = engine.update("COPY memory.back FROM '" + location + "'" + options);
            List<Object[]> rows = engine.rows("SELECT * FROM memory.back");
            assertEquals(rows.size(), count, "rows loaded from " + location);
            return rows;
        } finally {
            engine.update("DROP TABLE memory.back");
        }
    }

    static void same(Schema schema, List<Object[]> expected, List<Object[]> actual, String what) {
        ResultComparison.Outcome outcome = ResultComparison.compare(schema, expected, actual, false);
        assertTrue(outcome.same(), what + ":\n" + outcome.report());
    }

    @Test
    void aPatternOrAPrefixReadsSeveralObjects() {
        assertEquals(6, engine.update("COPY memory.t TO '" + location("parts/t-1.parquet") + "'"));
        assertEquals(6, engine.update("COPY memory.t TO '" + location("parts/t-2.parquet") + "'"));
        assertEquals(6, engine.update("COPY memory.t TO '" + location("parts/sub/t-3.parquet") + "'"));
        assertEquals(List.of(12L), engine.rows("SELECT count(*) FROM '" + location("parts/t-*.parquet") + "'")
                .stream().map(r -> r[0]).toList());
        assertEquals(List.of(12L), engine.rows("SELECT count(*) FROM read_parquet('" + location("parts/") + "')")
                .stream().map(r -> r[0]).toList());
        assertEquals(List.of(12L), engine.rows("SELECT count(*) FROM parquet_scan('" + location("parts") + "')")
                .stream().map(r -> r[0]).toList());
    }

    @Test
    void aFilterSkipsTheRowGroupsOfAnObject() {
        String location = location("pruning/big.parquet");
        assertEquals(BIG_ROWS, engine.update("COPY memory.big TO '" + location + "' (ROW_GROUP_SIZE 1000)"));
        parquet.clearReads();
        assertEquals(List.of(0L), engine.rows("SELECT count(*) FROM '" + location + "' WHERE id < 0").stream()
                .map(r -> r[0]).toList());
        // Without pushdown the source does not see the filter and reads every row group.
        assertEquals(pushdown() ? 0 : 10, parquet.reads().size(), parquet.reads().toString());
        parquet.clearReads();
        assertEquals(List.of("name 4321"), engine.rows("SELECT name FROM '" + location + "' WHERE id = 4321")
                .stream().map(r -> r[0]).toList());
        List<ParquetConnector.Read> reads = parquet.reads();
        assertEquals(pushdown() ? 1 : 10, reads.size(), reads.toString());
        assertEquals(location, reads.get(0).file());
        if (pushdown()) {
            assertEquals(4, reads.get(0).rowGroup());
        }
    }

    @Test
    void whatIsNotThereIsAnErrorThatNamesIt() {
        assertEquals("No files found that match the pattern \"" + location("none.parquet") + "\"",
                assertThrows(SqlException.class, () -> engine.rows("SELECT * FROM '" + location("none.parquet")
                        + "'")).reason());
        assertEquals("Cannot write " + location("") + ": it is not the location of an object",
                assertThrows(SqlException.class, () -> engine.update("COPY memory.t TO '" + location("")
                        + "' (FORMAT parquet)")).reason());
    }
}
