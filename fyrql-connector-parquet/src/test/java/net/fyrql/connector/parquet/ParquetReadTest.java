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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.api.Binary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads files that DuckDB and parquet-java write, with every codec, writer
 * version and encoding, and DuckDB reads the same files: the rows must be
 * the same, in the same order. Values are compared as text: numbers,
 * strings and the text of a {@code TIME} as DuckDB casts them to
 * {@code VARCHAR}, floating point numbers as Java prints the
 * {@code double}, dates as days and timestamps as microseconds since
 * 1970, byte arrays in hex.
 */
class ParquetReadTest {

    @TempDir
    Path dir;

    /** A table of random values of every primitive type DuckDB writes, a tenth of them {@code NULL}. */
    private static final String RANDOM = """
            CREATE TABLE t AS SELECT range AS id,
              CASE WHEN random() < 0.1 THEN NULL ELSE random() < 0.5 END AS b,
              CASE WHEN random() < 0.1 THEN NULL ELSE (floor(random() * 256) - 128)::TINYINT END AS i8,
              CASE WHEN random() < 0.1 THEN NULL ELSE (floor(random() * 65536) - 32768)::SMALLINT END AS i16,
              CASE WHEN random() < 0.1 THEN NULL ELSE (floor(random() * 4294967296) - 2147483648)::INTEGER END AS i32,
              CASE WHEN random() < 0.1 THEN NULL ELSE (hash(range) >> 1)::BIGINT * (1 - 2 * (range % 2)) END AS i64,
              CASE WHEN random() < 0.1 THEN NULL ELSE floor(random() * 256)::UTINYINT END AS u8,
              CASE WHEN random() < 0.1 THEN NULL ELSE floor(random() * 65536)::USMALLINT END AS u16,
              CASE WHEN random() < 0.1 THEN NULL ELSE floor(random() * 4294967296)::UINTEGER END AS u32,
              CASE WHEN random() < 0.1 THEN NULL ELSE hash(range) END AS u64,
              CASE WHEN random() < 0.1 THEN NULL ELSE (range * 7919)::HUGEINT END AS i128,
              CASE WHEN random() < 0.1 THEN NULL ELSE (random() * 2000 - 1000)::FLOAT END AS f32,
              CASE WHEN range % 50 = 0 THEN 'NaN'::DOUBLE WHEN range % 50 = 1 THEN '-0.0'::DOUBLE
                WHEN range % 50 = 2 THEN '-inf'::DOUBLE WHEN random() < 0.1 THEN NULL
                ELSE (random() - 0.5) * 1e12 END AS f64,
              CASE WHEN random() < 0.1 THEN NULL ELSE (random() * 199.98 - 99.99)::DECIMAL(4,2) END AS d4,
              CASE WHEN random() < 0.1 THEN NULL ELSE ((random() - 0.5) * 1e15)::DECIMAL(18,3) END AS d18,
              CASE WHEN random() < 0.1 THEN NULL ELSE ((random() - 0.5) * 1e27)::DECIMAL(38,10) END AS d38,
              CASE WHEN random() < 0.1 THEN NULL ELSE DATE '1800-01-01' + floor(random() * 150000)::INTEGER END AS day,
              CASE WHEN random() < 0.1 THEN NULL
                ELSE TIMESTAMP '1800-01-01' + to_microseconds((random() * 1.2e16)::BIGINT) END AS ts,
              CASE WHEN random() < 0.1 THEN NULL ELSE make_timestamp_ns(((random() - 0.5) * 4e18)::BIGINT) END AS ts_ns,
              CASE WHEN random() < 0.1 THEN NULL
                ELSE (TIMESTAMP '1900-01-01' + to_milliseconds((random() * 6e12)::BIGINT))::TIMESTAMP_MS END AS ts_ms,
              CASE WHEN random() < 0.1 THEN NULL
                ELSE (TIMESTAMP '1900-01-01' + to_microseconds((random() * 6e15)::BIGINT))::TIMESTAMPTZ END AS tz,
              CASE WHEN random() < 0.1 THEN NULL
                ELSE TIME '00:00:00' + to_microseconds((random() * 86400e6)::BIGINT) END AS clock,
              CASE WHEN random() < 0.1 THEN NULL
                ELSE (TIME '00:00:00' + to_microseconds((random() * 86400e6)::BIGINT))::TIMETZ END AS clock_tz,
              CASE WHEN random() < 0.1 THEN NULL ELSE 'v' || (hash(range) % 100000)::VARCHAR
                || CASE WHEN range % 7 = 0 THEN ' \u00E4\u00F6\u00FC \u20AC \uD83D\uDE00' ELSE '' END END AS s,
              'k' || (range % 10) AS k,
              CASE WHEN random() < 0.1 THEN NULL ELSE ('b' || range)::BLOB END AS bin,
              CASE WHEN random() < 0.1 THEN NULL ELSE gen_random_uuid() END AS u,
              CASE WHEN random() < 0.1 THEN NULL ELSE '{"a":' || range || '}' END::JSON AS j
            FROM range(5000)""";

