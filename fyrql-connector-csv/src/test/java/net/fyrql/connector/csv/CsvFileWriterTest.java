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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV files that {@code COPY ... TO} writes, through {@link CsvConnector#openFile}. */
class CsvFileWriterTest {

    static final Schema ALL_TYPES = Schema.of(new Schema.Field("i", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("d", DataType.DOUBLE),
            new Schema.Field("f", DataType.FLOAT), new Schema.Field("dec", DataType.decimal(10, 2)),
            new Schema.Field("hi", DataType.HUGEINT), new Schema.Field("dt", DataType.DATE),
            new Schema.Field("ts", DataType.TIMESTAMP), new Schema.Field("b", DataType.BLOB),
            new Schema.Field("u", DataType.UUID), new Schema.Field("bo", DataType.BOOLEAN),
            new Schema.Field("st", DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
                    new DataType.Struct.Field("y", DataType.VARCHAR))),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));

    /** What DuckDB 1.5 writes for {@link #allTypes()} with {@code COPY ... TO 'x.csv' (HEADER)}. */
    private static final String DUCKDB_CSV = """
            i,s,d,f,dec,hi,dt,ts,b,u,bo,st,l,m
            1,plain,0.1,1.5,-12.30,170141183460469231731687303715884105727,2024-02-29,2024-02-29 13:14:15.123456,\
            \\x00\\xFFA,123e4567-e89b-12d3-a456-426614174000,true,"{'x': 1, 'y': q}","[1, NULL, 3]","{a=1, b=NULL}"
            ,"",nan,inf,,,0001-01-01,1500-01-01 00:00:00,"",,false,"{'x': NULL, 'y': NULL}",[],{}
            3,"a,b""c
            d",-0.0,-inf,0.00,-1,9999-12-31,1970-01-01 00:00:00,,,,,,
            4,,1e+20,1.5e-07,,,,,,,,,[NULL],
            """;

    @TempDir
    Path dir;

    /** Rows with a value of each type and the values that need care: quotes, line breaks, NaN, -0.0. */
    static Batch allTypes() {
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

    private static CsvConnector connector() {
        return CsvConnector.builder("csv").build();
    }

    private static long write(Path file, Schema schema, Map<String, String> options, Batch... batches) {
        try (Sink sink = connector().openFile("csv", file, schema, options)) {
            for (Batch b : batches) {
                sink.write(b);
            }
            return sink.commit();
        }
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(Path::getFileName).sorted().toList();
        }
    }

    @Test
    void writesWhatDuckDbWrites() throws IOException {
        Path file = dir.resolve("all.csv");
        Batch batch = allTypes();
        assertEquals(5, write(file, ALL_TYPES, Map.of(), batch.select(new int[] {0, 1}), Batch.empty(ALL_TYPES),
                batch.select(new int[] {2, 3}), batch.select(new int[] {0})));
        assertEquals(DUCKDB_CSV + DUCKDB_CSV.lines().skip(1).findFirst().orElseThrow() + "\n",
                Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(List.of(Path.of("all.csv")), files());

        // The file reads back; an empty string reads as NULL, as in DuckDB by default.
        List<Schema.Field> text = new ArrayList<>(ALL_TYPES.fields().subList(0, 11));
        for (String nested : List.of("st", "l", "m")) {
            text.add(new Schema.Field(nested, DataType.VARCHAR));
        }
        CsvConnector reader = CsvConnector.builder("csv").schema("all", new Schema(text)).file("all", file).build();
        ScanPlan plan = reader.plan(TableRef.of("csv", "all"), ScanRequest.columns(List.of(0, 1, 2, 6, 8, 9)));
        List<List<Object>> rows = Batches.readAll(reader.open(reader.splits(plan).get(0))).stream()
                .flatMap(b -> b.rows().stream()).map(Arrays::asList).toList();
        assertEquals(Arrays.asList(1, "plain", 0.1, Conversions.parseDate("2024-02-29")), rows.get(0).subList(0, 4));
        assertEquals("[0, -1, 65]", Arrays.toString((byte[]) rows.get(0).get(4)));
        assertEquals(Arrays.asList(null, null, Double.NaN, Conversions.parseDate("0001-01-01"), null, null),
                rows.get(1));
        assertEquals("a,b\"c\nd", rows.get(2).get(1));
        assertEquals(5, rows.size());
    }

    @Test
    void optionsChangeTheDialect() throws IOException {
        Schema schema = Schema.of(new Schema.Field("a b", DataType.VARCHAR), new Schema.Field("d", DataType.DATE),
                new Schema.Field("t", DataType.TIMESTAMP));
        Batch batch = Batch.ofRows(schema, List.of(
                new Object[] {"NA", Conversions.parseDate("2024-03-05"),
                    Conversions.parseTimestamp("2024-03-05 07:08:09")},
                new Object[] {null, null, null},
                new Object[] {"it's;\\", Conversions.parseDate("0099-01-02"), 0L},
                new Object[] {"", null, null}));
        Path file = dir.resolve("x.txt");
        write(file, schema, Map.of("header", "false", "delim", ";", "quote", "'", "escape", "\\", "nullstr", "NA",
                "dateformat", "%d.%m.%Y", "timestampformat", "%Y%m%d %H:%M"), batch);
        assertEquals("""
                'NA';05.03.2024;20240305 07:08
                NA;NA;NA
                'it\\'s;\\\\';02.01.0099;19700101 00:00
                ;NA;NA
                """, Files.readString(file));

        // A .tsv file has tabs; the header is quoted as needed too.
        Path tsv = dir.resolve("x.tsv");
        write(tsv, schema, Map.of(), batch.select(new int[] {1, 3}));
        assertEquals("a b\td\tt\n\t\t\n\"\"\t\t\n", Files.readString(tsv));
        write(tsv, schema, Map.of("sep", ",", "header", "on"), batch.select(new int[] {1}));
        assertEquals("a b,d,t\n,,\n", Files.readString(tsv));
    }

    @Test
    void gzipCompressesAFileThatEndsInGzOrWhenAskedFor() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.BIGINT));
        Batch batch = Batch.ofRows(schema, List.of(new Object[] {1L}, new Object[] {2L}));
        Path gz = dir.resolve("n.csv.gz");
        write(gz, schema, Map.of(), batch);
        assertEquals("n\n1\n2\n", gunzip(gz));
        Path asked = dir.resolve("n.csv");
        write(asked, schema, Map.of("compression", "GZIP"), batch);
        assertEquals("n\n1\n2\n", gunzip(asked));
        write(gz, schema, Map.of("compression", "none"), batch);
        assertEquals("n\n1\n2\n", Files.readString(gz));
        assertEquals("The option compression of the CSV file " + gz + " must be gzip, none or auto, not zstd",
                assertThrows(IllegalArgumentException.class, () -> write(gz, schema, Map.of("compression", "zstd")))
                        .getMessage());
    }

    private static String gunzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aCommitReplacesTheFileAndCloseAloneLeavesNoTrace() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER));
        Path file = dir.resolve("n.csv");
        Files.writeString(file, "old\n");
        try (Sink sink = connector().openFile("csv", file, schema, Map.of())) {
            sink.write(Batch.ofRows(schema, List.<Object[]>of(new Object[] {1})));
            assertEquals(2, files().size());
            assertEquals("old\n", Files.readString(file));
        }
        assertEquals("old\n", Files.readString(file));
        assertEquals(List.of(Path.of("n.csv")), files());

        assertEquals(0, write(file, schema, Map.of()));
        assertEquals("n\n", Files.readString(file));
        assertEquals(List.of(Path.of("n.csv")), files());

        Sink sink = connector().openFile("csv", file, schema, Map.of());
        sink.commit();
        sink.close();
        assertThrows(IllegalStateException.class, () -> sink.write(Batch.empty(schema)));
        assertThrows(IllegalStateException.class, sink::commit);
    }

    @Test
    void wrongOptionsAndPathsFailBeforeAnyFileIsCreated() throws IOException {
        Schema schema = Schema.of(new Schema.Field("n", DataType.INTEGER));
        Path file = dir.resolve("n.csv");
        assertEquals("The CSV file " + file + " has an unknown option force_quote; known are header, delimiter,"
                + " delim, sep, quote, escape, nullstr, null, dateformat, timestampformat, compression",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("force_quote", "n")))
                        .getMessage());
        assertEquals("The option header of the CSV file " + file + " must be true, false, on or off: maybe",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("header", "maybe")))
                        .getMessage());
        assertEquals("The option delimiter of the CSV file " + file + " must be one character: ;;",
                assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("delimiter", ";;")))
                        .getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> write(file, schema, Map.of("dateformat",
                "%Q"))).getMessage().startsWith("The option dateformat of the CSV file " + file + " is invalid:"));
        assertEquals("The CSV file " + file + " needs a delimiter and a quote that differ from each other and from"
                + " line breaks", assertThrows(IllegalArgumentException.class, () -> write(file, schema,
                        Map.of("quote", ","))).getMessage());
        Path missing = dir.resolve("no/n.csv");
        assertEquals("Cannot write " + missing + ": the directory " + dir.resolve("no") + " does not exist",
                assertThrows(IllegalArgumentException.class, () -> write(missing, schema, Map.of())).getMessage());
        assertEquals("Cannot write " + dir + ": it is a directory",
                assertThrows(IllegalArgumentException.class, () -> write(dir, schema, Map.of())).getMessage());
        assertEquals("The CSV source csv writes no files of the format json",
                assertThrows(IllegalArgumentException.class, () -> connector().openFile("json", file, schema,
                        Map.of())).getMessage());
        assertEquals(List.of(), files());
        assertFalse(Files.exists(file));
        assertEquals(List.of("csv"), connector().fileFormats());
    }
}
