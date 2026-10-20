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

package net.fyrql.connector.csv;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
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
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV files read with the columns of a table, through {@link CsvConnector#fileTable}, as for {@code COPY ... FROM}. */
class CsvFileTableTest {

    /** The columns of {@link CsvFileWriterTest#ALL_TYPES} that a CSV file holds, all but the nested ones. */
    private static final Schema SCALARS = new Schema(CsvFileWriterTest.ALL_TYPES.fields().subList(0, 11));

    @TempDir
    Path dir;

    private final CsvConnector csv = CsvConnector.builder("csv").build();

    /**
     * The rows that a file of {@link #scalarRows()} reads back as: with an
     * empty null string, the default, an empty string or {@code BLOB} is
     * {@code NULL}, as DuckDB reads it by default.
     */
    private static String expected(String nullString) {
        List<Object[]> rows = scalarRows();
        for (Object[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                if (nullString.isEmpty() && ("".equals(row[c]) || row[c] instanceof byte[] b && b.length == 0)) {
                    row[c] = null;
                }
            }
        }
        return Arrays.deepToString(rows.toArray());
    }

    /** The rows of {@link CsvFileWriterTest#allTypes()} without the nested columns. */
    private static List<Object[]> scalarRows() {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : CsvFileWriterTest.allTypes().rows()) {
            rows.add(Arrays.copyOf(row, SCALARS.size()));
        }
        return rows;
    }

    private void write(Path file, Map<String, String> options) {
        try (Sink sink = csv.openFile("csv", file, SCALARS, options)) {
            sink.write(Batch.ofRows(SCALARS, scalarRows()));
            sink.commit();
        }
    }

    /** Reads all columns of a table from each of its splits in turn. */
    private List<Object[]> read(TableRef table) {
        ScanPlan plan = csv.plan(table, ScanRequest.columns(IntStream.range(0, csv.catalog().schema(table).size())
                .boxed().toList()));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : csv.splits(plan)) {
            for (Batch b : Batches.readAll(csv.open(split))) {
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
        List<Map<String, String>> variants = List.of(Map.of(),
                options("delimiter", ";", "quote", "'", "nullstr", "NA", "header", "false"),
                options("dateformat", "%d.%m.%Y", "timestampformat", "%d.%m.%Y %H:%M:%S.%f", "escape", "\""),
                options("compression", "gzip", "sep", "|"));
        for (Map<String, String> options : variants) {
            Path file = dir.resolve("all" + options.size() + options.hashCode() + ".csv");
            write(file, options);
            TableRef table = csv.fileTable("csv", file, SCALARS, options);
            assertEquals("csv", table.source());
            assertTrue(table.table().startsWith("#"), table.table());
            assertEquals(SCALARS, csv.catalog().schema(table));
            assertEquals(expected(options.getOrDefault("nullstr", "")), Arrays.deepToString(read(table).toArray()),
                    options.toString());
        }
        assertEquals(List.of(), csv.catalog().tables("main"));

        // A name that ends in .gz is compressed by default, and .tsv.gz has a tab between the fields.
        Path gz = dir.resolve("all.tsv.gz");
        write(gz, Map.of());
        assertEquals(expected(""), Arrays.deepToString(read(csv.fileTable("csv", gz, SCALARS, Map.of())).toArray()));
    }

    @Test
    void theColumnsComeByPositionAndTheHeaderIsSkippedUnread() throws IOException {
        Path file = dir.resolve("h.csv");
        Files.writeString(file, "x,y\n1,2024-02-29\n,\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.SMALLINT), new Schema.Field("b", DataType.DATE));
        assertEquals("[[1, " + Conversions.parseDate("2024-02-29") + "], [null, null]]",
                Arrays.deepToString(read(csv.fileTable("csv", file, schema, Map.of())).toArray()));
        Files.writeString(file, "1,2024-02-29\n", StandardCharsets.UTF_8);
        assertEquals("[]", Arrays.deepToString(read(csv.fileTable("csv", file, schema, Map.of())).toArray()));
        assertEquals("[[1, " + Conversions.parseDate("2024-02-29") + "]]", Arrays.deepToString(read(
                csv.fileTable("csv", file, schema, Map.of("header", "false"))).toArray()));
    }

    @Test
    void anEmptyLineIsANullInAFileOfOneColumnAndSkippedOtherwise() throws IOException {
        Path one = dir.resolve("one.csv");
        Files.writeString(one, "\nc\n1\n\n3\n\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("c", DataType.INTEGER));
        assertEquals("[[1], [null], [3], [null]]", Arrays.deepToString(read(csv.fileTable("csv", one, schema,
                Map.of())).toArray()));
        assertEquals("[[1], [3]]", Arrays.deepToString(read(csv.fileTable("csv", one, schema,
                Map.of("nullstr", "NA"))).toArray()));
        Files.writeString(one, "c\n1\n\n3\n\n", StandardCharsets.UTF_8);
        assertEquals("[[1], [null], [3], [null]]", Arrays.deepToString(read(csv.bind("read_csv",
                List.of(one.toString()), Map.of())).toArray()));

        Path two = dir.resolve("two.csv");
        Files.writeString(two, "a,b\n1,2\n\n3,4\n\n", StandardCharsets.UTF_8);
        assertEquals("[[1, 2], [3, 4]]", Arrays.deepToString(read(csv.fileTable("csv", two, Schema.of(
                new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.INTEGER)), Map.of()))
                .toArray()));
    }

    @Test
    void theSameFileWithTheSameColumnsAndOptionsGivesTheSameTable() throws IOException {
        Path file = dir.resolve("s.csv");
        Files.writeString(file, "a\n1\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.BIGINT));
        TableRef table = csv.fileTable("csv", file, schema, Map.of());
        assertEquals(table, csv.fileTable("csv", file, schema, Map.of()));
        assertNotEquals(table, csv.fileTable("csv", file, schema, Map.of("header", "true")));
        assertNotEquals(table, csv.fileTable("csv", file, Schema.of(new Schema.Field("a", DataType.INTEGER)),
                Map.of()));
        Files.writeString(file, "a\n1\n2\n", StandardCharsets.UTF_8);
        TableRef changed = csv.fileTable("csv", file, schema, Map.of());
        assertNotEquals(table, changed);
        assertEquals(2, read(changed).size());
    }

    @Test
    void errors() throws IOException {
        Path file = dir.resolve("e.csv");
        Files.writeString(file, "a,b\n1,2\n3,x\n4\n", StandardCharsets.UTF_8);
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.INTEGER));
        List<Map.Entry<String, Runnable>> errors = new ArrayList<>();
        errors.add(Map.entry("The CSV source csv reads no files of the format json",
                () -> csv.fileTable("json", file, schema, Map.of())));
        errors.add(Map.entry("The CSV file " + file + " has an unknown option rows; known are header, delimiter,"
                + " delim, sep, quote, escape, nullstr, null, dateformat, timestampformat, compression",
                () -> csv.fileTable("csv", file, schema, Map.of("rows", "3"))));
        errors.add(Map.entry("Not supported: an escape other than the quote, for the CSV file " + file + "; a quote"
                + " in a quoted value is read as two quotes",
                () -> csv.fileTable("csv", file, schema, Map.of("escape", "\\"))));
        errors.add(Map.entry("The CSV file " + file + " needs a delimiter and a quote that differ from each other"
                + " and from line breaks", () -> csv.fileTable("csv", file, schema, Map.of("quote", ","))));
        errors.add(Map.entry("The option compression of the CSV file " + file + " must be gzip, none or auto, not"
                + " zip", () -> csv.fileTable("csv", file, schema, Map.of("compression", "zip"))));
        errors.add(Map.entry("A CSV file holds no values of the type INTEGER[], as the column l has",
                () -> csv.fileTable("csv", file, Schema.of(new Schema.Field("l", DataType.listOf(DataType.INTEGER))),
                        Map.of())));
        Path missing = dir.resolve("missing.csv");
        errors.add(Map.entry("No files found that match the pattern \"" + missing + "\"",
                () -> csv.fileTable("csv", missing, schema, Map.of())));
        Path zst = dir.resolve("e.csv.zst");
        Files.writeString(zst, "a,b\n", StandardCharsets.UTF_8);
        errors.add(Map.entry("Files compressed with zstd are not supported: " + zst,
                () -> csv.fileTable("csv", zst, schema, Map.of())));
        for (Map.Entry<String, Runnable> e : errors) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, e.getValue()::run);
            assertEquals(e.getKey(), x.getMessage());
        }
        IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                () -> csv.fileTable("csv", file, schema, Map.of("dateformat", "%Q")));
        assertTrue(format.getMessage().startsWith("The option dateformat of the CSV file " + file + " is invalid: "),
                format.getMessage());

        // A field that does not parse, and a record of too few fields, are errors of the scan.
        TableRef table = csv.fileTable("csv", file, schema, Map.of());
        ConversionException x = assertThrows(ConversionException.class, () -> read(table));
        assertTrue(x.getMessage().startsWith("Line 3 of " + file.toAbsolutePath() + ", column b: "), x.getMessage());
        Files.writeString(file, "a,b\n1,2\n4\n", StandardCharsets.UTF_8);
        x = assertThrows(ConversionException.class, () -> read(csv.fileTable("csv", file, schema, Map.of())));
        assertEquals("Line 3 of " + file.toAbsolutePath() + " has 1 fields, the header 2", x.getMessage());
        // A date in another form than the format is an error, also one a cast would read.
        Files.writeString(file, "a,b\n2024-02-29,x\n", StandardCharsets.UTF_8);
        Schema dates = Schema.of(new Schema.Field("a", DataType.DATE), new Schema.Field("b", DataType.VARCHAR));
        x = assertThrows(ConversionException.class, () -> read(csv.fileTable("csv", file, dates,
                Map.of("dateformat", "%d.%m.%Y"))));
        assertTrue(x.getMessage().startsWith("Line 2 of " + file.toAbsolutePath() + ", column a: "), x.getMessage());
    }

    @Test
    void readCsvReadsGzipAndDateFormats() throws IOException {
        Path gz = dir.resolve("d.tsv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("d\tt\tn\n29.02.2024\t01.03.2024 10:11:12\t1\n".getBytes(StandardCharsets.UTF_8));
        }
        TableRef t = csv.bind("read_csv", List.of(gz.toString()), Map.of("dateformat", "%d.%m.%Y",
                "timestampformat", "%d.%m.%Y %H:%M:%S"));
        assertEquals("d DATE, t TIMESTAMP, n BIGINT", describe(csv.catalog().schema(t)));
        assertEquals(List.of(Conversions.parseDate("2024-02-29"), Conversions.parseTimestamp("2024-03-01 10:11:12"),
                1L), Arrays.asList(read(t).get(0)));
        assertEquals(1L, csv.catalog().rowCount(t).orElseThrow());
        TableRef plain = csv.bind("read_csv", List.of(gz.toString()), Map.of());
        assertEquals("d VARCHAR, t VARCHAR, n BIGINT", describe(csv.catalog().schema(plain)));
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> csv.bind("read_csv", List.of(gz.toString()), Map.of("timestamp_format", "%Q")));
        assertTrue(x.getMessage().startsWith("The argument timestamp_format of read_csv is invalid: "),
                x.getMessage());
    }

    @Test
    void theFactoryNamesTheFormat() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("csv")).findFirst().orElseThrow();
        assertEquals(List.of("csv"), factory.fileFormats());
        assertEquals(List.of("csv"), csv.fileFormats());
    }

    private static String describe(Schema schema) {
        return String.join(", ", schema.fields().stream().map(f -> f.name() + " " + f.type().sqlName()).toList());
    }
}