    @Test
    void duckDbFilesOfEveryCodecAndVersionGiveTheRowsOfDuckDb() throws Exception {
        List<String> statements = new ArrayList<>(List.of("SELECT setseed(0.25)", RANDOM));
        List<Path> files = new ArrayList<>();
        for (String version : List.of("V1", "V2")) {
            for (String codec : List.of("uncompressed", "snappy", "gzip", "zstd", "lz4_raw")) {
                Path file = dir.resolve(codec + "-" + version + ".parquet");
                files.add(file);
                statements.add("COPY t TO " + ParquetTestFiles.sql(file) + " (FORMAT parquet, COMPRESSION " + codec
                        + ", PARQUET_VERSION " + version + ", ROW_GROUP_SIZE 2048)");
            }
        }
        ParquetTestFiles.duckdb(statements.toArray(String[]::new));
        for (Path file : files) {
            assertTrue(new ParquetFiles().footer(file).metadata().getBlocks().size() > 1, file.toString());
            List<List<String>> expected = duckdb(file, "");
            for (int batchSize : file == files.get(0) ? List.of(1, 7, 2048) : List.of(7, 2048)) {
                assertRows(expected, fyrql(connector(file, batchSize), null, OptionalLong.empty()),
                        file.getFileName() + " in batches of " + batchSize);
            }
        }
    }

    /** The columns of {@link #fill}. */
    private static final String SCHEMA = """
            message m {
              required int64 id;
              optional boolean b;
              optional int32 i32;
              optional int64 i64;
              optional float f32;
              optional double f64;
              optional binary s (STRING);
              optional binary e (ENUM);
              optional binary j (JSON);
              optional binary raw;
              optional fixed_len_byte_array(4) fixed;
              optional fixed_len_byte_array(16) u (UUID);
              optional fixed_len_byte_array(2) half (FLOAT16);
              optional int32 day (DATE);
              optional int64 ts_ms (TIMESTAMP(MILLIS,false));
              optional int64 ts_us (TIMESTAMP(MICROS,true));
              optional int64 ts_ns (TIMESTAMP(NANOS,false));
              optional int64 tz_ns (TIMESTAMP(NANOS,true));
              optional int96 legacy;
              optional int32 t_ms (TIME(MILLIS,false));
              optional int64 t_us (TIME(MICROS,true));
              optional int64 t_ns (TIME(NANOS,false));
              optional int32 i8 (INTEGER(8,true));
              optional int32 i16 (INTEGER(16,true));
              optional int32 u8 (INTEGER(8,false));
              optional int32 u16 (INTEGER(16,false));
              optional int32 u32 (INTEGER(32,false));
              optional int64 u64 (INTEGER(64,false));
              optional int32 d_int (DECIMAL(9,2));
              optional int64 d_long (DECIMAL(18,4));
              optional fixed_len_byte_array(9) d_fixed (DECIMAL(20,3));
              optional binary d_bin (DECIMAL(38,5));
              optional fixed_len_byte_array(8) d_small (DECIMAL(12,2));
              required int32 r;
            }""";

