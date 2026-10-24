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

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.FileStores;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.Vector;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoder of pages against the column readers of parquet-java as the
 * oracle: the same file read through the same row group reader with and
 * without the decoder gives the same batches, vector by vector and value
 * by value, nulls included. The files come from the writers of
 * parquet-java in both versions, with and without dictionaries, with small
 * dictionary pages that switch to plain values within a chunk, with pages
 * of 1 to 100 rows, a tenth to all values {@code NULL}, in all codecs, and
 * from DuckDB.
 */
class PageDecoderDifferentialTest {

    @TempDir
    Path dir;

    private record Variant(String name, Consumer<ExampleParquetWriter.Builder> options) {
    }

    private static final ParquetProperties.WriterVersion V1 = ParquetProperties.WriterVersion.PARQUET_1_0;
    private static final ParquetProperties.WriterVersion V2 = ParquetProperties.WriterVersion.PARQUET_2_0;

    /** The writers, each with another share of {@code NULL}s: none, a tenth, half, all. */
    private static final List<Variant> VARIANTS = List.of(
            new Variant("v1-dictionary", w -> w.withWriterVersion(V1).withDictionaryEncoding(true)
                    .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)),
            new Variant("v1-plain-pages-of-7", w -> w.withWriterVersion(V1).withDictionaryEncoding(false)
                    .withCompressionCodec(CompressionCodecName.SNAPPY).withPageRowCountLimit(7)),
            new Variant("v2-dictionary-pages-of-100", w -> w.withWriterVersion(V2).withDictionaryEncoding(true)
                    .withCompressionCodec(CompressionCodecName.GZIP).withPageRowCountLimit(100)),
            new Variant("v1-small-dictionary-pages-of-1", w -> w.withWriterVersion(V1).withDictionaryEncoding(true)
                    .withDictionaryPageSize(256).withPageRowCountLimit(1)
                    .withCompressionCodec(CompressionCodecName.LZ4_RAW)),
            new Variant("v2-plain", w -> w.withWriterVersion(V2).withDictionaryEncoding(false)
                    .withCompressionCodec(CompressionCodecName.ZSTD)),
            new Variant("v1-small-dictionary", w -> w.withWriterVersion(V1).withDictionaryEncoding(true)
                    .withDictionaryPageSize(512).withPageRowCountLimit(50)
                    .withCompressionCodec(CompressionCodecName.ZSTD)),
            new Variant("v2-small-dictionary", w -> w.withWriterVersion(V2).withDictionaryEncoding(true)
                    .withDictionaryPageSize(512).withCompressionCodec(CompressionCodecName.SNAPPY)),
            new Variant("v2-byte-stream-split", w -> w.withWriterVersion(V2).withDictionaryEncoding(false)
                    .withByteStreamSplitEncoding(true).withCompressionCodec(CompressionCodecName.UNCOMPRESSED)));

    private static final int[][] NULLS = {{0, 0}, {1, 1}, {0, 5}, {10, 0}};

    @Test
    void filesOfParquetJavaGiveTheBatchesOfTheColumnReaders() throws Exception {
        long seed = 20261024;
        for (int v = 0; v < VARIANTS.size(); v++) {
            Variant variant = VARIANTS.get(v);
            int[] nulls = NULLS[v % NULLS.length];
            SplittableRandom random = new SplittableRandom(seed + v);
            Path file = ParquetTestFiles.write(dir.resolve(variant.name() + ".parquet"), ParquetReadTest.SCHEMA,
                    w -> variant.options().accept(w.withRowGroupRowCountLimit(700)), rows -> {
                        for (long id = 0; id < 2000; id++) {
                            long i = id;
                            rows.row(g -> ParquetReadTest.fill(g, random, i, nulls[0], nulls[1]));
                        }
                    });
            ParquetFile parquet = open(file);
            Schema all = parquet.schema();
            List<ColumnPath> columns = ColumnPath.whole(range(all.size()));
            String what = variant.name() + " with " + nulls[0] + "+" + nulls[1] + " tenths NULL";
            Map<String, Integer> decoded = new java.util.TreeMap<>();
            for (int batchSize : new int[] {1, 7, 2048}) {
                String in = what + " in batches of " + batchSize;
                decoded = assertSameBatches(parquet, all, columns, List.of(), batchSize, Long.MAX_VALUE, in);
                Map<String, int[]> forms = forms(parquet, all, columns, batchSize, in);
                if (variant.name().equals("v1-dictionary")) {
                    // Few distinct values keep the dictionary for the whole chunk.
                    for (String name : List.of("e", "j")) {
                        assertTrue(forms.get(name)[0] > 0 && forms.get(name)[1] == 0, in + ", column " + name);
                    }
                } else if (variant.name().contains("plain") || variant.name().contains("split")) {
                    assertTrue(forms.values().stream().allMatch(f -> f[0] == 0), in);
                }
            }
            // A limit below the rows of a row group.
            assertSameBatches(parquet, all, columns, List.of(), 64, 500, what + " up to 500 rows");
            if (variant.name().equals("v1-dictionary")) {
                assertEquals(all.size() * parquet.rowGroups(), decoded.values().stream().mapToInt(i -> i).sum(),
                        what + ": " + decoded);
            }
            assertFalse(decoded.isEmpty(), what);
        }
    }

    @Test
    void stringsGoOnInAnotherEncodingWithinAChunk() throws Exception {
        String schema = "message m { required int64 id; optional binary s (STRING); }";
        String[] words = {"MAIL", "SHIP", "TRUCK", "AIR"};
        for (ParquetProperties.WriterVersion version : List.of(V1, V2)) {
            // A few words first, then unique values: the dictionary fills up, and the writer goes on in PLAIN
            // (v1) or DELTA_BYTE_ARRAY (v2); the metadata of v2 names the latter, so parquet-java reads the chunk.
            Path file = ParquetTestFiles.write(dir.resolve("switch-" + version + ".parquet"), schema,
                    w -> w.withWriterVersion(version).withDictionaryEncoding(true).withDictionaryPageSize(256)
                            .withPageRowCountLimit(50).withRowGroupRowCountLimit(1000)
                            .withCompressionCodec(CompressionCodecName.SNAPPY),
                    rows -> {
                        for (long id = 0; id < 2000; id++) {
                            long i = id;
                            rows.row(g -> {
                                g.add("id", i);
                                if (i % 7 != 3) {
                                    g.add("s", i % 1000 < 300 ? words[(int) (i % words.length)]
                                            : "unique value " + i);
                                }
                            });
                        }
                    });
            ParquetFile parquet = open(file);
            Schema all = parquet.schema();
            List<ColumnPath> columns = ColumnPath.whole(range(all.size()));
            for (int batchSize : new int[] {1, 7, 128, 2048}) {
                String what = version + " in batches of " + batchSize;
                assertSameBatches(parquet, all, columns, List.of(), batchSize, Long.MAX_VALUE, what);
                int[] f = forms(parquet, all, columns, batchSize, what).get("s");
                // A batch with rows of both encodings is made flat; a row group in one batch has such rows.
                assertEquals(version == V1 && batchSize < 1000, f[0] > 0, what);
                assertTrue(f[1] > 0, what);
            }
        }
    }

    @Test
    void widenedColumnsAndPageFilters() throws Exception {
        SplittableRandom random = new SplittableRandom(7);
        Path file = ParquetTestFiles.write(dir.resolve("wide.parquet"), ParquetReadTest.SCHEMA,
                w -> w.withWriterVersion(V1).withDictionaryEncoding(true).withPageRowCountLimit(100)
                        .withRowGroupRowCountLimit(1000).withCompressionCodec(CompressionCodecName.SNAPPY),
                rows -> {
                    for (long id = 0; id < 2000; id++) {
                        long i = id;
                        rows.row(g -> ParquetReadTest.fill(g, random, i, 1, 1));
                    }
                });
        ParquetFile parquet = open(file);
        Schema schema = parquet.schema();
        Map<String, DataType> wider = Map.of("i32", DataType.BIGINT, "f32", DataType.DOUBLE,
                "d_int", DataType.decimal(20, 2), "d_long", DataType.decimal(19, 4),
                "d_small", DataType.decimal(15, 2), "i8", DataType.INTEGER, "u16", DataType.BIGINT);
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            fields.add(new Schema.Field(f.name(), wider.getOrDefault(f.name(), f.type()), f.nullable()));
        }
        Schema output = new Schema(fields);
        List<ColumnPath> columns = ColumnPath.whole(range(schema.size()));
        Map<String, Integer> decoded = assertSameBatches(parquet, output, columns, List.of(), 333, Long.MAX_VALUE,
                "widened");
        for (String name : wider.keySet()) {
            assertEquals(2, decoded.get(name), name);
        }
        // Under a filter of pages, every column goes through the column readers.
        Expression late = new Expression.Comparison(Expression.ComparisonOp.GE,
                new Expression.Column(0, DataType.BIGINT), new Expression.Literal(DataType.BIGINT, 1550L));
        assertTrue(assertSameBatches(parquet, output, columns, List.of(late), 64, Long.MAX_VALUE, "filtered")
                .isEmpty());
    }

    @Test
    void fieldsOfStructsAloneAndBesideWholeStructs() throws Exception {
        String schema = """
                message s {
                  required int64 id;
                  optional group g {
                    optional int32 a;
                    required binary b (STRING);
                    optional double c;
                  }
                  required group h {
                    optional int64 x;
                  }
                }""";
        SplittableRandom r = new SplittableRandom(11);
        Path file = ParquetTestFiles.write(dir.resolve("struct.parquet"), schema,
                w -> w.withWriterVersion(V2).withDictionaryEncoding(true).withPageRowCountLimit(30)
                        .withRowGroupRowCountLimit(400).withCompressionCodec(CompressionCodecName.ZSTD),
                rows -> {
                    for (long id = 0; id < 1000; id++) {
                        long i = id;
                        rows.row(g -> {
                            g.add("id", i);
                            if (r.nextInt(5) != 0) {
                                Group sub = g.addGroup("g");
                                if (r.nextInt(4) != 0) {
                                    sub.add("a", r.nextInt(50));
                                }
                                sub.add("b", "b" + r.nextInt(20));
                                if (r.nextInt(4) != 0) {
                                    sub.add("c", r.nextDouble());
                                }
                            }
                            Group h = g.addGroup("h");
                            if (r.nextBoolean()) {
                                // Few values, so that the writer keeps the dictionary instead of DELTA_BINARY_PACKED.
                                h.add("x", r.nextLong(100));
                            }
                        });
                    }
                });
        ParquetFile parquet = open(file);
        Schema all = parquet.schema();
        DataType.Struct g = (DataType.Struct) all.type(1);
        DataType.Struct h = (DataType.Struct) all.type(2);
        Schema fields = new Schema(List.of(new Schema.Field("c", g.fields().get(2).type(), true),
                new Schema.Field("a", g.fields().get(0).type(), true),
                new Schema.Field("x", h.fields().get(0).type(), true),
                new Schema.Field("b", g.fields().get(1).type(), true)));
        List<ColumnPath> paths = List.of(new ColumnPath(1, List.of("c")), new ColumnPath(1, List.of("a")),
                new ColumnPath(2, List.of("x")), new ColumnPath(1, List.of("b")));
        for (int batchSize : new int[] {1, 7, 2048}) {
            Map<String, Integer> decoded = assertSameBatches(parquet, fields, paths, List.of(), batchSize,
                    Long.MAX_VALUE, "fields in batches of " + batchSize);
            assertEquals(Map.of("g.a", 3, "g.b", 3, "g.c", 3, "h.x", 3), decoded);
        }
        // The whole struct h through records beside fields of g through decoders.
        Schema mixed = new Schema(List.of(all.fields().get(2), new Schema.Field("b", g.fields().get(1).type(), true),
                all.fields().get(0)));
        Map<String, Integer> decoded = assertSameBatches(parquet, mixed, List.of(ColumnPath.whole(2),
                new ColumnPath(1, List.of("b")), ColumnPath.whole(0)), List.of(), 100, Long.MAX_VALUE, "mixed");
        // Unique ids make the writer v2 fall back from the dictionary to DELTA_BINARY_PACKED, read by parquet-java.
        assertEquals(Map.of("g.b", 3), decoded);
    }

    @Test
    void filesOfDuckDbGiveTheBatchesOfTheColumnReaders() throws Exception {
        List<String> statements = new ArrayList<>(List.of("SELECT setseed(0.5)", ParquetReadTest.RANDOM));
        List<Path> files = new ArrayList<>();
        for (String version : List.of("V1", "V2")) {
            for (String codec : List.of("uncompressed", "snappy", "zstd")) {
                Path file = dir.resolve("duck-" + codec + "-" + version + ".parquet");
                files.add(file);
                statements.add("COPY t TO " + ParquetTestFiles.sql(file) + " (FORMAT parquet, COMPRESSION " + codec
                        + ", PARQUET_VERSION " + version + ", ROW_GROUP_SIZE 2048)");
            }
        }
        ParquetTestFiles.duckdb(statements.toArray(String[]::new));
        for (Path file : files) {
            ParquetFile parquet = open(file);
            Schema all = parquet.schema();
            List<ColumnPath> columns = ColumnPath.whole(range(all.size()));
            Map<String, Integer> decoded = Map.of();
            for (int batchSize : new int[] {7, 2048}) {
                decoded = assertSameBatches(parquet, all, columns, List.of(), batchSize, Long.MAX_VALUE,
                        file.getFileName() + " in batches of " + batchSize);
                forms(parquet, all, columns, batchSize, file.getFileName() + " in batches of " + batchSize);
            }
            // Without a column index, a filter of pages skips none, and the decoder reads the pages all the same.
            Expression late = new Expression.Comparison(Expression.ComparisonOp.GE,
                    new Expression.Column(0, all.type(0)), new Expression.Literal(DataType.BIGINT, 3000L));
            Map<String, Integer> filtered = assertSameBatches(parquet, all, columns, List.of(late), 2048,
                    Long.MAX_VALUE, file.getFileName() + " under a filter");
            if (file.getFileName().toString().endsWith("V1.parquet")) {
                // DuckDB writes pages v1 in PLAIN and RLE_DICTIONARY only: every leaf of every row group decoded.
                assertEquals(all.size() * parquet.rowGroups(), decoded.values().stream().mapToInt(i -> i).sum(),
                        file.getFileName() + ": " + decoded);
                assertEquals(decoded, filtered, file.getFileName() + " under a filter");
            }
        }
    }

    private static ParquetFile open(Path file) {
        return ParquetFile.open(FileStores.local(file.getParent()), file.toString(), ParquetFiles.READ_AHEAD);
    }

    private static List<Integer> range(int n) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    /**
     * Reads all row groups with and without the decoder and compares the
     * batches; returns for each leaf the row groups the decoder read it in
     * without falling back.
     */
    private static Map<String, Integer> assertSameBatches(ParquetFile parquet, Schema output,
            List<ColumnPath> columns, List<Expression> filters, int batchSize, long limit, String what) {
        Map<String, Integer> decoded = new java.util.TreeMap<>();
        for (int group = 0; group < parquet.rowGroups(); group++) {
            long rows = Math.min(limit, parquet.rows(group));
            String where = what + ", row group " + group;
            try (RowGroupReader actual = parquet.read(group, rows, output, columns, filters, batchSize, null, true);
                    RowGroupReader expected = parquet.read(group, rows, output, columns, filters, batchSize, null,
                            false)) {
                assertTrue(expected.decodedLeaves().isEmpty(), where);
                for (int b = 0; ; b++) {
                    Batch e = expected.next();
                    Batch a = actual.next();
                    if (e == null) {
                        assertNull(a, where + ", batch " + b);
                        break;
                    }
                    assertEquals(e.rowCount(), a.rowCount(), where + ", batch " + b);
                    for (int c = 0; c < output.size(); c++) {
                        assertSameVector(e.columns().get(c), a.columns().get(c), where + ", batch " + b + ", column "
                                + output.name(c));
                    }
                }
                for (String leaf : new TreeSet<>(actual.decodedLeaves())) {
                    decoded.merge(leaf, 1, Integer::sum);
                }
            }
        }
        return decoded;
    }

    /**
     * Reads all row groups with the decoder and counts for each column of
     * strings or blobs the batches in the dictionary form and in the flat
     * form; asserts that the batches of a column in a row group share one
     * dictionary.
     */
    private static Map<String, int[]> forms(ParquetFile parquet, Schema output, List<ColumnPath> columns,
            int batchSize, String what) {
        Map<String, int[]> forms = new java.util.TreeMap<>();
        for (int group = 0; group < parquet.rowGroups(); group++) {
            try (RowGroupReader reader = parquet.read(group, parquet.rows(group), output, columns, List.of(),
                    batchSize, null, true)) {
                Map<String, BytesVector.Dictionary> seen = new java.util.HashMap<>();
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    for (int c = 0; c < output.size(); c++) {
                        if (b.column(c) instanceof BytesVector v) {
                            String name = output.name(c);
                            int[] f = forms.computeIfAbsent(name, k -> new int[2]);
                            if (v.hasDictionary()) {
                                f[0]++;
                                BytesVector.Dictionary d = seen.putIfAbsent(name, v.dictionary());
                                assertTrue(d == null || d == v.dictionary(), what + ", row group " + group
                                        + ", column " + name + ": one dictionary for all batches");
                            } else {
                                f[1]++;
                            }
                        }
                    }
                }
            }
        }
        return forms;
    }

    private static void assertSameVector(Vector expected, Vector actual, String what) {
        assertEquals(expected.type(), actual.type(), what);
        assertEquals(expected.size(), actual.size(), what);
        assertEquals(expected.hasNulls(), actual.hasNulls(), what);
        for (int r = 0; r < expected.size(); r++) {
            assertEquals(expected.isNull(r), actual.isNull(r), what + ", row " + r);
            Object e = expected.get(r);
            Object a = actual.get(r);
            if (e instanceof byte[] bytes) {
                assertArrayEquals(bytes, (byte[]) a, what + ", row " + r);
            } else {
                assertEquals(e, a, what + ", row " + r);
            }
        }
    }
}
