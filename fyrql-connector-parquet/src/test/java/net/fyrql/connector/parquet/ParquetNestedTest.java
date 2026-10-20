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
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads {@code STRUCT}, {@code LIST} and {@code MAP} columns of files that
 * parquet-java writes, in every form of lists and maps the specification
 * allows, with {@code NULL} on every level, empty lists and maps, and
 * primitive columns among them: the values must be those written, and
 * those DuckDB reads where it follows the specification. Values are
 * compared as JSON in the form of DuckDB's {@code to_json}.
 */
class ParquetNestedTest {

    @TempDir
    Path dir;

    /**
     * A column of every form: {@code la} and {@code lt} are lists of a
     * group named {@code array} or after the list with {@code _tuple}, so
     * that the group is the element; {@code lp} is one of a group of two
     * fields; {@code l2}, {@code r} and {@code rs.names} are repeated
     * primitives; {@code mkv} is a {@code MAP_KEY_VALUE}.
     */
    private static final String SCHEMA = """
            message nested {
              required int64 id;
              optional group s {
                optional int32 a;
                optional binary b (STRING);
                optional group c {
                  optional double x;
                  optional group tags (LIST) { repeated group list { optional binary element (STRING); } }
                }
              }
              optional int32 flat;
              optional group l3 (LIST) { repeated group list { optional int32 element; } }
              optional group l2 (LIST) { repeated int32 element; }
              optional group la (LIST) { repeated group array { optional int32 x; } }
              optional group lt (LIST) { repeated group lt_tuple { optional int32 x; } }
              optional group lp (LIST) { repeated group pair { required int32 a; optional binary b (STRING); } }
              optional group ls (LIST) {
                repeated group list {
                  optional group element {
                    optional int32 p;
                    optional group q (LIST) { repeated group list { optional int32 element; } }
                  }
                }
              }
              repeated int32 r;
              optional group rs { repeated binary names (STRING); optional int32 z; }
              optional group m (MAP) {
                repeated group key_value { required binary key (STRING); optional group value { optional int32 v; } }
              }
              optional group mkv (MAP) {
                repeated group map (MAP_KEY_VALUE) { required int32 key; optional binary value (STRING); }
              }
              optional group mm (MAP) {
                repeated group key_value {
                  required binary key (STRING);
                  optional group value (MAP) { repeated group key_value { required int32 key; optional int32 value; } }
                }
              }
              optional group ll (LIST) {
                repeated group list {
                  optional group element (LIST) { repeated group list { optional int32 element; } }
                }
              }
              required group req { required int32 k; optional binary t (STRING); }
            }""";

    private static final int S = 1;
    private static final int RS = 10;
    private static final int REQ = 15;

    @Test
    void everyFormOfStructsListsAndMapsGivesTheValuesWritten() throws Exception {
        ParquetProperties.WriterVersion v1 = ParquetProperties.WriterVersion.PARQUET_1_0;
        ParquetProperties.WriterVersion v2 = ParquetProperties.WriterVersion.PARQUET_2_0;
        List<Map.Entry<String, Consumer<ExampleParquetWriter.Builder>>> variants = List.of(
                Map.entry("v1-dictionary-small-pages", w -> w.withWriterVersion(v1).withDictionaryEncoding(true)
                        .withRowGroupRowCountLimit(700).withPageRowCountLimit(50).withPageSize(512)),
                Map.entry("v2-plain-snappy", w -> w.withWriterVersion(v2).withDictionaryEncoding(false)
                        .withRowGroupRowCountLimit(900).withCompressionCodec(CompressionCodecName.SNAPPY)));
        for (Map.Entry<String, Consumer<ExampleParquetWriter.Builder>> variant : variants) {
            List<List<Object>> written = new ArrayList<>();
            Path file = write(dir.resolve(variant.getKey() + ".parquet"), 2000, variant.getValue(), written);
            assertTrue(ParquetTestFiles.footer(file).metadata().getBlocks().size() > 1, file.toString());
            ParquetConnector parquet = connector(file, 7);
            Schema schema = parquet.catalog().schema(table());
            assertEquals("(id BIGINT NOT NULL, s STRUCT(a INTEGER, b VARCHAR, c STRUCT(x DOUBLE, tags VARCHAR[])),"
                    + " flat INTEGER, l3 INTEGER[], l2 INTEGER[], la STRUCT(x INTEGER)[], lt STRUCT(x INTEGER)[],"
                    + " lp STRUCT(a INTEGER, b VARCHAR)[], ls STRUCT(p INTEGER, q INTEGER[])[], r INTEGER[] NOT NULL,"
                    + " rs STRUCT(\"names\" VARCHAR[], z INTEGER), m MAP(VARCHAR, STRUCT(v INTEGER)),"
                    + " mkv MAP(INTEGER, VARCHAR), mm MAP(VARCHAR, MAP(INTEGER, INTEGER)), ll INTEGER[][],"
                    + " req STRUCT(k INTEGER, t VARCHAR) NOT NULL)", ParquetFiles.columns(schema));
            List<List<String>> expected = json(written, schema.types());
            for (int batchSize : List.of(1, 7, 2048)) {
                assertRows(expected, read(connector(file, batchSize), whole(schema.size())),
                        variant.getKey() + " in batches of " + batchSize);
            }
        }
    }

