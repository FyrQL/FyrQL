/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.json;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads JSON files through the connector; JSON is written with single quotes for readability. */
class JsonConnectorTest {

    @TempDir
    Path dir;

    private Path file(String name, String json) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, json.replace('\'', '"'), StandardCharsets.UTF_8);
        return p;
    }

    /** Reads the columns of a table, all rows. */
    private static List<List<Object>> read(JsonConnector c, String table, Integer... columns) {
        ScanPlan plan = c.plan(TableRef.of(c.name(), table), ScanRequest.columns(List.of(columns)));
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }

    private static int day(String date) {
        return Conversions.parseDate(date);
    }

    private static long micros(String timestamp) {
        return Conversions.parseTimestamp(timestamp);
    }

    @Test
    void readsTheFilesOfADirectoryWithNestedValuesInBatches() throws IOException {
        file("orders.json", """
                [
                  {'id': 1, 'customer': {'name': 'Ann', 'tags': ['a', 'b']}, 'items': [{'sku': 'x', 'qty': 2}],
                   'day': '2024-01-02', 'at': '2024-01-02T03:04:05+02:00', 'price': 1.5, 'big': 9223372036854775808,
                   'id2': '123e4567-e89b-12d3-a456-426614174000', 'any': 1, 'ok': true},
                  {'id': 2, 'customer': null, 'items': [], 'day': null, 'at': '2024-01-02T00:00:00-01:00',
                   'price': 2, 'big': 1, 'any': 'x', 'ok': null},
                  {'id': 3, 'customer': {'name': null}, 'day': '2024-12-31', 'any': {'k': [1, 2.5]}, 'ok': false}
                ]""");
        file("events.jsonl", "{'n': 1}\n{'n': 2}\n");
        file("notes.txt", "ignored");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve("packed.ndjson.gz")))) {
            out.write("{'z': 'zipped'}\n".replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        }
        JsonConnector c = JsonConnector.builder("js").directory(dir).batchSize(2).build();
        assertEquals(List.of("events", "orders", "packed"), c.catalog().tables("main"));
        Schema orders = c.catalog().schema(TableRef.of("js", "orders"));
        assertEquals("id BIGINT, customer STRUCT(\"name\" VARCHAR, tags VARCHAR[]), items STRUCT(sku VARCHAR, qty BIGINT)[], "
                + "day DATE, at TIMESTAMP, price DOUBLE, big HUGEINT, id2 UUID, any VARCHAR, ok BOOLEAN",
                JsonSchemaInferenceTest.describe(orders));
        List<List<Object>> rows = read(c, "orders", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertEquals(List.of(1L, List.of("Ann", List.of("a", "b")), List.of(List.of("x", 2L)), day("2024-01-02"),
                micros("2024-01-02 01:04:05"), 1.5, new BigInteger("9223372036854775808"),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "1", true), rows.get(0));
        assertEquals(Arrays.asList(2L, null, List.of(), null, micros("2024-01-02 01:00:00"), 2.0, BigInteger.ONE, null,
                "\"x\"", null), rows.get(1));
        assertEquals(Arrays.asList(3L, Arrays.asList(null, null), null, day("2024-12-31"), null, null, null, null,
                "{\"k\":[1,2.5]}", false), rows.get(2));
        assertEquals(List.of(List.of(1L), List.of(2L)), read(c, "events", 0));
        assertEquals(List.of(List.of("zipped")), read(c, "packed", 0));
        assertEquals(List.of(List.of(day("2024-12-31"), 3L)), read(c, "orders", 3, 0).subList(2, 3));
    }

    @Test
    void theValuesOfColumnsThatAreNotReadAreSkippedUnread() throws IOException {
        file("t.json", "[{'a': 1, 'b': 1}, {'a': 2, 'b': 'not a number'}, {'a': 3, 'b': {'deep': [1, {'x': 2}]}}]");
        JsonConnector c = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1).build();
        assertEquals("a BIGINT, b BIGINT", JsonSchemaInferenceTest.describe(c.catalog().schema(TableRef.of("js", "t"))));
        assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), read(c, "t", 0));
        assertEquals(3, read(c, "t").size());
        ConversionException e = assertThrows(ConversionException.class, () -> read(c, "t", 1));
        assertEquals("Record 2 of " + dir.resolve("t.json") + ", column b: cannot read \"not a number\" as BIGINT: "
                + "Could not convert string 'not a number' to an integer; the schema was inferred from the first 1 records, "
                + "sample_size = -1 infers it from all of them", e.getMessage());
    }

    @Test
    void ofAStructOnlyTheFieldsAskedForAreReadAndTheOthersAreSkippedUnread() throws IOException {
        file("t.json", "[{'id': 1, 's': {'a': 'x', 'b': 1, 'c': {'d': 1, 'e': 2}}},"
                + " {'id': 2, 's': {'a': 'y', 'b': 'not a number', 'c': {'d': [1], 'e': 3}}},"
                + " {'id': 3, 's': null}, {'id': 4, 's': {'b': 2}}]");
        JsonConnector c = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1).build();
        TableRef t = TableRef.of("js", "t");
        assertEquals("id BIGINT, s STRUCT(a VARCHAR, b BIGINT, c STRUCT(d BIGINT, e BIGINT))",
                JsonSchemaInferenceTest.describe(c.catalog().schema(t)));
        ScanRequest fields = new ScanRequest(List.of(new ColumnPath(1, List.of("c", "e")), ColumnPath.whole(0),
                new ColumnPath(1, List.of("a"))), List.of(), OptionalLong.empty(), List.of(), Optional.empty());
        ScanPlan plan = c.plan(t, fields);
        assertEquals(fields.columns(), plan.columns(), "the connector reads fields");
        assertEquals("[s.c.e BIGINT, id BIGINT, s.a VARCHAR]", plan.output().toString());
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            assertEquals(plan.output(), b.schema());
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        // The values of b and d that do not fit are never read.
        assertEquals(List.of(Arrays.asList(2L, 1L, "x"), Arrays.asList(3L, 2L, "y"), Arrays.asList(null, 3L, null),
                Arrays.asList(null, 4L, null)), rows);
        assertTrue(assertThrows(ConversionException.class, () -> read(c, "t", 1)).getMessage().contains(
                "column s: cannot read \"not a number\" as BIGINT"));

        // The keys of the struct are checked all the same.
        file("keys.json", "[{'s': {'a': 'x', 'b': 1}}, {'s': {'a': 'y', 'q': 1}}]");
        JsonConnector keys = JsonConnector.builder("js").file("t", dir.resolve("keys.json")).sampleSize(1).build();
        ScanPlan a = keys.plan(t, new ScanRequest(List.of(new ColumnPath(0, List.of("a"))), List.of(),
                OptionalLong.empty(), List.of(), Optional.empty()));
        assertTrue(assertThrows(ConversionException.class, () -> Batches.readAll(keys.open(keys.splits(a).get(0))))
                .getMessage().contains("column s: the object has the key \"q\", which is not a field of"));
    }

    @Test
    void outsideTheSampleAValueIsCastToTheTypeOfItsColumnAsInDuckDb() throws IOException {
        file("t.json", "[{'i': 1, 'd': 1.5, 's': 'x', 'b': true}, {'i': 2.5, 'd': 2, 's': 2, 'b': 1},"
                + " {'i': -2.5, 'd': '3', 's': true, 'b': 'false'}, {'i': 2.7, 'd': true, 's': {'q': [1, 'x']},"
                + " 'b': 0}, {'i': '7', 'd': 1e400, 's': 1e2, 'b': 0.0}]");
        JsonConnector c = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1).build();
        assertEquals(List.of(List.of(1L, 1.5, "x", true), List.of(2L, 2.0, "2", true), List.of(-2L, 3.0, "true", false),
                List.of(3L, 1.0, "{\"q\":[1,\"x\"]}", false), List.of(7L, Double.POSITIVE_INFINITY, "100.0", false)),
                read(c, "t", 0, 1, 2, 3));
    }

    @Test
    void anUnknownKeyOrAKeyTwiceIsAnErrorUnlessErrorsAreIgnored() throws IOException {
        file("t.json", "[{'a': 1, 's': {'x': 1}}, {'a': 2, 'b': 2}, {'a': 3, 'a': 4}, {'a': 5, 's': {'y': 1}}, 7,"
                + " {'a': 'x', 's': {'x': 'y'}}, {'a': [1], 's': 5}]");
        JsonConnector strict = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1).build();
        String file = dir.resolve("t.json").toString();
        assertEquals("Record 2 of " + file + ": the object has the key \"b\", which is not a column; the schema was "
                + "inferred from the first 1 records, sample_size = -1 infers it from all of them",
                assertThrows(ConversionException.class, () -> read(strict, "t", 0)).getMessage());
        JsonConnector lenient = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1)
                .ignoreErrors(true).build();
        assertEquals(Arrays.asList(Arrays.asList(1L, List.of(1L)), Arrays.asList(2L, null), Arrays.asList(3L, null),
                Arrays.asList(5L, Arrays.asList((Object) null)), Arrays.asList(null, null),
                Arrays.asList(null, Arrays.asList((Object) null)), Arrays.asList(null, null)), read(lenient, "t", 0, 1));
        file("twice.json", "[{'a': 1}, {'a': 3, 'a': 4}]");
        JsonConnector twice = JsonConnector.builder("js").file("t", dir.resolve("twice.json")).build();
        assertEquals("Record 2 of " + dir.resolve("twice.json") + ": the object has the key \"a\" twice",
                assertThrows(ConversionException.class, () -> read(twice, "t", 0)).getMessage());
        file("nested.json", "[{'s': {'x': 1}}, {'s': {'x': 2, 'y': 3}}]");
        JsonConnector nested = JsonConnector.builder("js").file("t", dir.resolve("nested.json")).sampleSize(1).build();
        assertTrue(assertThrows(ConversionException.class, () -> read(nested, "t", 0)).getMessage().startsWith(
                "Record 2 of " + dir.resolve("nested.json") + ", column s: the object has the key \"y\", which is not "
                        + "a field of STRUCT(x BIGINT)"));
        file("shape.json", "[{'s': {'x': 1}}, {'s': 5}]");
        JsonConnector shape = JsonConnector.builder("js").file("t", dir.resolve("shape.json")).sampleSize(1).build();
        assertTrue(assertThrows(ConversionException.class, () -> read(shape, "t", 0)).getMessage().startsWith(
                "Record 2 of " + dir.resolve("shape.json") + ", column s: expected STRUCT(x BIGINT), but got an "
                        + "INTEGER"));
    }

    @Test
    void aStringOutsideTheSampleMustHaveTheFormOfItsColumn() throws IOException {
        file("t.json", "[{'d': '2020-01-02', 't': '2020-01-02T03:04:05Z'}, {'d': '2020-01-02 03:04:05', 't': null}]");
        JsonConnector c = JsonConnector.builder("js").file("t", dir.resolve("t.json")).sampleSize(1).build();
        assertEquals(List.of(DataType.DATE, DataType.TIMESTAMP), c.catalog().schema(TableRef.of("js", "t")).types());
        assertEquals(Arrays.asList(Arrays.asList(micros("2020-01-02 03:04:05")), Arrays.asList((Object) null)),
                read(c, "t", 1));
        assertTrue(assertThrows(ConversionException.class, () -> read(c, "t", 0)).getMessage().contains(
                "column d: cannot read \"2020-01-02 03:04:05\" as DATE in the form %Y-%m-%d"));
    }

    @Test
    void aGivenSchemaCastsValuesAndSkipsOtherKeys() throws IOException {
        file("t.json", "{'id': '3', 'amount': 12.345, 'day': '2024-02-29', 'note': 2.5, 'tags': {'a': 1}, 'other': 1}\n"
                + "{'id': 4, 'amount': '1.5', 'tags': {'b': '2'}, 'skipped': {'x': [1]}}\n");
        Schema schema = new Schema(List.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("amount", DataType.decimal(10, 2)), new Schema.Field("day", DataType.DATE),
                new Schema.Field("note", DataType.VARCHAR),
                new Schema.Field("tags", DataType.mapOf(DataType.VARCHAR, DataType.SMALLINT))));
        JsonConnector c = JsonConnector.builder("js").file("t", dir.resolve("t.json")).schema("t", schema).build();
        assertEquals(schema, c.catalog().schema(TableRef.of("js", "t")));
        Map<Object, Object> a = new LinkedHashMap<>();
        a.put("a", (short) 1);
        Map<Object, Object> b = new LinkedHashMap<>();
        b.put("b", (short) 2);
        assertEquals(List.of(Arrays.asList(3, new BigDecimal("12.35"), day("2024-02-29"), "2.5", a),
                Arrays.asList(4, new BigDecimal("1.50"), null, null, b)), read(c, "t", 0, 1, 2, 3, 4));
    }

    @Test
    void readJsonBindsATableOfTheCallAndReadsItAgainWhenTheFileChanges() throws IOException {
        Path p = file("data.json", "[{'a': 1}, {'b': 'x'}]");
        String path = p.toString();
        JsonConnector c = JsonConnector.builder("js").build();
        assertEquals(List.of("read_json", "read_json_auto", "read_ndjson", "read_ndjson_auto"), c.tableFunctions());
        TableRef t = c.bind("read_json", List.of(path), Map.of());
        assertEquals("#1", t.table());
        assertEquals(List.of(), c.catalog().tables("main"));
        assertEquals("a BIGINT, b VARCHAR", JsonSchemaInferenceTest.describe(c.catalog().schema(t)));
        assertEquals(t, c.bind("read_json_auto", List.of(path), Map.of()));
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("sample_size", 1);
        named.put("ignore_errors", "true");
        named.put("format", "ARRAY");
        named.put("map_inference_threshold", new BigDecimal("-1"));
        named.put("field_appearance_threshold", new BigDecimal("0.5"));
        named.put("dateformat", "%d.%m.%Y");
        named.put("timestamp_format", "%d.%m.%Y %H:%M");
        TableRef sampled = c.bind("read_json", List.of(path), named);
        assertNotEquals(t, sampled);
        assertEquals("a BIGINT", JsonSchemaInferenceTest.describe(c.catalog().schema(sampled)));
        assertEquals(List.of(Arrays.asList(1L), Arrays.asList((Object) null)), read(c, sampled.table(), 0));
        Files.writeString(p, "[{\"c\": true}]");
        Files.setLastModifiedTime(p, FileTime.fromMillis(Files.getLastModifiedTime(p).toMillis() + 2000));
        TableRef changed = c.bind("read_json", List.of(path), Map.of());
        assertNotEquals(t, changed);
        assertEquals("c BOOLEAN", JsonSchemaInferenceTest.describe(c.catalog().schema(changed)));
        Path lines = file("lines.txt", "[1, 2]\n[3]");
        assertEquals("json BIGINT[]", JsonSchemaInferenceTest.describe(c.catalog().schema(
                c.bind("read_ndjson", List.of(lines.toString()), Map.of()))));
        assertEquals("Invalid named parameter \"header\" for function read_json; supported are format, sample_size, "
                + "ignore_errors, map_inference_threshold, field_appearance_threshold, dateformat, timestampformat",
                assertThrows(IllegalArgumentException.class,
                        () -> c.bind("read_json", List.of(path), Map.of("header", true))).getMessage());
        assertEquals("The argument sample_size of read_json must be an INTEGER", assertThrows(
                IllegalArgumentException.class,
                () -> c.bind("read_json", List.of(path), Map.of("sample_size", new BigDecimal("1.5")))).getMessage());
        assertEquals("Unknown JSON format \"csv\"; supported are auto, array, newline_delimited", assertThrows(
                IllegalArgumentException.class,
                () -> c.bind("read_json", List.of(path), Map.of("format", "csv"))).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> c.bind("read_json",
                List.of(dir.resolve("no.json").toString()), Map.of())).getMessage().startsWith(
                        "No files found that match the pattern"));
        assertEquals("No table js.main.nope in the JSON source js", assertThrows(IllegalArgumentException.class,
                () -> c.catalog().schema(TableRef.of("js", "nope"))).getMessage());
    }

    @Test
    void theRowCountIsExactOrEstimatedFromTheSample() throws IOException {
        StringBuilder lines = new StringBuilder();
        for (int i = 10; i < 60; i++) {
            lines.append("{'x': ").append(i).append(", 'y': 'ab'}\n");
        }
        Path file = file("t.jsonl", lines.toString());
        JsonConnector all = JsonConnector.builder("js").sampleSize(-1).file("t", file).build();
        assertEquals(OptionalLong.of(50), all.catalog().rowCount(TableRef.of("js", "t")));
        JsonConnector sampled = JsonConnector.builder("js").sampleSize(10).file("t", file).build();
        assertEquals(OptionalLong.of(50), sampled.catalog().rowCount(TableRef.of("js", "t")));
        JsonConnector given = JsonConnector.builder("js").sampleSize(10).file("t", file)
                .schema("t", Schema.of(new Schema.Field("x", DataType.INTEGER))).build();
        assertEquals(OptionalLong.of(50), given.catalog().rowCount(TableRef.of("js", "t")));
        Path gz = dir.resolve("t.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(lines.toString().replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        }
        JsonConnector zipped = JsonConnector.builder("js").sampleSize(10).file("z", gz).build();
        assertEquals(OptionalLong.empty(), zipped.catalog().rowCount(TableRef.of("js", "z")));
    }

    @Test
    void jsonTextIsWrittenAsDuckDbWritesItsTypeJson() {
        assertEquals("100000000000000000000.0", JsonValues.formatDouble(1e20));
        assertEquals("1e21", JsonValues.formatDouble(1e21));
        assertEquals("1.5e-7", JsonValues.formatDouble(1.5e-7));
        assertEquals("0.000001", JsonValues.formatDouble(1e-6));
        assertEquals("123456789.0", JsonValues.formatDouble(123456789.0));
        assertEquals("-0.0", JsonValues.formatDouble(-0.0));
        assertEquals("0.1", JsonValues.formatDouble(0.1));
        assertEquals("-12345678.5", JsonValues.formatDouble(-12345678.5));
        StringBuilder b = new StringBuilder();
        JsonValues.string("x\"y\\ a/b\t\u0001\u00E4", b);
        assertEquals("\"x\\\"y\\\\ a/b\\t\\u0001\u00E4\"", b.toString());
    }

    @Test
    void theFactoryCreatesASourceFromOptions() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/a.json"), "[{\"x\": 1}, {\"x\": 2}]", StandardCharsets.UTF_8);
        file("lines.txt", "{'y': 'p'}\n{'y': 'q'}\n");
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("json")).findFirst().orElseThrow();
        JsonConnector c = (JsonConnector) factory.create("docs", Map.of("directory", "data", "tables.b", "lines.txt",
                "sampleSize", "-1", "ignoreErrors", "true", "batchSize", "1"), dir);
        assertEquals(List.of("a", "b"), c.catalog().tables("main"));
        assertEquals(List.of(List.of(1L), List.of(2L)), read(c, "a", 0));
        assertEquals(List.of(List.of("p"), List.of("q")), read(c, "b", 0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("docs", Map.of("format", "xml"), dir));
        assertTrue(e.getMessage().startsWith("The option format of the JSON source docs is invalid: Unknown JSON"
                + " format \"xml\""), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("docs", Map.of("ignoreErrors", "maybe"), dir));
        assertEquals("The option ignoreErrors of the JSON source docs must be true, false, on or off: maybe", e.getMessage());
    }
}
