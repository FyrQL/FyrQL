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
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.GZIPOutputStream;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tables of several CSV files: directories, patterns and partitions in
 * directories of the form {@code key=value}, with the files the filters on
 * partition columns skip.
 */
class CsvHiveTest {

    @TempDir
    static Path dir;

    private static Path sales;

    /** 120 rows in 12 files, by three years and four months, as DuckDB writes them with {@code PARTITION_BY}. */
    @BeforeAll
    static void write() throws IOException {
        sales = dir.resolve("sales");
        for (int i = 0; i < 120; i++) {
            Path file = sales.resolve("year=" + (2023 + i % 3)).resolve("month=" + (1 + i % 4)).resolve("data_0.csv");
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, "id,region\n");
            }
            Files.writeString(file, i + "," + (i % 5 == 0 ? "" : "r" + i % 2) + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private static final TableRef SALES = TableRef.of("csv", "sales");

    @Test
    void thePartitionsFollowTheColumnsOfTheFiles() {
        CsvConnector c = CsvConnector.builder("csv").file("sales", sales).build();
        assertEquals(List.of(new Schema.Field("id", DataType.BIGINT), new Schema.Field("region", DataType.VARCHAR),
                new Schema.Field("month", DataType.BIGINT), new Schema.Field("year", DataType.BIGINT)),
                c.catalog().schema(SALES).fields());
        List<List<Object>> rows = sorted(scan(c, SALES, List.of(0, 1, 2, 3), List.of()));
        assertEquals(120, rows.size());
        assertEquals(Arrays.asList(5L, null, 2L, 2025L), rows.get(5));
        assertEquals(Arrays.asList(7L, "r1", 4L, 2024L), rows.get(7));
        // Only partition columns: the files deliver their numbers of rows.
        assertEquals(40, scan(c, SALES, List.of(3), List.of()).stream().filter(r -> r.get(0).equals(2024L)).count());
    }

    @Test
    void aFilterOnPartitionsIsTakenOverAndSkipsFiles() {
        CsvConnector c = CsvConnector.builder("csv").file("sales", sales).build();
        Expression year = eq(col(3, DataType.BIGINT), lit(2024L));
        ScanPlan plan = c.plan(SALES, request(List.of(0, 1, 2, 3), List.of(year)));
        assertEquals(List.of(year), plan.acceptedFilters());
        SourceCounters counters = new SourceCounters();
        List<List<Object>> rows;
        try (SourceCounters.Scope s = SourceCounters.enter(counters)) {
            rows = scan(c, SALES, List.of(0, 1, 2, 3), List.of(year));
        }
        SourceStatistics read = counters.snapshot();
        assertEquals(40, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.get(3).equals(2024L)));
        assertEquals(4, read.filesRead());
        assertEquals(8, read.filesSkipped());

        Expression mixed = or(year, gt(col(0, DataType.BIGINT), lit(100L)));
        assertEquals(List.of(), c.plan(SALES, request(List.of(0, 1, 2, 3), List.of(mixed))).acceptedFilters());
        assertEquals(120, scan(c, SALES, List.of(0, 1, 2, 3), List.of(mixed)).size());
    }