    @Test
    void duckDbReadsTheSameValuesExceptForTheGroupsTheSpecificationMakesElements() throws Exception {
        List<List<Object>> written = new ArrayList<>();
        Path file = write(dir.resolve("nested.parquet"), 500, w -> w.withRowGroupRowCountLimit(200), written);
        Schema schema = connector(file, 7).catalog().schema(table());
        List<Integer> same = new ArrayList<>(IntStream.range(0, schema.size()).boxed().toList());
        // DuckDB reads la and lt as lists of the integer, where the specification makes the group the element.
        same.remove(Integer.valueOf(schema.indexOf("lt")));
        same.remove(Integer.valueOf(schema.indexOf("la")));
        List<String> expressions = same.stream().map(c -> "to_json(\"" + schema.fields().get(c).name() + "\")")
                .toList();
        List<List<String>> duckdb = ParquetTestFiles.duckdbRows("SELECT " + String.join(", ", expressions)
                + " FROM read_parquet(" + ParquetTestFiles.sql(file) + ")");
        assertRows(duckdb, read(connector(file, 64), ColumnPath.whole(same)), "DuckDB");
        assertEquals(List.of(List.of("INTEGER[]", "INTEGER[]")), ParquetTestFiles.duckdbRows(
                "SELECT typeof(la), typeof(lt) FROM read_parquet(" + ParquetTestFiles.sql(file) + ") LIMIT 1"));
    }

    @Test
    void fieldsOfStructsAreReadAloneAndOnlyTheirLeaves() throws Exception {
        List<List<Object>> written = new ArrayList<>();
        Path file = write(dir.resolve("fields.parquet"), 1500, w -> w.withRowGroupRowCountLimit(400)
                .withPageRowCountLimit(30), written);
        record Case(List<ColumnPath> paths, List<String> output, List<String> leaves) {
        }
        List<Case> cases = List.of(
                new Case(List.of(path(S, "c", "x"), path(0)), List.of("s.c.x DOUBLE", "id BIGINT NOT NULL"),
                        List.of("id", "s.c.x")),
                new Case(List.of(path(S, "a"), path(S), path(S, "c", "tags")),
                        List.of("s.a INTEGER", "s STRUCT(a INTEGER, b VARCHAR, c STRUCT(x DOUBLE, tags VARCHAR[]))",
                                "s.c.tags VARCHAR[]"),
                        List.of("s.a", "s.b", "s.c.x", "s.c.tags.list.element")),
                new Case(List.of(path(S, "c", "tags"), path(RS, "z"), path(REQ, "k"), path(S, "b")),
                        List.of("s.c.tags VARCHAR[]", "rs.z INTEGER", "req.k INTEGER", "s.b VARCHAR"),
                        List.of("s.b", "s.c.tags.list.element", "rs.z", "req.k")),
                new Case(List.of(path(S, "c"), path(S, "c", "x"), path(S, "c", "x"), path(RS, "names")),
                        List.of("s.c STRUCT(x DOUBLE, tags VARCHAR[])", "s.c.x DOUBLE", "s.c.x DOUBLE",
                                "rs.names VARCHAR[]"),
                        List.of("s.c.x", "s.c.tags.list.element", "rs.names")),
                new Case(List.of(path(REQ, "t"), path(REQ)),
                        List.of("req.t VARCHAR", "req STRUCT(k INTEGER, t VARCHAR) NOT NULL"),
                        List.of("req.k", "req.t")));
        Schema table = connector(file, 7).catalog().schema(table());
        for (Case c : cases) {
            ParquetConnector parquet = connector(file, 7);
            ScanPlan plan = parquet.plan(table(), request(c.paths()));
            assertEquals(c.output(), plan.output().fields().stream()
                    .map(f -> f.name() + " " + f.type().sqlName() + (f.nullable() ? "" : " NOT NULL")).toList(),
                    c.paths().toString());
            List<List<Object>> values = new ArrayList<>();
            List<DataType> types = new ArrayList<>();
            for (ColumnPath p : c.paths()) {
                types.add(p.field(table).type());
            }
            for (List<Object> row : written) {
                List<Object> projected = new ArrayList<>();
                for (ColumnPath p : c.paths()) {
                    projected.add(follow(row.get(p.column()), table.type(p.column()), p.fields()));
                }
                values.add(projected);
            }
            assertRows(json(values, types), read(parquet, c.paths()), c.paths().toString());
            assertTrue(parquet.reads().size() > 1);
            for (ParquetConnector.Read r : parquet.reads()) {
                assertEquals(c.leaves(), r.columns(), c.paths().toString());
            }
        }
    }

