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

package net.fyrql.connector.json;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** JSON files that {@code COPY ... TO} writes, through {@link JsonConnector#openFile}. */
class JsonFileWriterTest {

    private static final Schema ALL_TYPES = Schema.of(new Schema.Field("i", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("d", DataType.DOUBLE),
            new Schema.Field("f", DataType.FLOAT), new Schema.Field("dec", DataType.decimal(10, 2)),
            new Schema.Field("hi", DataType.HUGEINT), new Schema.Field("dt", DataType.DATE),
            new Schema.Field("ts", DataType.TIMESTAMP), new Schema.Field("b", DataType.BLOB),
            new Schema.Field("u", DataType.UUID), new Schema.Field("bo", DataType.BOOLEAN),
            new Schema.Field("st", DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
                    new DataType.Struct.Field("y", DataType.VARCHAR))),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));

    /**
     * What DuckDB 1.5 writes for {@link #allTypes()} with
     * {@code COPY ... TO 'x.json'}, except that a {@code DECIMAL} keeps its
     * scale and a {@code FLOAT} is as short as a float allows, which read
     * back the same: DuckDB writes {@code -12.3}, {@code 0.0} and
     * {@code 1.500000053056283e-7}.
     */
    private static final String JSON_LINES = """
            {"i":1,"s":"plain","d":0.1,"f":1.5,"dec":-12.30,"hi":170141183460469231731687303715884105727,\
            "dt":"2024-02-29","ts":"2024-02-29 13:14:15.123456","b":"\\\\x00\\\\xFFA",\
            "u":"123e4567-e89b-12d3-a456-426614174000","bo":true,"st":{"x":1,"y":"q"},"l":[1,null,3],\
            "m":{"a":1,"b":null}}
            {"i":null,"s":"","d":NaN,"f":Infinity,"dec":null,"hi":null,"dt":"0001-01-01","ts":"1500-01-01 00:00:00",\
            "b":"","u":null,"bo":false,"st":{"x":null,"y":null},"l":[],"m":{}}
            {"i":3,"s":"a,b\\"c\\nd","d":-0.0,"f":-Infinity,"dec":0.00,"hi":-1,"dt":"9999-12-31",\
            "ts":"1970-01-01 00:00:00","b":null,"u":null,"bo":null,"st":null,"l":null,"m":null}
            {"i":4,"s":null,"d":100000000000000000000.0,"f":1.5e-7,"dec":null,"hi":null,"dt":null,"ts":null,\
            "b":null,"u":null,"bo":null,"st":null,"l":[null],"m":null}
            """;

    @TempDir
    Path dir;

    private static Batch allTypes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", null);
        List<Object[]> rows = List.of(
                new Object[] {1, "plain", 0.1, 1.5f, new BigDecimal("-12.30"),
                    new BigInteger("170141183460469231731687303715884105727"), Conversions.parseDate("2024-02-29"),
                    Conversions.parseTimestamp("2024-02-29 13:14:15.123456"), new byte[] {0, (byte) 0xff, 'A'},
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), true, List.of(1, "q"),
                    Arrays.asList(1, null, 3), m},
                new Object[] {null, "", Double.NaN, Float.POSITIVE_INFINITY, null, null,
                    Conversions.parseDate("0001-01-01"), Conversions.parseTimestamp("1500-01-01 00:00:00"),
                    new byte[0], null, false, Arrays.asList(null, null), List.of(), Map.of()},
                new Object[] {3, "a,b\"c\nd", -0.0, Float.NEGATIVE_INFINITY, new BigDecimal("0.00"), BigInteger.ONE
                    .negate(), Conversions.parseDate("9999-12-31"), 0L, null, null, null, null, null, null},
                new Object[] {4, null, 1e20, 1.5e-7f, null, null, null, null, null, null, null, null,
                    Arrays.asList((Object) null), null});
        return Batch.ofRows(ALL_TYPES, rows);
    }

    private static JsonConnector connector() {
        return JsonConnector.builder("json").build();
    }

    private static long write(Path file, Schema schema, Map<String, String> options, Batch... batches) {
        try (Sink sink = connector().openFile("json", file, schema, options)) {
            for (Batch b : batches) {
                sink.write(b);
            }
            return sink.commit();
        }
    }

    private static List<List<Object>> read(Path file, Schema schema) {
        JsonConnector c = JsonConnector.builder("json").schema("t", schema).file("t", file).build();
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(i);
        }
        ScanPlan plan = c.plan(TableRef.of("json", "t"), ScanRequest.columns(columns));
        return Batches.readAll(c.open(c.splits(plan).get(0))).stream().flatMap(b -> b.rows().stream())
                .map(Arrays::asList).toList();
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(Path::getFileName).sorted().toList();
        }
    }

    @Test
    void writesJsonLinesAsDuckDbDoesAndReadsThemBack() throws IOException {
        Path file = dir.resolve("all.json");
        Batch batch = allTypes();
        assertEquals(4, write(file, ALL_TYPES, Map.of(), batch.select(new int[] {0}), Batch.empty(ALL_TYPES),
                batch.select(new int[] {1, 2, 3})));
        assertEquals(JSON_LINES, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(List.of(Path.of("all.json")), files());

        // The reader takes NaN and the infinities, and the values of all types back as they were.
        List<List<Object>> rows = read(file, ALL_TYPES);
        List<List<Object>> expected = batch.rows().stream().map(Arrays::asList).toList();
        for (int r = 0; r < expected.size(); r++) {
            for (int c = 0; c < ALL_TYPES.size(); c++) {
                Object e = expected.get(r).get(c);
                Object a = rows.get(r).get(c);
                if (e instanceof byte[] bytes) {
                    assertEquals(Arrays.toString(bytes), Arrays.toString((byte[]) a), r + "," + c);
                } else {
                    assertEquals(e, a, r + "," + c);
                }
            }
        }
    }

    @Test
    void anArrayHoldsTheRowsWhenAskedFor() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.BIGINT), new Schema.Field("d", DataType.DATE),
                new Schema.Field("t", DataType.TIMESTAMP));
        Batch batch = Batch.ofRows(schema, List.of(new Object[] {1L, Conversions.parseDate("2024-03-05"),
            Conversions.parseTimestamp("2024-03-05 07:08:09")}, new Object[] {2L, null, null}));
        Path file = dir.resolve("a.json");
        assertEquals(2, write(file, schema, Map.of("array", "true", "dateformat", "%d.%m.%Y", "timestampformat",
                "%H:%M"), batch));
        assertEquals("[\n\t{\"n\":1,\"d\":\"05.03.2024\",\"t\":\"07:08\"},\n\t{\"n\":2,\"d\":null,\"t\":null}\n]\n",
                Files.readString(file));
        assertEquals(List.of(List.of(1L), List.of(2L)), read(file, Schema.of(schema.fields().get(0))));
        write(file, schema, Map.of("array", "true"));
        assertEquals("[\n]\n", Files.readString(file));
        write(file, schema, Map.of());
        assertEquals("", Files.readString(file));
    }

    @Test
    void gzipCompressesAFileThatEndsInGzOrWhenAskedFor() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.BIGINT));
        Batch batch = Batch.ofRows(schema, List.of(new Object[] {1L}, new Object[] {null}));
        Path gz = dir.resolve("n.json.gz");
        write(gz, schema, Map.of(), batch);
        assertEquals("{\"n\":1}\n{\"n\":null}\n", gunzip(gz));
        assertEquals(Arrays.asList(List.of(1L), Arrays.asList((Object) null)), read(gz, schema));
        Path asked = dir.resolve("n.ndjson");
        write(asked, schema, Map.of("compression", "gzip"), batch);
        assertEquals("{\"n\":1}\n{\"n\":null}\n", gunzip(asked));
    }

    private static String gunzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void closeAloneLeavesNoTraceAndWrongOptionsFailFirst() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER));
        Path file = dir.resolve("n.json");
        try (Sink sink = connector().openFile("json", file, schema, Map.of())) {
            sink.write(Batch.ofRows(schema, List.<Object[]>of(new Object[] {1})));
            assertEquals(1, files().size());
        }
        assertEquals(List.of(), files());
        assertEquals("The JSON file " + file + " has an unknown option header; known are array, dateformat,"
                + " timestampformat, compression", assertThrows(IllegalArgumentException.class,
                        () -> write(file, schema, Map.of("header", "true"))).getMessage());
        assertEquals("The option compression of the JSON file " + file + " must be gzip, none or auto, not zstd",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("compression",
                        "zstd"))).getMessage());
        assertEquals("The JSON source json writes no files of the format csv",
                assertThrows(IllegalArgumentException.class, () -> connector().openFile("csv", file, schema,
                        Map.of())).getMessage());
        assertEquals(List.of(), files());
        assertEquals(List.of("json"), connector().fileFormats());
    }
}
