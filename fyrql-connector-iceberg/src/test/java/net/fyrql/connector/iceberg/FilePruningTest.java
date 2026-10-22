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

package net.fyrql.connector.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

/**
 * A filter on the partition reads only the files of the partition; bounds
 * and null counts leave out files; manifests are left out by their
 * partition summaries; and random filters never lose a row.
 */
class FilePruningTest {

    private static IcebergTestTables tables;
    private static IcebergConnector connector;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
        connector = IcebergConnector.builder("ice").recordReads(true).build();
    }

    private static TableRef scan(String name) {
        return connector.bind("iceberg_scan", List.of(tables.location(name).toString()), Map.of());
    }

    private static Expression col(int index, DataType type) {
        return new Expression.Column(index, type);
    }

    private static Expression lit(DataType type, Object value) {
        return new Expression.Literal(type, value);
    }

    private static Expression cmp(ComparisonOp op, Expression l, Expression r) {
        return new Expression.Comparison(op, l, r);
    }

    private static long micros(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
    }

    /** Reads all columns of part under a filter and returns the data files read. */
    private static Set<String> filesRead(String table, Expression filter) {
        TableRef ref = scan(table);
        connector.clearReads();
        read(ref, filter);
        Set<String> files = new TreeSet<>();
        connector.reads().forEach(r -> files.add(java.nio.file.Path.of(r.file()).toString()));
        return files;
    }

    private static List<List<Object>> read(TableRef ref, Expression filter) {
        int n = connector.catalog().schema(ref).size();
        List<Integer> all = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            all.add(c);
        }
        return IcebergRows.read(connector, ref, new ScanRequest(ColumnPath.whole(all), List.of(filter),
                OptionalLong.empty(), List.of(), Optional.empty()));
    }

    private static Set<String> allFiles(String table) throws IOException {
        Set<String> files = new TreeSet<>();
        try (CloseableIterable<FileScanTask> tasks = tables.table(table).newScan().planFiles()) {
            tasks.forEach(t -> files.add(java.nio.file.Path.of(t.file().location()).toString()));
        }
        return files;
    }

    @Test
    void aFilterOnThePartitionReadsOnlyItsFiles() throws IOException {
        int total = allFiles("part").size();
        Set<String> a = filesRead("part", cmp(ComparisonOp.EQ, col(1, DataType.VARCHAR), lit(DataType.VARCHAR, "a")));
        assertFalse(a.isEmpty());
        assertTrue(a.size() < total, a.size() + " of " + total);
        a.forEach(f -> assertTrue(f.replace('\\', '/').contains("/cat=a/"), f));
        Set<String> day = filesRead("part", cmp(ComparisonOp.GE, col(2, DataType.TIMESTAMP),
                lit(DataType.TIMESTAMP, micros(2024, 1, 3))));
        assertFalse(day.isEmpty());
        day.forEach(f -> assertTrue(f.replace('\\', '/').contains("/ts_day=2024-01-03/"), f));
        Set<String> before = filesRead("part", cmp(ComparisonOp.LT, col(2, DataType.TIMESTAMP),
                lit(DataType.TIMESTAMP, micros(2024, 1, 2))));
        before.forEach(f -> assertTrue(f.replace('\\', '/').contains("/ts_day=2024-01-01/"), f));
        Set<String> bucket = filesRead("part", cmp(ComparisonOp.EQ, lit(DataType.BIGINT, 85L),
                col(0, DataType.BIGINT)));
        int expected = (Transforms.hashLong(85) & Integer.MAX_VALUE) % 4;
        assertFalse(bucket.isEmpty());
        bucket.forEach(f -> assertTrue(f.replace('\\', '/').contains("/id_bucket=" + expected + "/"), f));
        long inBucket = allFiles("part").stream().filter(f -> f.replace('\\', '/').contains("/id_bucket="
                + expected + "/")).count();
        assertTrue(bucket.size() < inBucket, "the bounds of id leave out files of the bucket: " + bucket);
        Set<String> truncated = filesRead("part", new Expression.In(col(4, DataType.VARCHAR), List.of(
                lit(DataType.VARCHAR, "beta1"), lit(DataType.VARCHAR, "gamma2"))));
        truncated.forEach(f -> assertTrue(f.replace('\\', '/').matches(".*/name_trunc=(be|ga)/.*"), f));
    }

    @Test
    void aScanCountsTheFilesAndRowGroupsItReadsAndSkips() throws IOException {
        int total = allFiles("part").size();
        TableRef ref = scan("part");
        connector.clearReads();
        SourceCounters counters = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(counters)) {
            read(ref, cmp(ComparisonOp.EQ, col(1, DataType.VARCHAR), lit(DataType.VARCHAR, "a")));
        }
        SourceStatistics read = counters.snapshot();
        Set<String> files = new TreeSet<>();
        connector.reads().forEach(r -> files.add(r.file()));
        assertTrue(read.filesRead() >= files.size() && read.filesRead() > 0, read::toString);
        assertTrue(read.filesSkipped() > 0 && read.filesRead() + read.filesSkipped() <= total, read::toString);
        assertEquals(connector.reads().size(), read.rowGroupsRead());
        assertTrue(read.bytes() > 0);
    }

    @Test
    void boundsAndNullCountsLeaveOutFiles() throws IOException {
        Table part = tables.table("part");
        Set<String> high = filesRead("part", cmp(ComparisonOp.GT, col(3, DataType.DOUBLE),
                lit(DataType.DOUBLE, 55.0)));
        int expected = 0;
        try (CloseableIterable<FileScanTask> tasks = part.newScan().includeColumnStats().planFiles()) {
            for (FileScanTask t : tasks) {
                DataFile f = t.file();
                ByteBuffer bound = f.upperBounds().get(4).duplicate().order(ByteOrder.LITTLE_ENDIAN);
                double upper = bound.getDouble(bound.position());
                if (upper > 55.0) {
                    expected++;
                    assertTrue(high.contains(java.nio.file.Path.of(f.location()).toString()), f.location());
                }
            }
        }
        assertEquals(expected, high.size());
        assertEquals(Set.of(), filesRead("part", new Expression.IsNull(col(4, DataType.VARCHAR), false)));
        assertEquals(allFiles("part"), filesRead("part", new Expression.IsNull(col(4, DataType.VARCHAR), true)));
        assertEquals(Set.of(), filesRead("part", new Expression.Not(cmp(ComparisonOp.LE, col(0, DataType.BIGINT),
                lit(DataType.BIGINT, 1000L)))));
        assertEquals(allFiles("part"), filesRead("part", cmp(ComparisonOp.NE, col(0, DataType.BIGINT),
                lit(DataType.BIGINT, -1L))));
        assertEquals(Set.of(), filesRead("part", new Expression.And(
                cmp(ComparisonOp.EQ, col(1, DataType.VARCHAR), lit(DataType.VARCHAR, "a")),
                cmp(ComparisonOp.LT, col(0, DataType.BIGINT), lit(DataType.BIGINT, 80L)))));
        assertEquals(2, filesRead("eq_v2", new Expression.Between(col(0, DataType.BIGINT),
                lit(DataType.BIGINT, 5L), lit(DataType.BIGINT, 5L))).size(), "both files hold 5");
        assertEquals(1, filesRead("eq_v2", new Expression.Between(col(0, DataType.BIGINT),
                lit(DataType.BIGINT, 6L), lit(DataType.BIGINT, 9L))).size());
    }

    @Test
    void manifestsAreLeftOutByTheirPartitionSummaries() throws IOException {
        TableLocation location = TableLocation.resolve(FileStores.local(tables.warehouse()),
                tables.location("part").toString());
        TableMetadata m = TableMetadata.parse(location.metadataFile().location(),
                TableLocation.bytes(location.metadataFile()));
        IcebergFiles files = new IcebergFiles(FileStores.local(tables.warehouse()), 1 << 16);
        List<Manifests.ManifestFile> manifests = SnapshotScan.manifests(m, location, m.current(), files::avro);
        List<ColumnPath> columns = ColumnPath.whole(List.of(0, 1, 2, 3, 4));
        FilePruning none = new FilePruning(m.schema(), columns, List.of(cmp(ComparisonOp.EQ,
                col(1, DataType.VARCHAR), lit(DataType.VARCHAR, "zzz"))), m.specs());
        FilePruning b = new FilePruning(m.schema(), columns, List.of(cmp(ComparisonOp.EQ,
                col(1, DataType.VARCHAR), lit(DataType.VARCHAR, "b"))), m.specs());
        FilePruning late = new FilePruning(m.schema(), columns, List.of(cmp(ComparisonOp.GT,
                col(2, DataType.TIMESTAMP), lit(DataType.TIMESTAMP, micros(2024, 1, 4)))), m.specs());
        int data = 0;
        for (Manifests.ManifestFile manifest : manifests) {
            if (manifest.content() == Manifests.DATA) {
                data++;
                assertFalse(none.mayMatch(manifest), manifest.path());
                assertTrue(b.mayMatch(manifest), manifest.path());
                assertFalse(late.mayMatch(manifest), manifest.path());
            }
        }
        assertTrue(data >= 2, "manifests of data: " + data);
    }

    @Test
    void randomFiltersNeverLoseARow() {
        SplittableRandom random = new SplittableRandom(20261021);
        for (String name : List.of("part", "evo", "pos_v2", "eq_v2", "dv_v3", "v1", "types")) {
            TableRef ref = scan(name);
            Schema schema = connector.catalog().schema(ref);
            List<List<Object>> all = IcebergRows.read(connector, ref);
            for (int i = 0; i < 60; i++) {
                Expression filter = filter(random, schema, all, 3);
                List<List<Object>> expected = passing(schema, all, filter);
                List<List<Object>> actual = passing(schema, read(ref, filter), filter);
                assertEquals(IcebergRows.sorted(expected), IcebergRows.sorted(actual), name + " " + filter);
            }
        }
    }

    /** Returns a random filter over the primitive columns of a table with constants from its rows. */
    private static Expression filter(SplittableRandom random, Schema schema, List<List<Object>> rows, int depth) {
        int kind = random.nextInt(depth > 0 ? 6 : 3);
        if (kind == 3) {
            return new Expression.And(filter(random, schema, rows, depth - 1), filter(random, schema, rows,
                    depth - 1));
        }
        if (kind == 4) {
            return new Expression.Or(filter(random, schema, rows, depth - 1), filter(random, schema, rows,
                    depth - 1));
        }
        if (kind == 5) {
            return new Expression.Not(filter(random, schema, rows, depth - 1));
        }
        List<Integer> primitive = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            DataType t = schema.type(c);
            if (t.isIntegral() || t == DataType.DOUBLE || t == DataType.FLOAT || t == DataType.VARCHAR
                    || t == DataType.TIMESTAMP || t == DataType.DATE || t instanceof DataType.Decimal) {
                primitive.add(c);
            }
        }
        int c = primitive.get(random.nextInt(primitive.size()));
        DataType type = schema.type(c);
        if (kind == 2) {
            return new Expression.IsNull(col(c, type), random.nextBoolean());
        }
        Object value = rows.isEmpty() ? null : rows.get(random.nextInt(rows.size())).get(c);
        if (value == null) {
            return new Expression.IsNull(col(c, type), random.nextBoolean());
        }
        if (kind == 1) {
            Object other = rows.get(random.nextInt(rows.size())).get(c);
            return other == null ? new Expression.In(col(c, type), List.of(lit(type, value)))
                    : new Expression.In(col(c, type), List.of(lit(type, value), lit(type, other)));
        }
        ComparisonOp op = ComparisonOp.values()[random.nextInt(ComparisonOp.values().length)];
        return random.nextBoolean() ? cmp(op, col(c, type), lit(type, value)) : cmp(op, lit(type, value),
                col(c, type));
    }

    /** Returns the rows for which a filter is {@code TRUE}, as the engine evaluates it. */
    private static List<List<Object>> passing(Schema schema, List<List<Object>> rows, Expression filter) {
        if (rows.isEmpty()) {
            return rows;
        }
        Batch batch = Batch.ofRows(schema, rows.stream().map(List::toArray).toList());
        List<List<Object>> result = new ArrayList<>();
        for (int i : Evaluator.select(filter, batch)) {
            result.add(rows.get(i));
        }
        return result;
    }
}