    @Test
    void aMapKeyThatIsNullIsAnErrorNamingTheColumn() throws Exception {
        Path file = ParquetTestFiles.write(dir.resolve("null-key.parquet"), """
                message m {
                  required int32 id;
                  optional group m (MAP) { repeated group key_value { optional binary key (STRING); optional int32 value; } }
                }""", CompressionCodecName.UNCOMPRESSED, w -> {
                    w.row(g -> g.append("id", 1).addGroup("m").addGroup("key_value").append("key", "a")
                            .append("value", 1));
                    w.row(g -> g.append("id", 2).addGroup("m").addGroup("key_value").append("value", 2));
                });
        ParquetConnector parquet = connector(file, 7);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> read(parquet, whole(2)));
        assertEquals("Row group 0 of the Parquet file " + file.toAbsolutePath() + ", column m: A MAP key must not"
                + " be NULL", e.getMessage());
    }

    /** Writes rows of {@link #SCHEMA} and keeps the values written, as FyrQL gives them. */
    private static Path write(Path file, int rows,
            Consumer<ExampleParquetWriter.Builder> options,
            List<List<Object>> written) throws IOException {
        SplittableRandom r = new SplittableRandom(20261016);
        return ParquetTestFiles.write(file, SCHEMA, options, w -> {
            for (long id = 0; id < rows; id++) {
                long row = id;
                w.row(g -> written.add(fill(g, r, row)));
            }
        });
    }

    /** Fills a row of {@link #SCHEMA} with random values and returns them. */
    private static List<Object> fill(Group g, SplittableRandom r, long id) {
        List<Object> row = new ArrayList<>();
        g.add("id", id);
        row.add(id);
        row.add(absent(r) ? null : struct(g.addGroup("s"), r));
        row.add(integer(g, "flat", r));
        row.add(absent(r) ? null : list(g.addGroup("l3"), r, l -> integer(l.addGroup("list"), "element", r)));
        row.add(absent(r) ? null : list(g.addGroup("l2"), r, l -> {
            int v = r.nextInt(-50, 50);
            l.add("element", v);
            return v;
        }));
        row.add(absent(r) ? null : list(g.addGroup("la"), r,
                l -> Arrays.asList(integer(l.addGroup("array"), "x", r))));
        row.add(absent(r) ? null : list(g.addGroup("lt"), r,
                l -> Arrays.asList(integer(l.addGroup("lt_tuple"), "x", r))));
        row.add(absent(r) ? null : list(g.addGroup("lp"), r, l -> {
            Group pair = l.addGroup("pair");
            int a = r.nextInt();
            pair.add("a", a);
            return Arrays.asList(a, string(pair, "b", r));
        }));
        row.add(absent(r) ? null : list(g.addGroup("ls"), r, l -> {
            Group e = l.addGroup("list");
            if (absent(r)) {
                return null;
            }
            Group element = e.addGroup("element");
            return Arrays.asList(integer(element, "p", r), absent(r) ? null
                    : list(element.addGroup("q"), r, q -> integer(q.addGroup("list"), "element", r)));
        }));
        row.add(list(g, r, x -> {
            int v = r.nextInt(1000);
            x.add("r", v);
            return v;
        }));
        if (absent(r)) {
            row.add(null);
        } else {
            Group rs = g.addGroup("rs");
            row.add(Arrays.asList(list(rs, r, names -> {
                String v = text(r);
                names.add("names", v);
                return v;
            }), integer(rs, "z", r)));
        }
        row.add(absent(r) ? null : map(g.addGroup("m"), r, (kv, i) -> {
            String key = "k" + i;
            kv.add("key", key);
            return Map.entry(key, Optional.ofNullable(absent(r) ? null
                    : Arrays.asList(integer(kv.addGroup("value"), "v", r))));
        }, "key_value"));
        row.add(absent(r) ? null : map(g.addGroup("mkv"), r, (kv, i) -> {
            kv.add("key", i * 7 - 3);
            return Map.entry(i * 7 - 3, Optional.ofNullable(string(kv, "value", r)));
        }, "map"));
        row.add(absent(r) ? null : map(g.addGroup("mm"), r, (kv, i) -> {
            kv.add("key", "m" + i);
            return Map.entry("m" + i, Optional.ofNullable(absent(r) ? null : map(kv.addGroup("value"), r,
                    (inner, j) -> {
                        inner.add("key", j);
                        return Map.entry(j, Optional.ofNullable(integer(inner, "value", r)));
                    }, "key_value")));
        }, "key_value"));
        row.add(absent(r) ? null : list(g.addGroup("ll"), r, l -> {
            Group e = l.addGroup("list");
            return absent(r) ? null : list(e.addGroup("element"), r, i -> integer(i.addGroup("list"), "element", r));
        }));
        Group req = g.addGroup("req");
        int k = r.nextInt(100);
        req.add("k", k);
        row.add(Arrays.asList(k, string(req, "t", r)));
        return row;
    }

    /** Fills the struct {@code s} and returns its values. */
    private static List<Object> struct(Group s, SplittableRandom r) {
        Integer a = integer(s, "a", r);
        String b = string(s, "b", r);
        if (absent(r)) {
            return Arrays.asList(a, b, null);
        }
        Group c = s.addGroup("c");
        Double x = real(c, "x", r);
        List<Object> tags = absent(r) ? null : list(c.addGroup("tags"), r, e -> string(e.addGroup("list"), "element", r));
        return Arrays.asList(a, b, Arrays.asList(x, tags));
    }

    private static boolean absent(SplittableRandom r) {
        return r.nextInt(7) == 0;
    }

    private static Integer integer(Group g, String field, SplittableRandom r) {
        if (absent(r)) {
            return null;
        }
        int v = r.nextInt(-1000, 1000);
        g.add(field, v);
        return v;
    }

    private static Double real(Group g, String field, SplittableRandom r) {
        if (absent(r)) {
            return null;
        }
        double v = r.nextInt(-4000, 4000) / 4.0;
        g.add(field, v);
        return v;
    }

    private static String string(Group g, String field, SplittableRandom r) {
        if (absent(r)) {
            return null;
        }
        String v = text(r);
        g.add(field, v);
        return v;
    }

    private static String text(SplittableRandom r) {
        return switch (r.nextInt(5)) {
            case 0 -> "";
            case 1 -> "\u00E4\u20AC \"q\" \uD83D\uDE00";
            default -> "v" + r.nextInt(1000);
        };
    }

    /** Adds up to three elements, each through the function, and returns them. */
    private static List<Object> list(Group g, SplittableRandom r, Function<Group, Object> element) {
        List<Object> values = new ArrayList<>();
        for (int i = r.nextInt(4); i > 0; i--) {
            values.add(element.apply(g));
        }
        return values;
    }

    /** Adds up to three entries in groups of the name, each through the function, and returns them. */
    private static Map<Object, Object> map(Group g, SplittableRandom r,
            BiFunction<Group, Integer, Map.Entry<Object, Optional<Object>>> entry, String name) {
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int i = 0, n = r.nextInt(4); i < n; i++) {
            Map.Entry<Object, Optional<Object>> e = entry.apply(g.addGroup(name), i);
            values.put(e.getKey(), e.getValue().orElse(null));
        }
        return values;
    }

    /** Returns the field a path names in a value of a type, {@code NULL} if a struct on the way is. */
    private static Object follow(Object value, DataType type, List<String> fields) {
        for (String f : fields) {
            if (value == null) {
                return null;
            }
            DataType.Struct s = (DataType.Struct) type;
            int i = IntStream.range(0, s.fields().size()).filter(j -> s.fields().get(j).name().equals(f))
                    .findFirst().orElseThrow();
            value = ((List<?>) value).get(i);
            type = s.fields().get(i).type();
        }
        return value;
    }

    private static List<List<String>> json(List<List<Object>> rows, List<DataType> types) {
        List<List<String>> json = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (int c = 0; c < row.size(); c++) {
                values.add(json(row.get(c), types.get(c)));
            }
            json.add(values);
        }
        return json;
    }

    /** Returns a value as DuckDB's {@code to_json} writes it, or {@code null} for {@code NULL}. */
    private static String json(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        append(b, value, type);
        return b.toString();
    }

    private static void append(StringBuilder b, Object value, DataType type) {
        switch (type) {
            case DataType.Struct s -> {
                b.append('{');
                List<?> values = (List<?>) value;
                for (int i = 0; i < values.size(); i++) {
                    b.append(i == 0 ? "" : ",");
                    quote(b, s.fields().get(i).name()).append(':');
                    appendOrNull(b, values.get(i), s.fields().get(i).type());
                }
                b.append('}');
            }
            case DataType.ListType l -> {
                b.append('[');
                List<?> values = (List<?>) value;
                for (int i = 0; i < values.size(); i++) {
                    b.append(i == 0 ? "" : ",");
                    appendOrNull(b, values.get(i), l.element());
                }
                b.append(']');
            }
            case DataType.MapType m -> {
                b.append('{');
                String separator = "";
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    b.append(separator);
                    quote(b, e.getKey().toString()).append(':');
                    appendOrNull(b, e.getValue(), m.value());
                    separator = ",";
                }
                b.append('}');
            }
            default -> {
                if (value instanceof String s) {
                    quote(b, s);
                } else {
                    b.append(value);
                }
            }
        }
    }

    private static void appendOrNull(StringBuilder b, Object value, DataType type) {
        if (value == null) {
            b.append("null");
        } else {
            append(b, value, type);
        }
    }

    private static StringBuilder quote(StringBuilder b, String s) {
        return b.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }

    private static ParquetConnector connector(Path file, int batchSize) {
        return ParquetConnector.builder("pq").path("t", file).batchSize(batchSize).recordReads(true).build();
    }

    private static TableRef table() {
        return TableRef.of("pq", "t");
    }

    private static ColumnPath path(int column, String... fields) {
        return new ColumnPath(column, List.of(fields));
    }

    private static List<ColumnPath> whole(int columns) {
        return ColumnPath.whole(IntStream.range(0, columns).boxed().toList());
    }

    private static ScanRequest request(List<ColumnPath> paths) {
        return new ScanRequest(paths, List.of(), OptionalLong.empty(), List.of(), Optional.empty());
    }

    /** Reads paths of the table {@code t}, each value as JSON. */
    private static List<List<String>> read(ParquetConnector c, List<ColumnPath> paths) {
        ScanPlan plan = c.plan(table(), request(paths));
        List<List<String>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            try (BatchReader reader = c.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    for (int i = 0; i < b.rowCount(); i++) {
                        List<String> row = new ArrayList<>();
                        for (int col = 0; col < b.schema().size(); col++) {
                            row.add(json(b.column(col).get(i), b.schema().type(col)));
                        }
                        rows.add(row);
                    }
                }
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
