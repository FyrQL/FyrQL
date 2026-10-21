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
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skips row groups and pages of a file of ten row groups of 1000 rows in
 * pages of 100 rows, sorted by {@code id}, by the filters of a scan: by the
 * statistics of the row groups, by the dictionary of {@code name}, by the
 * Bloom filter of {@code code}, whose values are random, and by the column
 * index of the pages. For each filter the number of row groups read must be
 * as expected, and the rows that pass the filter must be those of a scan
 * without filters, so that pruning never loses a row.
 */
class ParquetPruningTest {

    private static final String SCHEMA = """
            message t {
              required int64 id;
              optional int64 code;
              optional binary name (STRING);
              optional int32 day (DATE);
              optional int32 amount (DECIMAL(9,2));
              optional int64 ts (TIMESTAMP(MICROS,true));
              optional int64 tsm (TIMESTAMP(MILLIS,false));
              optional boolean flag;
              optional int32 n;
              optional group s {
                optional int32 x;
                optional binary label (STRING);
              }
              optional double dbl;
              optional int32 small (INTEGER(8,true));
            }
            """;

    private static final int ROWS = 10_000;
    private static final int GROUP = 1000;
    private static final long DAY = 18_000;
    private static final long MICROS = 1_600_000_000_000_000L;
    private static final long MILLIS = 1_600_000_000_000L;

    /** The paths the scans read: the columns, and the fields of {@code s} on their own. */
    private static final List<ColumnPath> PATHS = List.of(path(0), path(1), path(2), path(3), path(4), path(5),
            path(6), path(7), path(8), path(9, "x"), path(9, "label"), path(10), path(11));

    private static final Schema OUTPUT = new Schema(PATHS.stream()
            .map(p -> p.field(ParquetSchemas.schema(MessageTypeParser.parseMessageType(SCHEMA)))).toList());

    @TempDir
    static Path dir;

    private static Path bloom;
    private static Path plain;
    private static long[] codes;

    @BeforeAll
    static void write() throws IOException {
        SplittableRandom random = new SplittableRandom(20261016);
        codes = new long[ROWS];
        for (int r = 0; r < ROWS; r++) {
            codes[r] = random.nextLong();
        }
        Consumer<ExampleParquetWriter.Builder> options = w -> w.withRowGroupRowCountLimit(GROUP)
                .withPageRowCountLimit(100).withDictionaryEncoding(true).withDictionaryEncoding("code", false);
        bloom = ParquetTestFiles.write(dir.resolve("bloom.parquet"), SCHEMA,
                options.andThen(w -> w.withBloomFilterEnabled("code", true)), ParquetPruningTest::rows);
        plain = ParquetTestFiles.write(dir.resolve("plain.parquet"), SCHEMA, options, ParquetPruningTest::rows);
    }

    /**
     * Writes the rows: {@code name} is {@code aaa}, {@code zzz} or the
     * number of the row group after {@code k}, so that only the dictionary
     * tells the row groups apart; {@code flag} is true only in the last row
     * group; {@code n} is {@code NULL} in all of the first row group and in
     * every third row of the next four.
     */
    private static void rows(ParquetTestFiles.RowWriter w) {
        for (int id = 0; id < ROWS; id++) {
            int group = id / GROUP;
            int row = id;
            w.row(g -> {
                g.append("id", (long) row).append("code", codes[row])
                        .append("name", row % 3 == 0 ? "aaa" : row % 3 == 1 ? "zzz" : "k" + group)
                        .append("day", (int) (DAY + row / 10)).append("amount", row * 125)
                        .append("ts", MICROS + row * 1_000_000L).append("tsm", MILLIS + row * 1000L)
                        .append("flag", group == 9);
                if (group != 0 && (group >= 5 || row % 3 != 0)) {
                    g.append("n", row);
                }
                Group s = g.addGroup("s");
                s.append("x", row / 100).append("label", String.format("L%05d", row));
                g.append("dbl", row + 0.5).append("small", row % 100 - 50);
            });
        }
    }

    private static ColumnPath path(int column, String... fields) {
        return new ColumnPath(column, List.of(fields));
    }

    /** A filter and the number of row groups a scan under it reads. */
    private record Case(String name, Expression filter, int rowGroups) {
    }