    @Test
    void theTableFunctionReadsDirectoriesAndPatterns() {
        CsvConnector c = CsvConnector.builder("csv").build();
        TableRef all = c.bind("read_csv", List.of(sales.toString()), Map.of());
        assertEquals(List.of("id", "region", "month", "year"), names(c, all));
        assertEquals(120, scan(c, all, List.of(0), List.of()).size());
        TableRef tree = c.bind("read_csv", List.of(sales + "/**/*.csv"), Map.of());
        assertEquals(120, scan(c, tree, List.of(0), List.of()).size());
        TableRef below = c.bind("read_csv", List.of(sales + "/year=2024/*/*.csv"), Map.of());
        assertEquals(List.of("id", "region", "month", "year"), names(c, below));
        assertEquals(40, scan(c, below, List.of(0), List.of()).size());
        TableRef none = c.bind("read_csv", List.of(sales.toString()), Map.of("hive_partitioning", false));
        assertEquals(List.of("id", "region"), names(c, none));
        TableRef typed = c.bind("read_csv", List.of(sales.toString()), Map.of("hive_types",
                Map.of("year", "INTEGER"), "hive_types_autocast", false));
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.VARCHAR, DataType.INTEGER),
                c.catalog().schema(typed).types());
        assertEquals(all, c.bind("read_csv", List.of(sales.toString()), Map.of()));
    }

    @Test
    void theFilesOfATableShareTheColumnsOfTheFirst() throws IOException {
        Path plain = dir.resolve("plain");
        Files.createDirectories(plain.resolve("sub"));
        Files.writeString(plain.resolve("a.csv"), "x,y\n1,a\n2,b\n");
        Files.writeString(plain.resolve("sub").resolve("c.csv"), "x,y\n5,e\n");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(plain.resolve("b.csv.gz")))) {
            out.write("x,y\n3,c\n4,d\n".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(plain.resolve("notes.txt"), "not a table");
        CsvConnector c = CsvConnector.builder("csv").file("plain", plain).build();
        TableRef ref = TableRef.of("csv", "plain");
        assertEquals(List.of("x", "y"), names(c, ref));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), scan(c, ref, List.of(0), List.of()).stream().map(r -> r.get(0))
                .toList());
        assertTrue(c.catalog().rowCount(ref).isEmpty(), "a table with a compressed file has no estimate of its rows");

        // A file added later is read by the next scan; a file of other columns fails it.
        Files.writeString(plain.resolve("sub").resolve("d.csv"), "x,y\n6,f\n");
        assertEquals(6, scan(c, ref, List.of(0), List.of()).size());
        Files.writeString(plain.resolve("z.csv"), "x,y,z\n7,g,h\n");
        RuntimeException e = assertThrows(RuntimeException.class, () -> scan(c, ref, List.of(0), List.of()));
        assertTrue(e.getMessage().startsWith("Line 2 of " + plain.resolve("z.csv").toAbsolutePath()
                + " has 3 fields, the header 2"), e.getMessage());

        Path mixed = dir.resolve("mixed");
        Files.createDirectories(mixed);
        Files.writeString(mixed.resolve("a.csv"), "x\n1\n");
        Files.writeString(mixed.resolve("b.tsv"), "x\n2\n");
        assertEquals("The files of " + mixed.toAbsolutePath() + " mix TSV files with other ones; give the delimiter",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_csv", List.of(mixed.toString()),
                        Map.of())).getMessage());
        assertEquals(2, scan(c, c.bind("read_csv", List.of(mixed.toString()), Map.of("delim", ",")), List.of(0),
                List.of()).size());
    }

    @Test
    void eachLargeFileIsReadInParts() throws IOException {
        Path large = dir.resolve("large");
        for (int f = 0; f < 3; f++) {
            StringBuilder sb = new StringBuilder("id,text\n");
            for (int i = 0; i < 500; i++) {
                sb.append(f * 1000 + i).append(",\"line ").append(i).append("\n of a quoted field\"\n");
            }
            Files.createDirectories(large.resolve("part=" + f));
            Files.writeString(large.resolve("part=" + f).resolve("f.csv"), sb);
        }
        CsvConnector c = CsvConnector.builder("csv").splitSize(4096).batchSize(16).file("large", large).build();
        TableRef ref = TableRef.of("csv", "large");
        List<Split> splits = c.splits(c.plan(ref, request(List.of(0, 2), List.of())));
        assertTrue(splits.size() > 6, "splits: " + splits.size());
        List<List<Object>> rows = sorted(scan(c, ref, List.of(0, 2), List.of()));
        assertEquals(1500, rows.size());
        assertEquals(List.of(2499L, 2L), rows.get(1499));
        Expression part = eq(col(1, DataType.BIGINT), lit(1L));
        assertEquals(500, scan(c, ref, List.of(0, 2), List.of(part)).size());
    }

    @Test
    void theConfigurationSetsTheOptionsOfPartitionsAndPatterns() throws IOException {
        CsvConnectorFactory factory = new CsvConnectorFactory();
        CsvConnector typed = factory.create("csv", Map.of("tables.sales.file", "sales/**/*.csv",
                "tables.sales.hiveTypes", "year INTEGER", "hiveTypesAutocast", "false"), dir);
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.VARCHAR, DataType.INTEGER),
                typed.catalog().schema(SALES).types());
        CsvConnector none = factory.create("csv", Map.of("tables.sales", "sales", "hivePartitioning", "false"), dir);
        assertEquals(List.of("id", "region"), names(none, SALES));
        Path tables = dir.resolve("tables");
        Files.createDirectories(tables.resolve("t").resolve("k=1"));
        Files.writeString(tables.resolve("t").resolve("k=1").resolve("a.csv"), "x\n1\n");
        Files.writeString(tables.resolve("u.csv"), "y\n2\n");
        CsvConnector directory = factory.create("csv", Map.of("directory", "tables"), dir);
        assertEquals(List.of("t", "u"), directory.catalog().tables(TableRef.MAIN));
        assertEquals(List.of("x", "k"), names(directory, TableRef.of("csv", "t")));
        assertEquals("The table sales of the CSV source csv has invalid hiveTypes: Expected a name and a type,"
                + " such as year INTEGER, not 'year'", assertThrows(IllegalArgumentException.class,
                        () -> factory.create("csv", Map.of("tables.sales", "sales", "tables.sales.hiveTypes", "year"),
                                dir)).getMessage());
    }

    private static List<String> names(CsvConnector c, TableRef table) {
        return c.catalog().schema(table).fields().stream().map(Schema.Field::name).toList();
    }

    private static ScanRequest request(List<Integer> columns, List<Expression> filters) {
        return new ScanRequest(ColumnPath.whole(columns), filters, OptionalLong.empty(), List.of(), Optional.empty());
    }

    /** Reads the columns of a table under filters, which the scan takes over or not, from each split in turn. */
    private static List<List<Object>> scan(CsvConnector c, TableRef table, List<Integer> columns,
            List<Expression> filters) {
        ScanPlan plan = c.plan(table, request(columns, filters));
        List<List<Object>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            for (Batch b : Batches.readAll(c.open(split))) {
                for (int r = 0; r < b.rowCount(); r++) {
                    rows.add(Arrays.asList(b.row(r)));
                }
            }
        }
        return rows;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(r -> (Long) r.get(0)));
        return sorted;
    }
}
