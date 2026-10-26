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
import net.fyrql.connector.FileStores;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tables of Parquet files in directories of the form {@code key=value},
 * written by DuckDB with {@code PARTITION_BY}: the partition columns, the
 * rows as DuckDB reads them, and the files the filters on partition columns
 * skip.
 */
class ParquetHiveTest {

    @TempDir
    static Path dir;

    private static Path sales;

    /** 120 rows in 12 files, by three years and four months. */
    @BeforeAll
    static void write() throws Exception {
        sales = dir.resolve("sales");
        ParquetTestFiles.duckdb("CREATE TABLE s AS SELECT i AS id, 2023 + i % 3 AS year, 1 + i % 4 AS month,"
                + " CASE WHEN i % 5 = 0 THEN NULL ELSE 'r' || (i % 2) END AS region FROM range(120) t(i);"
                + " COPY s TO " + ParquetTestFiles.sql(sales) + " (FORMAT parquet, PARTITION_BY (year, month))");
    }

    private static final TableRef SALES = TableRef.of("pq", "sales");

    /** id, region, month, year. */
    private static final List<ColumnPath> ALL = ColumnPath.whole(List.of(0, 1, 2, 3));

    private static ParquetConnector connector() {
        return ParquetConnector.builder("pq").path("sales", sales).recordReads(true).build();
    }

    private static List<List<String>> duckdb(String where) throws Exception {
        return ParquetTestFiles.duckdbRows("SELECT id, region, month, year FROM read_parquet("
                + pattern(sales, "/**/*.parquet") + ")" + where + " ORDER BY id");
    }

    @Test
    void thePartitionsFollowTheColumnsOfTheFilesOrderedByTheirKeys() throws Exception {
        ParquetConnector c = connector();
        assertEquals(List.of(new Schema.Field("id", DataType.BIGINT, true),
                new Schema.Field("region", DataType.VARCHAR, true), new Schema.Field("month", DataType.BIGINT, true),
                new Schema.Field("year", DataType.BIGINT, true)), c.catalog().schema(SALES).fields());
        assertEquals(120, c.catalog().rowCount(SALES).getAsLong());
        assertEquals(duckdb(""), sorted(scan(c, SALES, ALL, List.of(), OptionalLong.empty())));
        // Only partition columns: the files deliver their numbers of rows.
        List<List<String>> years = scan(c, SALES, List.of(ColumnPath.whole(3)), List.of(), OptionalLong.empty());
        assertEquals(120, years.size());
        assertEquals(40, years.stream().filter(r -> r.get(0).equals("2024")).count());
    }