    private static List<Case> cases() {
        return List.of(
                new Case("id = 42", cmp(ComparisonOp.EQ, col(0), lit(DataType.INTEGER, 42)), 1),
                new Case("42 = id", cmp(ComparisonOp.EQ, lit(DataType.BIGINT, 42L), col(0)), 1),
                new Case("id BETWEEN 2950 AND 3050", new Expression.Between(col(0), lit(DataType.INTEGER, 2950),
                        lit(DataType.INTEGER, 3050)), 2),
                new Case("id NOT BETWEEN 1000 AND 8999", new Expression.Not(new Expression.Between(col(0),
                        lit(DataType.INTEGER, 1000), lit(DataType.INTEGER, 8999))), 2),
                new Case("id IN (5, 9999, 7.5)", new Expression.In(col(0), List.of(lit(DataType.INTEGER, 5),
                        lit(DataType.INTEGER, 9999), lit(DataType.decimal(2, 1), new BigDecimal("7.5")))), 2),
                new Case("id IN (5, NULL)", new Expression.In(col(0), List.of(lit(DataType.INTEGER, 5),
                        lit(DataType.INTEGER, null))), 1),
                new Case("id NOT IN (5)", new Expression.Not(new Expression.In(col(0),
                        List.of(lit(DataType.INTEGER, 5)))), 10),
                new Case("id IS NOT DISTINCT FROM 42", new Expression.IsDistinct(col(0),
                        lit(DataType.BIGINT, 42L), true), 1),
                new Case("id IS DISTINCT FROM 42", new Expression.IsDistinct(col(0),
                        lit(DataType.BIGINT, 42L), false), 10),
                new Case("id <> 5", cmp(ComparisonOp.NE, col(0), lit(DataType.INTEGER, 5)), 10),
                new Case("id < 41.5", cmp(ComparisonOp.LT, col(0), lit(DataType.decimal(3, 1),
                        new BigDecimal("41.5"))), 1),
                new Case("id = 42.5", cmp(ComparisonOp.EQ, col(0), lit(DataType.decimal(3, 1),
                        new BigDecimal("42.5"))), 10),
                new Case("id > 10^30", cmp(ComparisonOp.GT, col(0), lit(DataType.HUGEINT, BigInteger.TEN.pow(30))),
                        10),
                new Case("NOT (id < 9000)", new Expression.Not(cmp(ComparisonOp.LT, col(0),
                        lit(DataType.INTEGER, 9000))), 1),
                new Case("id < 1000 OR id >= 9000", new Expression.Or(cmp(ComparisonOp.LT, col(0),
                        lit(DataType.INTEGER, 1000)), cmp(ComparisonOp.GE, col(0), lit(DataType.INTEGER, 9000))), 2),
                new Case("NOT (id >= 1000 AND id < 9000)", new Expression.Not(new Expression.And(
                        cmp(ComparisonOp.GE, col(0), lit(DataType.INTEGER, 1000)),
                        cmp(ComparisonOp.LT, col(0), lit(DataType.INTEGER, 9000)))), 2),
                new Case("code = the code of row 3500", cmp(ComparisonOp.EQ, col(1),
                        lit(DataType.BIGINT, codes[3500])), 1),
                new Case("name = 'k3'", cmp(ComparisonOp.EQ, col(2), lit(DataType.VARCHAR, "k3")), 1),
                new Case("name IN ('k3', 'k7')", new Expression.In(col(2), List.of(lit(DataType.VARCHAR, "k3"),
                        lit(DataType.VARCHAR, "k7"))), 2),
                new Case("name >= 'k'", cmp(ComparisonOp.GE, col(2), lit(DataType.VARCHAR, "k")), 10),
                new Case("day > DATE 18899", cmp(ComparisonOp.GT, col(3), lit(DataType.DATE, 18_899)), 1),
                new Case("day >= TIMESTAMP noon of 18900", cmp(ComparisonOp.GE, col(3),
                        lit(DataType.TIMESTAMP, 18_900 * 86_400_000_000L + 43_200_000_000L)), 1),
                new Case("amount > 11248.751", cmp(ComparisonOp.GT, col(4), lit(DataType.decimal(8, 3),
                        new BigDecimal("11248.751"))), 1),
                new Case("amount <= 1248.75", cmp(ComparisonOp.LE, col(4), lit(DataType.decimal(6, 2),
                        new BigDecimal("1248.75"))), 1),
                // ts is adjusted to UTC, an instant.
                new Case("ts >= 9000 seconds later", cmp(ComparisonOp.GE, col(5),
                        lit(DataType.TIMESTAMPTZ, MICROS + 9_000_000_000L)), 1),
                new Case("ts < the midnight before", cmp(ComparisonOp.LT, col(5), lit(DataType.TIMESTAMPTZ,
                        MICROS / 86_400_000_000L * 86_400_000_000L)), 0),
                new Case("tsm < 999.0005 seconds later", cmp(ComparisonOp.LT, col(6),
                        lit(DataType.TIMESTAMP, MILLIS * 1000 + 999_000_500L)), 1),
                new Case("flag", col(7), 1),
                new Case("NOT flag", new Expression.Not(col(7)), 9),
                new Case("flag = false", cmp(ComparisonOp.EQ, col(7), lit(DataType.BOOLEAN, false)), 9),
                new Case("n IS NULL", new Expression.IsNull(col(8), false), 5),
                new Case("n IS NOT NULL", new Expression.IsNull(col(8), true), 9),
                new Case("NOT (n IS NULL)", new Expression.Not(new Expression.IsNull(col(8), false)), 9),
                new Case("s.x > 95", cmp(ComparisonOp.GT, col(9), lit(DataType.INTEGER, 95)), 1),
                new Case("s.label >= 'L09000'", cmp(ComparisonOp.GE, col(10), lit(DataType.VARCHAR, "L09000")), 1),
                new Case("small = 7", cmp(ComparisonOp.EQ, col(12), lit(DataType.TINYINT, (byte) 7)), 10),
                new Case("id = 42 AND dbl > 1", new Expression.And(cmp(ComparisonOp.EQ, col(0),
                        lit(DataType.INTEGER, 42)), cmp(ComparisonOp.GT, col(11), lit(DataType.DOUBLE, 1.0))), 1),
                new Case("id = 42 OR dbl > 9000", new Expression.Or(cmp(ComparisonOp.EQ, col(0),
                        lit(DataType.INTEGER, 42)), cmp(ComparisonOp.GT, col(11), lit(DataType.DOUBLE, 9000.0))), 10),
                new Case("dbl > 9000", cmp(ComparisonOp.GT, col(11), lit(DataType.DOUBLE, 9000.0)), 10),
                new Case("CAST(id AS DOUBLE) > 9000", cmp(ComparisonOp.GT, new Expression.Cast(col(0),
                        DataType.DOUBLE), lit(DataType.DOUBLE, 9000.0)), 10),
                new Case("id > 9000.0 as a DOUBLE", cmp(ComparisonOp.GT, col(0), lit(DataType.DOUBLE, 9000.0)), 10),
                new Case("id + 1 > 9000", cmp(ComparisonOp.GT, new Expression.Arithmetic(Expression.ArithmeticOp.ADD,
                        col(0), lit(DataType.BIGINT, 1L)), lit(DataType.BIGINT, 9000L)), 10),
                new Case("id > code", cmp(ComparisonOp.GT, col(0), col(1)), 10),
                new Case("id = NULL", cmp(ComparisonOp.EQ, col(0), lit(DataType.BIGINT, null)), 10));
    }

