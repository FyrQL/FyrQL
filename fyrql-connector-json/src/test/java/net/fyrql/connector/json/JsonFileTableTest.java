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
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON files read with the columns of a table, through
 * {@link JsonConnector#fileTable}, as for {@code COPY ... FROM}.
 */
class JsonFileTableTest {

    @TempDir
    Path dir;

    private final JsonConnector json = JsonConnector.builder("json").build();

    private void write(Path file, Schema schema, Batch batch, Map<String, String> options) {
        try (Sink sink = json.openFile("json", file.toString(), schema, options)) {
            sink.write(batch);
            sink.commit();
        }
    }

    /** Reads all columns of a table from each of its splits in turn. */
    private List<Object[]> read(TableRef table) {
        ScanPlan plan = json.plan(table, ScanRequest.columns(IntStream.range(0, json.catalog().schema(table).size())
                .boxed().toList()));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : json.splits(plan)) {
            for (Batch b : Batches.readAll(json.open(split))) {
                rows.addAll(b.rows());
            }
        }
        return rows;
    }

    private static Map<String, String> options(String... keysAndValues) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            options.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return options;
    }

    @Test
    void readsWhatTheWriterWritesWithTheColumnsOfTheTable() {
        Schema schema = JsonFileWriterTest.ALL_TYPES;
        Batch batch = JsonFileWriterTest.allTypes();
        String expected = Arrays.deepToString(batch.rows().toArray());
        List<Map<String, String>> variants = List.of(Map.of(), options("array", "true"),
                options("dateformat", "%d.%m.%Y", "timestampformat", "%d.%m.%Y %H:%M:%S.%f"),
                options("compression", "gzip"));
        for (Map<String, String> options : variants) {
            Path file = dir.resolve("all" + options.size() + options.hashCode() + ".json");
            write(file, schema, batch, options);
            TableRef table = json.fileTable("json", file.toString(), schema, options);
            assertEquals("json", table.source());
            assertTrue(table.table().startsWith("#"), table.table());
            assertEquals(schema, json.catalog().schema(table));
            assertEquals(expected, Arrays.deepToString(read(table).toArray()), options.toString());
        }
        assertEquals(List.of(), json.catalog().tables("main"));

        Path gz = dir.resolve("all.ndjson.gz");
        write(gz, schema, batch, Map.of());
        assertEquals(expected, Arrays.deepToString(read(json.fileTable("json", gz.toString(), schema,
                Map.of("format", "newline_delimited"))).toArray()));
    }

    @Test
    void theColumnsComeByTheirKeys() throws IOException {
        Path file = dir.resolve("k.json");
        Files.writeString(file, "{\"b\":\"2024-02-29\",\"z\":[1],\"a\":1}\n{\"a\":\"3\"}\n{}\n",
                StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.SMALLINT), new Schema.Field("b", DataType.DATE));
        assertEquals("[[1, " + Conversions.parseDate("2024-02-29") + "], [3, null], [null, null]]",
                Arrays.deepToString(read(json.fileTable("json", file.toString(), schema, Map.of())).toArray()));
        // A key is matched as it is written.
        Schema upper = Schema.of(new Schema.Field("A", DataType.SMALLINT));
        assertEquals("[[null], [null], [null]]", Arrays.deepToString(read(json.fileTable("json", file.toString(), upper,
                Map.of())).toArray()));
    }

    @Test
    void theFormatsOfDatesApplyAlsoInsideStructsAndToTheSchemaOfARegisteredFile() throws IOException {
        Path file = dir.resolve("d.json");
        Files.writeString(file, "{\"s\":{\"d\":\"29.02.2024\"},\"l\":[\"01.03.2024\"]}\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("s", DataType.struct(new DataType.Struct.Field("d",
                DataType.DATE))), new Schema.Field("l", DataType.listOf(DataType.DATE)));
        int day = Conversions.parseDate("2024-02-29");
        assertEquals("[[[" + day + "], [" + (day + 1) + "]]]", Arrays.deepToString(read(json.fileTable("json",
                file.toString(), schema, Map.of("dateformat", "%d.%m.%Y"))).toArray()));
        JsonConnector registered = JsonConnector.builder("files").dateFormat("%d.%m.%Y").schema("d", schema)
                .file("d", file).build();
        ScanPlan plan = registered.plan(TableRef.of("files", "d"), ScanRequest.columns(List.of(0, 1)));
        assertEquals("[[[" + day + "], [" + (day + 1) + "]]]", Arrays.deepToString(Batches.readAll(
                registered.open(registered.splits(plan).get(0))).get(0).rows().toArray()));
        RuntimeException e = assertThrows(RuntimeException.class, () -> read(json.fileTable("json",
                file.toString(), schema, Map.of())));
        assertTrue(e.getMessage().contains("cannot read \"29.02.2024\" as DATE"), e.getMessage());
    }

    @Test
    void ignoreErrorsMakesAValueThatDoesNotFitNull() throws IOException {
        Path file = dir.resolve("i.json");
        Files.writeString(file, "{\"a\":\"x\",\"b\":1}\n{\"a\":2,\"b\":{}}\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.BIGINT));
        RuntimeException e = assertThrows(RuntimeException.class, () -> read(json.fileTable("json",
                file.toString(), schema, Map.of())));
        assertTrue(e.getMessage().contains("cannot read \"x\" as INTEGER"), e.getMessage());
        assertEquals("[[null, 1], [2, null]]", Arrays.deepToString(read(json.fileTable("json", file.toString(), schema,
                Map.of("ignore_errors", "true"))).toArray()));
    }

    @Test
    void theSameFileWithTheSameColumnsAndOptionsGivesTheSameTable() throws IOException {
        Path file = dir.resolve("s.json");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.BIGINT));
        TableRef table = json.fileTable("json", file.toString(), schema, Map.of());
        assertEquals(table, json.fileTable("json", file.toString(), schema, Map.of()));
        assertNotEquals(table, json.fileTable("json", file.toString(), schema, Map.of("format", "auto")));
        Files.writeString(file, "{\"a\":1}\n{\"a\":2}\n", StandardCharsets.UTF_8);
        TableRef changed = json.fileTable("json", file.toString(), schema, Map.of());
        assertNotEquals(table, changed);
        assertEquals(2, read(changed).size());
    }

    @Test
    void errors() throws IOException {
        Path file = dir.resolve("e.json");
        Files.writeString(file, "{\"a\":1}\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER));
        List<Map.Entry<String, Runnable>> errors = new ArrayList<>();
        errors.add(Map.entry("The JSON source json reads no files of the format csv",
                () -> json.fileTable("csv", file.toString(), schema, Map.of())));
        errors.add(Map.entry("The JSON file " + file + " has an unknown option rows; known are format, array,"
                + " dateformat, timestampformat, ignore_errors, compression",
                () -> json.fileTable("json", file.toString(), schema, Map.of("rows", "3"))));
        errors.add(Map.entry("The option format of the JSON file " + file + " is invalid: Unknown JSON format"
                + " \"lines\"; supported are auto, array, newline_delimited",
                () -> json.fileTable("json", file.toString(), schema, Map.of("format", "lines"))));
        errors.add(Map.entry("The JSON file " + file + " takes the option format or array, not both",
                () -> json.fileTable("json", file.toString(), schema, options("format", "array", "array", "true"))));
        Path missing = dir.resolve("missing.json");
        errors.add(Map.entry("No files found that match the pattern \"" + missing + "\"",
                () -> json.fileTable("json", missing.toString(), schema, Map.of())));
        Path zst = dir.resolve("e.json.zst");
        Files.writeString(zst, "{}", StandardCharsets.UTF_8);
        errors.add(Map.entry("Files compressed with zstd are not supported: " + zst,
                () -> json.fileTable("json", zst.toString(), schema, Map.of())));
        for (Map.Entry<String, Runnable> e : errors) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, e.getValue()::run);
            assertEquals(e.getKey(), x.getMessage());
        }
        IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                () -> json.fileTable("json", file.toString(), schema, Map.of("timestampformat", "%Q")));
        assertTrue(format.getMessage().startsWith("The option timestampformat of the JSON file " + file
                + " is invalid: "), format.getMessage());
    }

    @Test
    void theFactoryNamesTheFormat() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("json")).findFirst().orElseThrow();
        assertEquals(List.of("json"), factory.fileFormats());
    }
}
