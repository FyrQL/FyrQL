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

package net.fyrql.connector.csv;

import net.fyrql.connector.Batches;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvConnectorTest {

    @TempDir
    Path dir;

    @Test
    void sniffsTypesAndReadsEdgeCases() throws IOException {
        Files.writeString(dir.resolve("orders.csv"), "\uFEFFid,name,amount,created,ok,when\r\n"
                + "1,\"Smith, John\",5.5,2026-01-02,true,2026-01-02 03:04:05\n"
                + "2,\"He said \"\"hi\"\"\",,,false,\n"
                + "3,\"line one\nline two\",-1,1970-01-01,TRUE,2026-01-02T03:04:05.5\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("tags.tsv"), "id\ttag\n1\ta,b\n2\t\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("notes.txt"), "ignored", StandardCharsets.UTF_8);
        CsvConnector c = CsvConnector.builder("csv").directory(dir).batchSize(2).build();
        assertEquals(List.of("orders", "tags"), c.catalog().tables("main"));
        Schema orders = c.catalog().schema(TableRef.of("csv", "orders"));
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.DOUBLE, DataType.DATE, DataType.BOOLEAN,
                DataType.TIMESTAMP), orders.types());
        assertEquals("name", orders.name(1));
        ScanPlan plan = c.plan(TableRef.of("csv", "orders"), ScanRequest.columns(List.of(0, 1, 2, 3, 4, 5)));
        List<Batch> batches = Batches.readAll(c.open(c.splits(plan).get(0)));
        assertEquals(2, batches.size());
        assertEquals(Arrays.asList(1L, "Smith, John", 5.5, (int) java.time.LocalDate.of(2026, 1, 2).toEpochDay(), true,
                Conversions.parseTimestamp("2026-01-02 03:04:05")), Arrays.asList(batches.get(0).row(0)));
        assertEquals(Arrays.asList(2L, "He said \"hi\"", null, null, false, null), Arrays.asList(batches.get(0).row(1)));
        assertEquals("line one\nline two", batches.get(1).row(0)[1]);
        Schema tags = c.catalog().schema(TableRef.of("csv", "tags"));
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR), tags.types());
        ScanPlan tagPlan = c.plan(TableRef.of("csv", "tags"), ScanRequest.columns(List.of(1)));
        Batch t = Batches.readAll(c.open(c.splits(tagPlan).get(0))).get(0);
        assertEquals("a,b", t.column(0).get(0));
        assertTrue(t.column(0).isNull(1));
    }

    @Test
    void theRowCountIsExactOrEstimatedFromTheSample() throws IOException {
        StringBuilder csv = new StringBuilder("n,s\n");
        for (int i = 10; i < 60; i++) {
            csv.append(i).append(",xyz\n");
        }
        Files.writeString(dir.resolve("t.csv"), csv.toString(), StandardCharsets.UTF_8);
        CsvConnector all = CsvConnector.builder("csv").file("t", dir.resolve("t.csv")).build();
        assertEquals(OptionalLong.of(50), all.catalog().rowCount(TableRef.of("csv", "t")));
        CsvConnector sampled = CsvConnector.builder("csv").sampleRows(10).file("t", dir.resolve("t.csv")).build();
        assertEquals(OptionalLong.of(50), sampled.catalog().rowCount(TableRef.of("csv", "t")));
        assertEquals(OptionalLong.of(50), CsvConnector.builder("csv").sampleRows(10).file("t", dir.resolve("t.csv"))
                .schema("t", Schema.of(new Schema.Field("n", DataType.INTEGER), new Schema.Field("s", DataType.VARCHAR)))
                .build().catalog().rowCount(TableRef.of("csv", "t")));
    }

    @Test
    void explicitSchemaAndErrors() throws IOException {
        Files.writeString(dir.resolve("t.csv"), "a,b\n1,x\n2,y\n", StandardCharsets.UTF_8);
        CsvConnector c = CsvConnector.builder("csv")
                .file("t", dir.resolve("t.csv"))
                .schema("t", Schema.of(new Schema.Field("num", DataType.INTEGER), new Schema.Field("s", DataType.VARCHAR)))
                .build();
        Schema s = c.catalog().schema(TableRef.of("csv", "t"));
        assertEquals("num", s.name(0));
        ScanPlan plan = c.plan(TableRef.of("csv", "t"), ScanRequest.columns(List.of(0)));
        assertEquals(2, Batches.readAll(c.open(c.splits(plan).get(0))).get(0).column(0).get(1));

        Files.writeString(dir.resolve("bad.csv"), "a\n1\nx\n", StandardCharsets.UTF_8);
        CsvConnector bad = CsvConnector.builder("csv").file("bad", dir.resolve("bad.csv"))
                .schema("bad", Schema.of(DataType.INTEGER)).build();
        ScanPlan badPlan = bad.plan(TableRef.of("csv", "bad"), ScanRequest.columns(List.of(0)));
        ConversionException e = assertThrows(ConversionException.class,
                () -> Batches.readAll(bad.open(bad.splits(badPlan).get(0))));
        assertTrue(e.getMessage().startsWith("Line 3 of"), e.getMessage());

        Files.writeString(dir.resolve("short.csv"), "a,b\n1\n", StandardCharsets.UTF_8);
        CsvConnector shortRows = CsvConnector.builder("csv").file("short", dir.resolve("short.csv")).build();
        ScanPlan shortPlan = shortRows.plan(TableRef.of("csv", "short"), ScanRequest.columns(List.of(0)));
        assertThrows(ConversionException.class, () -> Batches.readAll(shortRows.open(shortRows.splits(shortPlan).get(0))));
    }

    @Test
    void readsEachTypeFromTheCharactersAsFromStrings() throws IOException {
        Schema schema = Schema.of(new Schema.Field("i", DataType.INTEGER), new Schema.Field("b", DataType.BIGINT),
                new Schema.Field("d", DataType.decimal(15, 2)), new Schema.Field("dt", DataType.DATE),
                new Schema.Field("ok", DataType.BOOLEAN), new Schema.Field("s", DataType.VARCHAR),
                new Schema.Field("x", DataType.DOUBLE), new Schema.Field("t", DataType.TINYINT),
                new Schema.Field("h", DataType.HUGEINT), new Schema.Field("w", DataType.decimal(38, 4)));
        List<String[]> rows = List.of(
                new String[] {"1", "-9", "1234.56", "1994-01-01", "true", "plain", "1.5", "-128", "1", "0.5"},
                new String[] {" 2", "+10", "1.005", "1994-1-2", "YES", "caf\u00E9", "1e3", "127", "-1", "1e2"},
                new String[] {"NA", "NA", "NA", "NA", "NA", "NA", "NA", "NA", "NA", "NA"},
                new String[] {"0x10", "1_000", "-.5", "epoch", "0", "", "nan", "0", "170141183460469231731687303715884105727",
                    "-12345678901234567890123456789012.1234"},
                new String[] {"1.5", "9223372036854775807", "17", "0001-01-01 (BC)", "f", "\"quoted, \"\"x\"\"\"", "-0.0",
                    "1", "0", "1"});
        StringBuilder csv = new StringBuilder("i,b,d,dt,ok,s,x,t,h,w\n");
        for (String[] row : rows) {
            csv.append(String.join(",", row)).append('\n');
        }
        Files.writeString(dir.resolve("all.csv"), csv.toString(), StandardCharsets.UTF_8);
        CsvConnector c = CsvConnector.builder("csv").nullString("NA").batchSize(2).file("all", dir.resolve("all.csv"))
                .schema("all", schema).build();
        List<Integer> reversed = List.of(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
        for (List<Integer> columns : List.of(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), reversed, List.of(5, 2))) {
            ScanPlan plan = c.plan(TableRef.of("csv", "all"), ScanRequest.columns(columns));
            List<Batch> batches = Batches.readAll(c.open(c.splits(plan).get(0)));
            assertEquals(3, batches.size());
            List<List<Object>> actual = new ArrayList<>();
            for (Batch b : batches) {
                for (int r = 0; r < b.rowCount(); r++) {
                    actual.add(Arrays.asList(b.row(r)));
                }
            }
            List<List<Object>> expected = new ArrayList<>();
            for (String[] row : rows) {
                List<Object> values = new ArrayList<>();
                for (int column : columns) {
                    String field = row[column].startsWith("\"") ? "quoted, \"x\"" : row[column];
                    values.add(CsvConnector.parse(field, schema.type(column), "NA"));
                }
                expected.add(values);
            }
            assertEquals(expected, actual, columns.toString());
        }
    }

    @Test
    void anErrorNamesTheLineAndTheFirstRequestedColumnThatFails() throws IOException {
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.DATE),
                new Schema.Field("c", DataType.VARCHAR));
        Files.writeString(dir.resolve("e.csv"), "\na,b,c\n1,1994-01-01,\"two\nlines\"\nx,1994-02-30,z\n",
                StandardCharsets.UTF_8);
        CsvConnector c = CsvConnector.builder("csv").header(true).file("e", dir.resolve("e.csv")).schema("e", schema)
                .build();
        ScanPlan inOrder = c.plan(TableRef.of("csv", "e"), ScanRequest.columns(List.of(0, 1, 2)));
        ConversionException e = assertThrows(ConversionException.class,
                () -> Batches.readAll(c.open(c.splits(inOrder).get(0))));
        assertEquals("Line 5 of " + dir.resolve("e.csv") + ", column a: Could not convert string 'x' to an integer",
                e.getMessage());
        ScanPlan backwards = c.plan(TableRef.of("csv", "e"), ScanRequest.columns(List.of(2, 1, 0)));
        e = assertThrows(ConversionException.class, () -> Batches.readAll(c.open(c.splits(backwards).get(0))));
        assertEquals("Line 5 of " + dir.resolve("e.csv") + ", column b: Could not convert string '1994-02-30' to a"
                + " DATE", e.getMessage());
        ScanPlan text = c.plan(TableRef.of("csv", "e"), ScanRequest.columns(List.of(2)));
        assertEquals(2, Batches.readAll(c.open(c.splits(text).get(0))).get(0).rowCount());

        // A wrong number of fields comes before a field that does not parse.
        Files.writeString(dir.resolve("f.csv"), "a,b,c\nx,1994-01-01\n", StandardCharsets.UTF_8);
        CsvConnector f = CsvConnector.builder("csv").file("f", dir.resolve("f.csv")).schema("f", schema).build();
        ScanPlan plan = f.plan(TableRef.of("csv", "f"), ScanRequest.columns(List.of(0)));
        e = assertThrows(ConversionException.class, () -> Batches.readAll(f.open(f.splits(plan).get(0))));
        assertEquals("Line 2 of " + dir.resolve("f.csv") + " has 2 fields, the header 3", e.getMessage());
    }

    /** The columns DuckDB 1.5.6 gives each file in DESCRIBE SELECT * FROM read_csv(file). */
    @Test
    void detectsTheHeaderAsDuckDb() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("x,y\na,b\n", "x VARCHAR, y VARCHAR");
        files.put("x,1\na,2\n", "column0 VARCHAR, column1 BIGINT");
        files.put("1,x\n2,y\n", "column0 BIGINT, column1 VARCHAR");
        files.put("a,b\n1,\n,2\n", "a BIGINT, b BIGINT");
        files.put("id,v\n1,2\n", "id BIGINT, v BIGINT");
        files.put("1,2\n3,4\n", "column0 BIGINT, column1 BIGINT");
        files.put("true,1\nfalse,2\n", "column0 BOOLEAN, column1 BIGINT");
        files.put("2024-01-01,x\n2024-01-02,y\n", "column0 DATE, column1 VARCHAR");
        files.put("x\n", "x VARCHAR");
        files.put("1.5,a\n2,b\n", "1.5 BIGINT, a VARCHAR");
        files.put("a,,c\n1,2,3\n", "a BIGINT, column1 BIGINT, c BIGINT");
        files.put("a,a,b\n1,2,3\n", "a BIGINT, a_1 BIGINT, b BIGINT");
        files.put("A B,c-d\n1,2\n", "A B BIGINT, c-d BIGINT");
        files.put("NULL,b\n1,2\n", "NULL BIGINT, b BIGINT");
        CsvConnector.Builder builder = CsvConnector.builder("csv");
        int i = 0;
        for (String content : files.keySet()) {
            Path file = dir.resolve("h" + i + ".csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            builder.file("h" + i++, file);
        }
        CsvConnector c = builder.build();
        i = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            assertEquals(e.getValue(), describe(c.catalog().schema(TableRef.of("csv", "h" + i++))), e.getKey());
        }
        Files.writeString(dir.resolve("wide.csv"), "1,2,3,4,5,6,7,8,9,10,11\n", StandardCharsets.UTF_8);
        CsvConnector wide = CsvConnector.builder("csv").header(false).file("wide", dir.resolve("wide.csv")).build();
        Schema s = wide.catalog().schema(TableRef.of("csv", "wide"));
        assertEquals("column00", s.name(0));
        assertEquals("column10", s.name(10));
    }

    private static String describe(Schema schema) {
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            columns.add(schema.name(c) + " " + schema.type(c).sqlName());
        }
        return String.join(", ", columns);
    }

    @Test
    void readCsvBindsFilesToTables() throws IOException {
        Path semi = dir.resolve("semi.csv");
        Files.writeString(semi, "a;b\n1;x\n2;NA\n", StandardCharsets.UTF_8);
        CsvConnector c = CsvConnector.builder("files").build();
        assertEquals(List.of("read_csv", "read_csv_auto"), c.tableFunctions());
        TableRef t = c.bind("read_csv", List.of(semi.toString()), Map.of("delim", ";", "nullstr", "NA"));
        assertEquals("files", t.source());
        assertEquals("main", t.schema());
        assertTrue(t.table().startsWith("#"), t.table());
        assertEquals(List.of(), c.catalog().tables("main"));
        assertEquals("a BIGINT, b VARCHAR", describe(c.catalog().schema(t)));
        ScanPlan plan = c.plan(t, ScanRequest.columns(List.of(0, 1)));
        List<Object[]> rows = Batches.readAll(c.open(c.splits(plan).get(0))).get(0).rows();
        assertEquals(Arrays.asList(1L, "x"), Arrays.asList(rows.get(0)));
        assertEquals(Arrays.asList(2L, null), Arrays.asList(rows.get(1)));
        // The same call gives the same table, another call another one.
        assertEquals(t, c.bind("read_csv_auto", List.of(semi.toString()), Map.of("sep", ";", "nullstr", "NA")));
        TableRef raw = c.bind("read_csv", List.of(semi.toString()), Map.of("header", "false"));
        assertEquals("column0 VARCHAR", describe(c.catalog().schema(raw)));
        // A changed file is sniffed again.
        Files.writeString(semi, "a;b;c\n1;x;2024-01-01\n", StandardCharsets.UTF_8);
        TableRef changed = c.bind("read_csv", List.of(semi.toString()), Map.of("delim", ";", "nullstr", "NA"));
        assertEquals("a BIGINT, b VARCHAR, c DATE", describe(c.catalog().schema(changed)));

        Path tsv = dir.resolve("t.tsv");
        Files.writeString(tsv, "a\tb\n1\tx\n", StandardCharsets.UTF_8);
        assertEquals("a BIGINT, b VARCHAR", describe(c.catalog().schema(c.bind("read_csv", List.of(tsv.toString()),
                Map.of()))));
        assertEquals("a BIGINT, b VARCHAR", describe(c.catalog().schema(c.bind("read_csv", List.of(tsv.toString()),
                Map.of("delim", "\\t")))));

        List<Map.Entry<String, Runnable>> errors = new ArrayList<>();
        errors.add(Map.entry("read_csv takes the path of one file as its argument",
                () -> c.bind("read_csv", List.of(), Map.of())));
        errors.add(Map.entry("read_csv takes the path of one file as its argument",
                () -> c.bind("read_csv", List.of(1L), Map.of())));
        errors.add(Map.entry("No files found that match the pattern \"nothere.csv\"",
                () -> c.bind("read_csv", List.of("nothere.csv"), Map.of())));
        errors.add(Map.entry("Patterns of file names are not supported yet: *.csv",
                () -> c.bind("read_csv", List.of("*.csv"), Map.of())));
        errors.add(Map.entry("Invalid named parameter \"foo\" for function read_csv; supported are header, delim, sep, quote,"
                + " nullstr", () -> c.bind("read_csv", List.of(tsv.toString()), Map.of("foo", 1L))));
        errors.add(Map.entry("The argument delim of read_csv must be one character",
                () -> c.bind("read_csv", List.of(tsv.toString()), Map.of("delim", ";;"))));
        errors.add(Map.entry("The argument header of read_csv must be a BOOLEAN",
                () -> c.bind("read_csv", List.of(tsv.toString()), Map.of("header", "maybe"))));
        for (Map.Entry<String, Runnable> e : errors) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, e.getValue()::run);
            assertEquals(e.getKey(), x.getMessage());
        }
    }

    @Test
    void theFactoryCreatesASourceFromOptions() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/a.csv"), "x;y\n1;n/a\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.txt"), "1;2\n3;4\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("c.txt"), "5;6\n7;8\n", StandardCharsets.UTF_8);
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("csv")).findFirst().orElseThrow();
        CsvConnector c = (CsvConnector) factory.create("files", Map.of("directory", "data", "tables.b", "b.txt",
                "tables.c.file", "c.txt", "delimiter", ";", "nullString", "n/a", "sampleRows", "10"), dir);
        assertEquals(List.of("a", "b", "c"), c.catalog().tables("main"));
        assertEquals(List.of(Arrays.asList(1L, null)), rows(c, "a"));
        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L)), rows(c, "b"));
        assertEquals(List.of(List.of(5L, 6L), List.of(7L, 8L)), rows(c, "c"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("files", Map.of("delimter", ";"), dir));
        assertTrue(e.getMessage().startsWith("The CSV source files has an unknown option delimter; known are"),
                e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> factory.create("files", Map.of("delimiter", ";;"), dir));
        assertEquals("The option delimiter of the CSV source files must be one character: ;;", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("files", Map.of("tables.b.url", "b.txt"), dir));
        assertEquals("The table b of the CSV source files has an unknown attribute url; known are file",
                e.getMessage());
    }

    private static List<List<Object>> rows(CsvConnector c, String table) {
        int width = c.catalog().schema(TableRef.of(c.name(), table)).size();
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            columns.add(i);
        }
        ScanPlan plan = c.plan(TableRef.of(c.name(), table), ScanRequest.columns(columns));
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }
}