    @Test
    void aFilterOnPartitionsIsTakenOverAndSkipsFiles() throws Exception {
        ParquetConnector c = connector();
        Expression year = eq(col(3, DataType.BIGINT), lit(2024L));
        ScanPlan plan = c.plan(SALES, request(ALL, List.of(year), OptionalLong.empty()));
        assertEquals(List.of(year), plan.acceptedFilters());
        List<List<String>> expected = duckdb(" WHERE year = 2024");
        SourceStatistics read = counted(() -> assertEquals(expected, sorted(scan(c, SALES, ALL, List.of(year),
                OptionalLong.empty()))));
        assertEquals(4, read.filesRead());
        assertEquals(8, read.filesSkipped());
        assertEquals(4, read.rowGroupsRead());

        // With all filters taken over, so is a limit.
        Expression months = or(eq(col(2, DataType.BIGINT), lit(1L)), eq(col(2, DataType.BIGINT), lit(2L)));
        ScanPlan limited = c.plan(SALES, request(ALL, List.of(year, months), OptionalLong.of(15)));
        assertTrue(limited.limitAccepted());
        List<List<String>> rows = scan(c, SALES, ALL, List.of(year, months), OptionalLong.of(15));
        assertEquals(15, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.get(3).equals("2024") && (r.get(2).equals("1")
                || r.get(2).equals("2"))), rows::toString);
    }

    @Test
    void aFilterThatAlsoReadsTheFilesStaysWithTheEngine() throws Exception {
        ParquetConnector c = connector();
        Expression mixed = or(eq(col(3, DataType.BIGINT), lit(2024L)), gt(col(0, DataType.BIGINT), lit(100L)));
        ScanPlan plan = c.plan(SALES, request(ALL, List.of(mixed), OptionalLong.of(3)));
        assertEquals(List.of(), plan.acceptedFilters());
        assertFalse(plan.limitAccepted());
        SourceStatistics read = counted(() -> assertEquals(120, scan(c, SALES, ALL, List.of(mixed),
                OptionalLong.empty()).size()));
        assertEquals(12, read.filesRead());
        assertEquals(0, read.filesSkipped());
    }

    @Test
    void countStarUnderFiltersOnPartitionsComesFromTheFootersOfTheFilesLeft() throws Exception {
        ParquetConnector c = connector();
        PlanNode.AggregateCall count = new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false,
                "count_star()", null, List.of());
        Expression month = eq(col(0, DataType.BIGINT), lit(1L));
        ScanPlan plan = c.plan(SALES, new ScanRequest(List.of(ColumnPath.whole(2)), List.of(month),
                OptionalLong.empty(), List.of(), Optional.of(new PlanNode.Aggregation(List.of(), List.of(count)))));
        assertTrue(plan.aggregationAccepted());
        List<Split> splits = c.splits(plan);
        assertEquals(1, splits.size());
        try (BatchReader reader = c.open(splits.get(0))) {
            assertEquals(30L, reader.next().column(0).get(0));
        }
        assertEquals(List.of(), c.reads(), "no file is read");
    }

    @Test
    void theTableFunctionTakesTheOptionsOfPartitionsAndPatterns() throws Exception {
        ParquetConnector c = ParquetConnector.builder("pq").build();
        TableRef auto = c.bind("read_parquet", List.of(sales.toString()), Map.of());
        assertEquals(List.of("id", "region", "month", "year"), names(c.catalog().schema(auto)));
        TableRef none = c.bind("read_parquet", List.of(sales.toString()), Map.of("hive_partitioning", false));
        assertEquals(List.of("id", "region"), names(c.catalog().schema(none)));
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("hive_types", Map.of("year", "INTEGER", "month", "VARCHAR"));
        types.put("timezone", "UTC");
        TableRef typed = c.bind("read_parquet", List.of(sales.toString()), types);
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.VARCHAR, DataType.INTEGER),
                c.catalog().schema(typed).types());
        TableRef tree = c.bind("read_parquet", List.of(sales + "/**/*.parquet"), Map.of());
        assertEquals(c.catalog().schema(auto), c.catalog().schema(tree));
        TableRef below = c.bind("read_parquet", List.of(sales + "/year=2024/*/*.parquet"), Map.of());
        assertEquals(40, c.catalog().rowCount(below).getAsLong());
        assertEquals(List.of("id", "region", "month", "year"), names(c.catalog().schema(below)));
        assertEquals("hive_types names day, which is no partition; the partitions are month, year",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet",
                        List.of(sales.toString()), Map.of("hive_types", Map.of("day", "DATE")))).getMessage());
        Path mixed = dir.resolve("mixed");
        Files.createDirectories(mixed.resolve("year=2023"));
        Path one = sales.resolve("year=2023").resolve("month=1").resolve("data_0.parquet");
        Files.copy(one, mixed.resolve("year=2023").resolve("a.parquet"));
        Files.copy(one, mixed.resolve("x.parquet"));
        assertEquals(List.of("id", "region"), names(c.catalog().schema(c.bind("read_parquet",
                List.of(mixed.toString()), Map.of()))));
        assertEquals("The files have different partitions: " + mixed.resolve("x.parquet").toAbsolutePath()
                + " has [], " + mixed.resolve("year=2023").resolve("a.parquet").toAbsolutePath() + " has [year]",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet",
                        List.of(mixed + "/**/*.parquet"), Map.of("hive_partitioning", true))).getMessage());
    }

    @Test
    void aColumnOfTheFilesWithTheNameOfAKeyTakesTheValueOfThePartition() throws Exception {
        Path same = dir.resolve("same");
        Files.createDirectories(same.resolve("YEAR=2030"));
        ParquetTestFiles.duckdb("COPY (SELECT 1 AS x, 1999 AS year, 'f' AS z) TO "
                + ParquetTestFiles.sql(same.resolve("YEAR=2030").resolve("a.parquet")) + " (FORMAT parquet)");
        ParquetConnector c = ParquetConnector.builder("pq").path("same", same).build();
        TableRef ref = TableRef.of("pq", "same");
        assertEquals(List.of(new Schema.Field("x", DataType.INTEGER, true),
                new Schema.Field("year", DataType.BIGINT, true), new Schema.Field("z", DataType.VARCHAR, true)),
                c.catalog().schema(ref).fields());
        assertEquals(List.of(List.of("1", "2030", "f")), scan(c, ref, ColumnPath.whole(List.of(0, 1, 2)),
                List.of(), OptionalLong.empty()));
        assertEquals(ParquetTestFiles.duckdbRows("SELECT * FROM read_parquet(" + pattern(same, "/**/*.parquet")
                + ")"),
                scan(c, ref, ColumnPath.whole(List.of(0, 1, 2)), List.of(), OptionalLong.empty()));
    }

    @Test
    void aFilterThatFailsOnAPartitionFailsTheScanOfItsRows() throws Exception {
        Path text = dir.resolve("text");
        for (String k : List.of("1", "a")) {
            Files.createDirectories(text.resolve("k=" + k));
            ParquetTestFiles.duckdb("COPY (SELECT 1 AS x) TO " + ParquetTestFiles.sql(text.resolve("k=" + k)
                    .resolve("f.parquet")) + " (FORMAT parquet)");
        }
        ParquetConnector c = ParquetConnector.builder("pq").path("text", text).build();
        TableRef ref = TableRef.of("pq", "text");
        assertEquals(DataType.VARCHAR, c.catalog().schema(ref).type(1));
        Expression filter = eq(cast(col(1, DataType.VARCHAR), DataType.INTEGER), lit(1));
        List<ColumnPath> columns = ColumnPath.whole(List.of(0, 1));
        assertEquals(List.of(filter), c.plan(ref, request(columns, List.of(filter), OptionalLong.empty()))
                .acceptedFilters());
        assertThrows(RuntimeException.class, () -> scan(c, ref, columns, List.of(filter), OptionalLong.empty()));
        Expression one = eq(col(1, DataType.VARCHAR), lit("1"));
        assertEquals(List.of(List.of("1", "1")), scan(c, ref, columns, List.of(one), OptionalLong.empty()));
    }

    @Test
    void theFilesOfAStoreHavePartitionsByTheirKeys() throws Exception {
        MemoryStore store = new MemoryStore();
        try (Stream<Path> files = Files.walk(sales)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                store.put("mem://b/sales/" + sales.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        ParquetConnector c = ParquetConnector.builder("pq").path("sales", "mem://b/sales").recordReads(true).build();
        c.fileStores(new FileStores(Map.of("mem", store), Path.of("")));
        assertEquals(List.of("id", "region", "month", "year"), names(c.catalog().schema(SALES)));
        Expression year = eq(col(3, DataType.BIGINT), lit(2025L));
        assertEquals(duckdb(" WHERE year = 2025"), sorted(scan(c, SALES, ALL, List.of(year), OptionalLong.empty())));
        assertEquals(4, c.reads().stream().map(ParquetConnector.Read::file).distinct().count());
        TableRef pattern = c.bind("read_parquet", List.of("mem://b/sales/year=2024/**"), Map.of());
        assertEquals(40, c.catalog().rowCount(pattern).getAsLong());
        assertEquals(List.of("id", "region", "month", "year"), names(c.catalog().schema(pattern)));
    }

    @Test
    void aTableWhosePartitionsChangeFailsItsNextScan() throws Exception {
        Path tables = dir.resolve("tables");
        Path moving = tables.resolve("moving");
        Files.createDirectories(moving.resolve("k=1"));
        Path first = moving.resolve("k=1").resolve("a.parquet");
        Files.copy(sales.resolve("year=2023").resolve("month=1").resolve("data_0.parquet"), first);
        ParquetConnector c = ParquetConnector.builder("pq").directory(tables).build();
        assertEquals(List.of("moving"), c.catalog().tables(TableRef.MAIN));
        TableRef ref = TableRef.of("pq", "moving");
        assertEquals(List.of("id", "region", "k"), names(c.catalog().schema(ref)));
        Files.copy(first, moving.resolve("b.parquet"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.splits(c.plan(ref, request(ColumnPath.whole(List.of(0)), List.of(), OptionalLong.empty()))));
        assertEquals("The files of the table pq.main.moving changed their partitions since they were read:"
                + " [k BIGINT] and now []", e.getMessage());
    }

    @Test
    void theConfigurationSetsTheOptionsOfPartitions() {
        ParquetConnectorFactory factory = new ParquetConnectorFactory();
        ParquetConnector typed = factory.create("pq", Map.of("tables.sales.path", "sales",
                "tables.sales.hiveTypes", "year INTEGER, month DECIMAL(4, 1)", "hiveTypesAutocast", "false"), dir);
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.decimal(4, 1), DataType.INTEGER),
                typed.catalog().schema(SALES).types());
        ParquetConnector none = factory.create("pq", Map.of("tables.sales", "sales", "hivePartitioning", "FALSE"),
                dir);
        assertEquals(List.of("id", "region"), names(none.catalog().schema(SALES)));
        assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR),
                factory.create("pq", Map.of("tables.sales", "sales", "hiveTypesAutocast", "false"), dir).catalog()
                        .schema(SALES).types());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> factory.create("pq",
                Map.of("tables.sales", "sales", "hivePartitioning", "maybe"), dir)).getMessage()
                .contains("true, false or auto"));
        assertEquals("The table sales of the Parquet source pq has invalid hiveTypes: Expected a name and a type,"
                + " such as year INTEGER, not 'year'", assertThrows(IllegalArgumentException.class,
                        () -> factory.create("pq", Map.of("tables.sales", "sales", "tables.sales.hiveTypes", "year"),
                                dir)).getMessage());
    }

    private static List<String> names(Schema schema) {
        return schema.fields().stream().map(Schema.Field::name).toList();
    }

    private static ScanRequest request(List<ColumnPath> columns, List<Expression> filters, OptionalLong limit) {
        return new ScanRequest(columns, filters, limit, List.of(), Optional.empty());
    }

    private static SourceStatistics counted(Runnable scan) {
        SourceCounters counters = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(counters)) {
            scan.run();
        }
        return counters.snapshot();
    }

    /** Reads a table under filters taken over or not; the values as text, as DuckDB gives them. */
    private static List<List<String>> scan(ParquetConnector c, TableRef table, List<ColumnPath> columns,
            List<Expression> filters, OptionalLong limit) {
        ScanPlan plan = c.plan(table, request(columns, filters, limit));
        List<List<String>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            try (BatchReader reader = c.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    for (int r = 0; r < b.rowCount(); r++) {
                        List<String> row = new ArrayList<>();
                        for (int k = 0; k < b.schema().size(); k++) {
                            Object v = b.column(k).get(r);
                            row.add(v == null ? null : v.toString());
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    /** Sorts rows by their first value, a number. */
    private static List<List<String>> sorted(List<List<String>> rows) {
        List<List<String>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(r -> Long.parseLong(r.get(0))));
        return sorted;
    }

    /** Returns a pattern of files under a directory as DuckDB reads it in a string literal. */
    private static String pattern(Path directory, String rest) {
        String quoted = ParquetTestFiles.sql(directory);
        return quoted.substring(0, quoted.length() - 1) + rest + "'";
    }
}