    private static Expression.Column col(int i) {
        return new Expression.Column(i, OUTPUT.type(i));
    }

    private static Expression.Literal lit(DataType type, Object value) {
        return new Expression.Literal(type, value);
    }

    private static Expression cmp(ComparisonOp op, Expression left, Expression right) {
        return new Expression.Comparison(op, left, right);
    }

    @Test
    void theFiltersSkipRowGroupsAndNeverARow() {
        ParquetConnector c = ParquetConnector.builder("pq").path("t", bloom).batchSize(64).recordReads(true).build();
        assertEquals(10, c.splits(plan(c, List.of())).size());
        List<List<Object>> all = scan(c, List.of());
        assertEquals(ROWS, all.size());
        List<String> failures = new ArrayList<>();
        for (Case k : cases()) {
            c.clearReads();
            List<List<Object>> read = scan(c, List.of(k.filter()));
            List<ParquetConnector.Read> reads = c.reads();
            if (reads.size() != k.rowGroups()) {
                failures.add(k.name() + ": " + reads.size() + " row groups read instead of " + k.rowGroups());
            }
            List<List<Object>> expected = passing(all, k.filter());
            List<List<Object>> actual = passing(read, k.filter());
            if (!expected.equals(actual)) {
                failures.add(k.name() + ": " + actual.size() + " rows pass instead of " + expected.size());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void withoutABloomFilterTheRandomCodesSkipNothing() {
        ParquetConnector c = ParquetConnector.builder("pq").path("t", plain).recordReads(true).build();
        Expression filter = cmp(ComparisonOp.EQ, col(1), lit(DataType.BIGINT, codes[3500]));
        List<List<Object>> read = passing(scan(c, List.of(filter)), filter);
        assertEquals(10, c.reads().size());
        assertEquals(1, read.size());
        assertEquals(3500L, read.get(0).get(0));
    }

    @Test
    void theColumnIndexSkipsPagesInStepAcrossColumns() {
        for (int batchSize : new int[] {1, 7, 2048}) {
            ParquetConnector c = ParquetConnector.builder("pq").path("t", bloom).batchSize(batchSize)
                    .recordReads(true).build();
            Expression filter = new Expression.Between(col(0), lit(DataType.INTEGER, 4250),
                    lit(DataType.INTEGER, 4260));
            List<List<Object>> read = scan(c, List.of(filter));
            assertEquals(1, c.reads().size());
            assertEquals(4, c.reads().get(0).rowGroup());
            long rows = c.reads().get(0).rows();
            assertTrue(rows >= 11 && rows < GROUP, "rows read: " + rows);
            assertEquals(rows, read.size());
            List<List<Object>> passing = passing(read, filter);
            assertEquals(11, passing.size());
            for (int k = 0; k < passing.size(); k++) {
                List<Object> row = passing.get(k);
                long id = 4250 + k;
                assertEquals(List.of(id, codes[(int) id], id % 3 == 0 ? "aaa" : id % 3 == 1 ? "zzz" : "k4",
                        (int) (DAY + id / 10), new BigDecimal(BigInteger.valueOf(id * 125), 2),
                        MICROS + id * 1_000_000L, MILLIS * 1000 + id * 1_000_000L, false), row.subList(0, 8));
                assertEquals(Arrays.asList(id % 3 == 0 ? null : (int) id, (int) (id / 100),
                        String.format("L%05d", id), id + 0.5, (byte) (id % 100 - 50)), row.subList(8, 13));
            }
        }
    }

    @Test
    void filtersAreNotTakenOverAndKeepALimitFromBeingTakenOver() {
        ParquetConnector c = ParquetConnector.builder("pq").path("t", bloom).build();
        ScanPlan plan = c.plan(TableRef.of("pq", "t"), new ScanRequest(PATHS,
                List.of(cmp(ComparisonOp.EQ, col(0), lit(DataType.INTEGER, 42))), OptionalLong.of(5), List.of(),
                Optional.empty()));
        assertEquals(List.of(), plan.acceptedFilters());
        assertFalse(plan.limitAccepted());
        assertEquals(1, c.splits(plan).size());
    }

    private static ScanPlan plan(ParquetConnector c, List<Expression> filters) {
        return c.plan(TableRef.of("pq", "t"), new ScanRequest(PATHS, filters, OptionalLong.empty(), List.of(),
                Optional.empty()));
    }

    /** Reads the rows of the table under filters, which the scan only skips by; the values as they are. */
    private static List<List<Object>> scan(ParquetConnector c, List<Expression> filters) {
        ScanPlan plan = plan(c, filters);
        List<List<Object>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            try (BatchReader reader = c.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    rows.addAll(rows(b));
                }
            }
        }
        return rows;
    }

    private static List<List<Object>> rows(Batch b) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < b.rowCount(); i++) {
            List<Object> row = new ArrayList<>();
            for (int col = 0; col < b.schema().size(); col++) {
                row.add(b.column(col).get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Returns the rows for which a filter is {@code TRUE}, as the engine evaluates it. */
    private static List<List<Object>> passing(List<List<Object>> rows, Expression filter) {
        if (rows.isEmpty()) {
            return rows;
        }
        Batch batch = Batch.ofRows(OUTPUT, rows.stream().map(List::toArray).toList());
        List<List<Object>> result = new ArrayList<>();
        for (int i : Evaluator.select(filter, batch)) {
            result.add(rows.get(i));
        }
        return result;
    }
}