    /** Fills the columns of {@link #SCHEMA} with random values, a tenth of them {@code NULL}. */
    private static void fill(org.apache.parquet.example.data.Group g, SplittableRandom r, long id) {
        g.add("id", id);
        g.add("r", r.nextInt());
        if (r.nextInt(10) == 0) {
            return;
        }
        // Each optional column is NULL in a tenth of the rows, and all of them in another tenth.
        Consumer<Runnable> maybe = add -> {
            if (r.nextInt(10) != 0) {
                add.run();
            }
        };
        maybe.accept(() -> g.add("b", r.nextBoolean()));
        maybe.accept(() -> g.add("i32", r.nextInt()));
        maybe.accept(() -> g.add("i64", r.nextLong()));
        maybe.accept(() -> g.add("f32", id % 31 == 0 ? Float.NaN : (float) (r.nextDouble() * 2000 - 1000)));
        maybe.accept(() -> g.add("f64", id % 37 == 0 ? -0.0 : r.nextDouble() * 1e12 - 5e11));
        maybe.accept(() -> g.add("s", "s" + r.nextInt(1000) + (id % 5 == 0 ? " \u00E9\u4E2D" : "")));
        maybe.accept(() -> g.add("e", List.of("RED", "GREEN", "BLUE").get(r.nextInt(3))));
        maybe.accept(() -> g.add("j", "{\"a\":" + r.nextInt(100) + "}"));
        maybe.accept(() -> g.add("raw", Binary.fromConstantByteArray(bytes(r, r.nextInt(11)))));
        maybe.accept(() -> g.add("fixed", Binary.fromConstantByteArray(bytes(r, 4))));
        maybe.accept(() -> g.add("u", Binary.fromConstantByteArray(bytes(r, 16))));
        maybe.accept(() -> g.add("half", Binary.fromConstantByteArray(ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN).putShort(Float.floatToFloat16((float) (r.nextDouble() * 200 - 100)))
                .array())));
        maybe.accept(() -> g.add("day", r.nextInt(-100_000, 100_000)));
        maybe.accept(() -> g.add("ts_ms", r.nextLong(-4_000_000_000_000L, 4_000_000_000_000L)));
        maybe.accept(() -> g.add("ts_us", r.nextLong(-4_000_000_000_000_000L, 4_000_000_000_000_000L)));
        maybe.accept(() -> g.add("ts_ns", r.nextLong(-4_000_000_000_000_000_000L, 4_000_000_000_000_000_000L)));
        maybe.accept(() -> g.add("tz_ns", r.nextLong(-4_000_000_000_000_000_000L, 4_000_000_000_000_000_000L)));
        maybe.accept(() -> g.add("legacy", Binary.fromConstantByteArray(ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(r.nextLong(86_400_000_000_000L))
                .putInt(r.nextInt(2_440_588 - 40_000, 2_440_588 + 40_000)).array())));
        maybe.accept(() -> g.add("t_ms", r.nextInt(86_400_000)));
        maybe.accept(() -> g.add("t_us", r.nextLong(86_400_000_000L)));
        maybe.accept(() -> g.add("t_ns", id % 3 == 0 ? r.nextLong(86_400L) * 1_000_000_000L
                : r.nextLong(86_400_000_000_000L)));
        maybe.accept(() -> g.add("i8", r.nextInt(-128, 128)));
        maybe.accept(() -> g.add("i16", r.nextInt(-32768, 32768)));
        maybe.accept(() -> g.add("u8", r.nextInt(256)));
        maybe.accept(() -> g.add("u16", r.nextInt(65536)));
        maybe.accept(() -> g.add("u32", r.nextInt()));
        maybe.accept(() -> g.add("u64", r.nextLong()));
        maybe.accept(() -> g.add("d_int", r.nextInt(-999_999_999, 1_000_000_000)));
        maybe.accept(() -> g.add("d_long", r.nextLong(-999_999_999_999_999_999L, 1_000_000_000_000_000_000L)));
        maybe.accept(() -> g.add("d_fixed", Binary.fromConstantByteArray(twosComplement(
                new BigInteger(67, new java.util.Random(r.nextLong())).mod(BigInteger.TEN.pow(20))
                        .multiply(BigInteger.valueOf(r.nextBoolean() ? 1 : -1)), 9))));
        maybe.accept(() -> g.add("d_bin", Binary.fromConstantByteArray(
                new BigInteger(126, new java.util.Random(r.nextLong())).mod(BigInteger.TEN.pow(38))
                        .multiply(BigInteger.valueOf(r.nextBoolean() ? 1 : -1)).toByteArray())));
        maybe.accept(() -> g.add("d_small", Binary.fromConstantByteArray(twosComplement(
                BigInteger.valueOf(r.nextLong(-999_999_999_999L, 1_000_000_000_000L)), 8))));
    }

    private static byte[] bytes(SplittableRandom r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    /** Returns a number in two's complement, big-endian, in exactly {@code n} bytes. */
    private static byte[] twosComplement(BigInteger v, int n) {
        byte[] minimal = v.toByteArray();
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, 0, n - minimal.length, v.signum() < 0 ? (byte) -1 : 0);
        System.arraycopy(minimal, 0, b, n - minimal.length, minimal.length);
        return b;
    }

    private record Variant(String name, Consumer<ExampleParquetWriter.Builder> options) {
    }

    @Test
    void parquetJavaFilesOfEveryWriterVersionEncodingAndCodecGiveTheRowsOfDuckDb() throws Exception {
        ParquetProperties.WriterVersion v1 = ParquetProperties.WriterVersion.PARQUET_1_0;
        ParquetProperties.WriterVersion v2 = ParquetProperties.WriterVersion.PARQUET_2_0;
        List<Variant> variants = List.of(
                new Variant("v1-dictionary", w -> w.withWriterVersion(v1).withDictionaryEncoding(true)
                        .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)),
                new Variant("v1-plain-small-pages", w -> w.withWriterVersion(v1).withDictionaryEncoding(false)
                        .withCompressionCodec(CompressionCodecName.SNAPPY).withPageRowCountLimit(64)
                        .withPageSize(1024)),
                new Variant("v2-dictionary", w -> w.withWriterVersion(v2).withDictionaryEncoding(true)
                        .withCompressionCodec(CompressionCodecName.GZIP)),
                new Variant("v2-delta", w -> w.withWriterVersion(v2).withDictionaryEncoding(false)
                        .withCompressionCodec(CompressionCodecName.ZSTD)),
                new Variant("v2-byte-stream-split", w -> w.withWriterVersion(v2).withDictionaryEncoding(false)
                        .withByteStreamSplitEncoding(true).withCompressionCodec(CompressionCodecName.LZ4_RAW)),
                new Variant("v1-dictionary-small-pages", w -> w.withWriterVersion(v1).withDictionaryEncoding(true)
                        .withDictionaryPageSize(512).withPageRowCountLimit(50)
                        .withCompressionCodec(CompressionCodecName.ZSTD)));
        long seed = 20261016;
        for (Variant variant : variants) {
            SplittableRandom random = new SplittableRandom(seed++);
            Path file = ParquetTestFiles.write(dir.resolve(variant.name() + ".parquet"), SCHEMA,
                    w -> variant.options().accept(w.withRowGroupRowCountLimit(700)), rows -> {
                        for (long id = 0; id < 2000; id++) {
                            long i = id;
                            rows.row(g -> fill(g, random, i));
                        }
                    });
            assertEquals(3, new ParquetFiles().footer(file).metadata().getBlocks().size(), variant.name());
            List<List<String>> expected = duckdb(file, "");
            for (int batchSize : List.of(1, 7, 2048)) {
                assertRows(expected, fyrql(connector(file, batchSize), null, OptionalLong.empty()),
                        variant.name() + " in batches of " + batchSize);
            }
        }
    }

    @Test
    void projectionsReadEachColumnOnceInAnyOrder() throws Exception {
        Path file = ordered(dir.resolve("ordered.parquet"), 5000, 1000);
        ParquetConnector c = connector(file, 300);
        Schema schema = c.catalog().schema(table());
        assertEquals(List.of("id", "name", "amount", "day"), schema.fields().stream().map(Schema.Field::name).toList());
        assertRows(duckdb(file, List.of("amount", "id", "amount"), ""), fyrql(c, List.of(2, 0, 2),
                OptionalLong.empty()), "amount, id, amount");
        List<ParquetConnector.Read> reads = c.reads();
        assertEquals(5, reads.size());
        for (int g = 0; g < 5; g++) {
            assertEquals(new ParquetConnector.Read(file.toAbsolutePath(), g, List.of("id", "amount"), 1000),
                    reads.get(g));
        }
        c.clearReads();
        // Without columns, as for count(*) without pushdown, only the numbers of rows are delivered.
        List<List<String>> none = fyrql(c, List.of(), OptionalLong.empty());
        assertEquals(5000, none.size());
        assertTrue(none.stream().allMatch(List::isEmpty));
        assertEquals(List.of(), c.reads().get(0).columns());
    }

    @Test
    void aLimitReadsOnlyTheRowGroupsItNeeds() throws Exception {
        Path file = ordered(dir.resolve("ordered.parquet"), 5000, 1000);
        ParquetConnector c = connector(file, 300);
        ScanPlan plan = c.plan(table(), request(List.of(0, 1), OptionalLong.of(2500)));
        assertTrue(plan.limitAccepted());
        assertEquals(3, c.splits(plan).size());
        assertRows(duckdb(file, List.of("id", "name"), " LIMIT 2500"), fyrql(c, List.of(0, 1), OptionalLong.of(2500)),
                "LIMIT 2500");
        assertEquals(List.of(1000L, 1000L, 500L), c.reads().stream().map(ParquetConnector.Read::rows).toList());
        assertEquals(0, c.splits(c.plan(table(), request(List.of(0), OptionalLong.of(0)))).size());
        assertEquals(5, c.splits(c.plan(table(), request(List.of(0), OptionalLong.of(5001)))).size());
    }

    @Test
    void countStarWithoutGroupsComesFromTheFooters() throws Exception {
        ParquetConnector c = connector(ordered(dir.resolve("ordered.parquet"), 5000, 1000), 300);
        PlanNode.AggregateCall count = new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false,
                "count_star()", null, List.of());
        ScanPlan plan = c.plan(table(), new ScanRequest(List.of(), List.of(), OptionalLong.empty(), List.of(),
                Optional.of(new PlanNode.Aggregation(List.of(), List.of(count, count)))));
        assertTrue(plan.aggregationAccepted());
        assertEquals(List.of(DataType.BIGINT, DataType.BIGINT), plan.output().types());
        List<Split> splits = c.splits(plan);
        assertEquals(1, splits.size());
        try (BatchReader reader = c.open(splits.get(0))) {
            Batch batch = reader.next();
            assertEquals(List.of(5000L, 5000L), List.of(batch.column(0).get(0), batch.column(1).get(0)));
            assertEquals(null, reader.next());
        }
        assertEquals(List.of(), c.reads());
        PlanNode.AggregateCall countId = new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT, 0, false,
                "count(id)", null, List.of());
        assertFalse(c.plan(table(), new ScanRequest(ColumnPath.whole(List.of(0)), List.of(), OptionalLong.empty(),
                List.of(), Optional.of(new PlanNode.Aggregation(List.of(), List.of(countId))))).aggregationAccepted());
        assertFalse(c.plan(table(), new ScanRequest(ColumnPath.whole(List.of(0)), List.of(), OptionalLong.empty(),
                List.of(), Optional.of(new PlanNode.Aggregation(List.of(0), List.of(count))))).aggregationAccepted());
    }

    @Test
    void emptyFilesAndRowGroupsOfOneRow() throws Exception {
        Path empty = ParquetTestFiles.write(dir.resolve("empty.parquet"), "message m { optional int64 a; }");
        ParquetConnector c = connector(empty, 7);
        assertEquals(List.of(), c.splits(c.plan(table(), request(List.of(0), OptionalLong.empty()))));
        assertEquals(List.of(), fyrql(c, null, OptionalLong.empty()));
        Path nothing = dir.resolve("nothing.parquet");
        ParquetTestFiles.duckdb("COPY (SELECT 1 AS a WHERE false) TO " + ParquetTestFiles.sql(nothing)
                + " (FORMAT parquet)");
        assertEquals(List.of(), fyrql(connector(nothing, 7), null, OptionalLong.empty()));

        Path ones = ParquetTestFiles.write(dir.resolve("ones.parquet"), "message m { optional int64 a; }",
                w -> w.withRowGroupRowCountLimit(1), rows -> {
                    for (long i = 0; i < 3; i++) {
                        long v = i;
                        rows.row(g -> {
                            if (v != 1) {
                                g.add("a", v);
                            }
                        });
                    }
                });
        ParquetConnector one = connector(ones, 7);
        assertEquals(3, one.splits(one.plan(table(), request(List.of(0), OptionalLong.empty()))).size());
        assertEquals(List.of(List.of("0"), List.of("NULL"), List.of("2")), fyrql(one, null, OptionalLong.empty()));
    }

    @Test
    void errorsNameTheFileTheRowGroupAndTheColumn() throws Exception {
        Path brotli = dir.resolve("brotli.parquet");
        ParquetTestFiles.duckdb("COPY (SELECT range AS a FROM range(10)) TO " + ParquetTestFiles.sql(brotli)
                + " (FORMAT parquet, COMPRESSION brotli)");
        assertEquals("Row group 0 of the Parquet file " + brotli.toAbsolutePath() + ": The codec BROTLI is not"
                + " supported; supported are UNCOMPRESSED, SNAPPY, GZIP, ZSTD, LZ4_RAW",
                assertThrows(IllegalArgumentException.class, () -> fyrql(connector(brotli, 7), null,
                        OptionalLong.empty())).getMessage());

        Path wide = ParquetTestFiles.write(dir.resolve("wide.parquet"), "message m { required int32 d (DECIMAL(4,2)); }",
                CompressionCodecName.UNCOMPRESSED, rows -> {
                    rows.row(g -> g.add("d", 1234));
                    rows.row(g -> g.add("d", -123456));
                });
        assertEquals("Row group 0 of the Parquet file " + wide.toAbsolutePath() + ", column d: The unscaled value"
                + " -123456 does not fit DECIMAL(4,2)", assertThrows(IllegalArgumentException.class,
                        () -> fyrql(connector(wide, 7), null, OptionalLong.empty())).getMessage());

        Path file = ParquetTestFiles.write(dir.resolve("changing.parquet"), "message m { required int64 id; }",
                CompressionCodecName.UNCOMPRESSED, rows -> rows.row(g -> g.add("id", 1L)));
        ParquetConnector c = connector(file, 7);
        ScanPlan plan = c.plan(table(), request(List.of(0), OptionalLong.empty()));
        List<Split> splits = c.splits(plan);
        rewrite(file, "message m { required int64 id; }", 2);
        assertEquals("The Parquet file " + file.toAbsolutePath() + " changed since its footer was read",
                assertThrows(IllegalArgumentException.class, () -> c.open(splits.get(0))).getMessage());
        assertEquals(List.of(List.of("0"), List.of("1")), fyrql(c, null, OptionalLong.empty()));
        rewrite(file, "message m { required int32 id; }", 4);
        assertEquals("The files of the table pq.main.t changed their columns since they were read:"
                + " (id BIGINT NOT NULL) and now (id INTEGER NOT NULL)", assertThrows(IllegalArgumentException.class,
                        () -> c.splits(plan)).getMessage());
    }

    /** Writes a file again with rows 0, 1, ... and a time of modification some seconds later. */
    private static void rewrite(Path file, String schema, int rows) throws IOException {
        FileTime before = Files.getLastModifiedTime(file);
        boolean wide = schema.contains("int64");
        ParquetTestFiles.write(file, schema, CompressionCodecName.UNCOMPRESSED, w -> {
            for (int i = 0; i < rows; i++) {
                int v = i;
                w.row(g -> {
                    if (wide) {
                        g.add("id", (long) v);
                    } else {
                        g.add("id", v);
                    }
                });
            }
        });
        Files.setLastModifiedTime(file, FileTime.fromMillis(before.toMillis() + 2000L * rows));
    }

    /** Writes rows with {@code id} in order, a {@code name}, an {@code amount} and a {@code day}, in row groups of a size. */
    private static Path ordered(Path file, int rows, int rowGroup) throws IOException {
        return ParquetTestFiles.write(file, """
                message m {
                  required int64 id;
                  optional binary name (STRING);
                  optional int64 amount (DECIMAL(12,2));
                  optional int32 day (DATE);
                }""", w -> w.withRowGroupRowCountLimit(rowGroup), w -> {
                    for (long id = 0; id < rows; id++) {
                        long i = id;
                        w.row(g -> {
                            g.add("id", i);
                            g.add("name", "n" + i % 97);
                            if (i % 11 != 0) {
                                g.add("amount", i * 37 - 50_000);
                            }
                            g.add("day", (int) (i % 20_000));
                        });
                    }
                });
    }

    private static ParquetConnector connector(Path file, int batchSize) {
        return ParquetConnector.builder("pq").path("t", file).batchSize(batchSize).recordReads(true).build();
    }

    private static TableRef table() {
        return TableRef.of("pq", "t");
    }

    private static ScanRequest request(List<Integer> columns, OptionalLong limit) {
        return new ScanRequest(ColumnPath.whole(columns), List.of(), limit, List.of(), Optional.empty());
    }

    /** Reads the columns, all of them if {@code null}, of the table {@code t}, each value as text. */
    private static List<List<String>> fyrql(ParquetConnector c, List<Integer> columns, OptionalLong limit) {
        List<Integer> read = columns != null ? columns
                : IntStream.range(0, c.catalog().schema(table()).size()).boxed().toList();
        ScanPlan plan = c.plan(table(), request(read, limit));
        List<List<String>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            try (BatchReader reader = c.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    for (int i = 0; i < b.rowCount(); i++) {
                        List<String> row = new ArrayList<>();
                        for (int col = 0; col < b.schema().size(); col++) {
                            row.add(text(b.column(col).get(i)));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static String text(Object v) {
        return switch (v) {
            case null -> "NULL";
            case Float f -> Double.toString(f.doubleValue());
            case Double d -> Double.toString(d);
            case byte[] b -> HexFormat.of().withUpperCase().formatHex(b);
            case BigDecimal d -> d.toPlainString();
            default -> v.toString();
        };
    }

    /** Lets DuckDB read all columns of a file, each value as text. */
    private static List<List<String>> duckdb(Path file, String suffix) throws SQLException {
        return duckdb(file, null, suffix);
    }

    /**
     * Lets DuckDB read columns of a file, all of them if {@code null}, each
     * value as text, the type of FyrQL from the connector.
     */
    private static List<List<String>> duckdb(Path file, List<String> columns, String suffix) throws SQLException {
        Schema schema = connector(file, 7).catalog().schema(table());
        List<String> names = new ArrayList<>();
        List<String> duckTypes = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("DESCRIBE SELECT * FROM read_parquet(" + ParquetTestFiles.sql(file)
                        + ")")) {
            while (rs.next()) {
                names.add(rs.getString("column_name"));
                duckTypes.add(rs.getString("column_type"));
            }
        }
        List<String> read = columns == null ? names : columns;
        List<String> expressions = new ArrayList<>();
        List<DataType> types = new ArrayList<>();
        for (String name : read) {
            int c = names.indexOf(name);
            DataType type = schema.type(c);
            types.add(type);
            String column = "\"" + name + "\"";
            expressions.add(switch (type) {
                case DataType.Scalar s when s == DataType.FLOAT || s == DataType.DOUBLE -> column;
                case DataType.Scalar s when s == DataType.BLOB -> "hex(" + column + ")";
                case DataType.Scalar s when s == DataType.DATE -> "CAST(" + column + " - DATE '1970-01-01' AS VARCHAR)";
                case DataType.Scalar s when s == DataType.TIMESTAMP -> duckTypes.get(c).contains("TIME ZONE")
                        ? "epoch_us(" + column + ")" : "epoch_us(CAST(" + column + " AS TIMESTAMP))";
                default -> "CAST(" + column + " AS VARCHAR)";
            });
        }
        List<List<String>> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT " + String.join(", ", expressions) + " FROM read_parquet("
                        + ParquetTestFiles.sql(file) + ")" + suffix)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 0; i < expressions.size(); i++) {
                    Object v = rs.getObject(i + 1);
                    row.add(v == null ? "NULL" : types.get(i) == DataType.FLOAT || types.get(i) == DataType.DOUBLE
                            ? Double.toString(((Number) v).doubleValue()) : rs.getString(i + 1));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Compares rows, reporting the first that differs. */
    private static void assertRows(List<List<String>> expected, List<List<String>> actual, String what) {
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            assertEquals(expected.get(i), actual.get(i), what + ", row " + i);
        }
        assertEquals(expected.size(), actual.size(), what + ", rows");
    }
}
