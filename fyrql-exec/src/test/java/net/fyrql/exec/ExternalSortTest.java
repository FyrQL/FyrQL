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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sorts over the budget spill sorted runs to disk and merge them: the same
 * rows in the same order as the sort in memory, bit for bit, also among
 * equal keys, on one thread and on several, for every budget from one in
 * which no two runs fit on; the budget is given back and the runs are
 * deleted, also after a failure or a cancel.
 */
class ExternalSortTest {

    private static final int FEW = 0;

    @TempDir
    Path temp;

    private final SplitTables source = new SplitTables();

    /** A key of few values, then a column of every type. */
    private static Schema schema() {
        List<Schema.Field> fields = new ArrayList<>();
        fields.add(new Schema.Field("few", DataType.INTEGER));
        List<DataType> types = new ArrayList<>(List.of(DataType.Scalar.values()));
        types.add(DataType.decimal(10, 2));
        types.add(DataType.decimal(38, 10));
        types.add(DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("b", DataType.listOf(DataType.VARCHAR))));
        types.add(DataType.listOf(DataType.decimal(5, 1)));
        types.add(DataType.mapOf(DataType.VARCHAR, DataType.INTEGER));
        for (DataType t : types) {
            fields.add(new Schema.Field(t.sqlName(), t));
        }
        return new Schema(fields);
    }

    private static int index(Schema schema, DataType type) {
        return schema.types().indexOf(type);
    }

    private static List<Object[]> rows(Schema schema, int n, long seed) {
        Schema rest = new Schema(schema.fields().subList(1, schema.size()));
        SplittableRandom random = new SplittableRandom(seed);
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : DataGenerator.withSeed(seed).generate(rest, n)) {
            Object[] row = new Object[schema.size()];
            row[FEW] = random.nextInt(10) == 0 ? null : random.nextInt(4);
            System.arraycopy(r, 0, row, 1, r.length);
            rows.add(row);
        }
        return rows;
    }

    /** Split sizes that add up to a count: some empty, the rest random. */
    private static int[] splits(SplittableRandom random, int count) {
        List<Integer> sizes = new ArrayList<>();
        int left = count;
        int most = Math.max(1, count / 8);
        while (left > 0) {
            int size = Math.min(left, random.nextInt(5) == 0 ? 0 : 1 + random.nextInt(most));
            sizes.add(size);
            left -= size;
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Runs a plan; the budget is given back and no file of a run is left. */
    private List<Object[]> run(PlanNode plan, long budget, int threads, boolean preserveOrder,
            SpillDirectory spill) {
        MemoryBudget b = new MemoryBudget(budget);
        Executor executor = new Executor(source, b, 64, Codegen.off(), threads, preserveOrder, spill);
        try {
            return executor.rows(plan);
        } finally {
            assertEquals(0, b.used(), "the budget is given back");
            assertNoRunsLeft(spill);
        }
    }

    private static void assertNoRunsLeft(SpillDirectory spill) {
        Path dir = spill == null ? null : spill.path();
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.toList(), "the runs are deleted");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertSameRows(List<Object[]> expected, List<Object[]> actual, String what) {
        assertEquals(expected.size(), actual.size(), what);
        for (int i = 0; i < expected.size(); i++) {
            int row = i;
            // Deep equality tells -0.0 from 0.0 and compares the bytes of a BLOB.
            assertTrue(Arrays.deepEquals(expected.get(i), actual.get(i)), () -> what + ", row " + row + ": "
                    + Arrays.deepToString(expected.get(row)) + " but " + Arrays.deepToString(actual.get(row)));
        }
    }

    @Test
    void severalBatchesSortAsTheirConcatenation() {
        Schema schema = schema();
        SplittableRandom random = new SplittableRandom(1);
        List<Batch> parts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            parts.add(Batch.ofRows(schema, rows(schema, i == 3 ? 0 : random.nextInt(300), i)));
        }
        Batch all = Batches.concat(schema, parts);
        for (List<SortKey> keys : List.of(List.of(SortKey.asc(FEW)), List.of(new SortKey(FEW, false, false),
                new SortKey(schema.indexOf("VARCHAR"), true, false), new SortKey(schema.indexOf("DOUBLE"), false,
                        true)), List.<SortKey>of())) {
            int[] one = new RowOrder(all, keys).sort(() -> {
            });
            RowOrder several = new RowOrder(parts, keys);
            int[] sorted = several.sort(() -> {
            }, 3);
            int[] mapped = new int[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                int at = 0;
                for (int p = 0; p < several.part(sorted[i]); p++) {
                    at += parts.get(p).rowCount();
                }
                mapped[i] = at + several.row(sorted[i]);
            }
            assertArrayEquals(one, mapped, keys.toString());
        }
    }

    @Test
    void sortsOverTheBudgetGiveWhatTheSortInMemoryGives() {
        Schema schema = schema();
        SplittableRandom random = new SplittableRandom(20261021);
        int n = 3000;
        List<Object[]> rows = rows(schema, n, 7);
        long total = Batches.estimateBytes(Batch.ofRows(schema, rows));
        PlanNode.Scan scan = source.table("sort", schema, rows, splits(random, n));
        List<PlanNode.Sort> sorts = List.of(
                new PlanNode.Sort(scan, List.of(SortKey.asc(FEW))),
                new PlanNode.Sort(scan, List.of(new SortKey(FEW, false, false),
                        new SortKey(schema.indexOf("DOUBLE"), true, true))),
                new PlanNode.Sort(scan, List.of(new SortKey(schema.indexOf("VARCHAR"), false, true),
                        SortKey.asc(index(schema, DataType.decimal(38, 10))))));
        SpillDirectory spill = new SpillDirectory(temp.resolve("spill"));
        Path dir;
        try (spill) {
            for (PlanNode.Sort sort : sorts) {
                List<Object[]> expected = run(sort, Long.MAX_VALUE, 1, true, null);
                assertEquals(n, expected.size());
                for (long budget : new long[] {total * 4, total / 3, total / 6, total / 10}) {
                    for (int threads : new int[] {1, 12}) {
                        for (boolean preserveOrder : new boolean[] {true, false}) {
                            String what = sort.keys() + ", budget " + budget + ", " + threads + " threads"
                                    + (preserveOrder ? "" : ", order not preserved");
                            long parts = spill.statistics().parts();
                            assertSameRows(expected, run(sort, budget, threads, preserveOrder, spill), what);
                            assertEquals(budget < total, spill.statistics().parts() > parts, what
                                    + ": runs went to disk");
                        }
                    }
                }
            }
            dir = spill.path();
            assertTrue(spill.statistics().bytesRead() > 0);
            assertEquals(spill.statistics().bytesWritten(), spill.statistics().bytesRead(),
                    "every run is read once");
        }
        assertFalse(Files.exists(dir), "the directory goes when the engine closes");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void runsThatDoNotFitAtOnceAreMergedInGroupsFirst() {
        Schema schema = schema();
        SplittableRandom random = new SplittableRandom(5);
        int n = 2000;
        List<Object[]> rows = rows(schema, n, 11);
        long total = Batches.estimateBytes(Batch.ofRows(schema, rows));
        PlanNode.Scan scan = source.table("cascade", schema, rows, splits(random, n));
        PlanNode.Sort sort = new PlanNode.Sort(scan, List.of(SortKey.asc(FEW),
                new SortKey(schema.indexOf("BIGINT"), false, true)));
        List<Object[]> expected = run(sort, Long.MAX_VALUE, 1, true, null);
        int before = ExternalSort.fanIn;
        long[] written = new long[65];
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int fanIn : new int[] {64, 2, 3}) {
                ExternalSort.fanIn = fanIn;
                for (int threads : new int[] {1, 12}) {
                    long parts = spill.statistics().parts();
                    long bytes = spill.statistics().bytesWritten();
                    assertSameRows(expected, run(sort, total / 4, threads, true, spill), "fan-in " + fanIn
                            + ", " + threads + " threads");
                    assertTrue(spill.statistics().parts() > parts);
                    if (threads == 1) {
                        written[fanIn] = spill.statistics().bytesWritten() - bytes;
                    }
                }
            }
        } finally {
            ExternalSort.fanIn = before;
        }
        // All runs of one thread fit at once; two at a time, the merged runs go to disk once more.
        assertTrue(written[2] > written[64], () -> written[2] + " bytes, " + written[64] + " bytes at once");
    }

    @Test
    void aBudgetInWhichNoTwoRunsFitFailsAfterSpilling() {
        Schema schema = schema();
        List<Object[]> rows = rows(schema, 500, 3);
        PlanNode.Scan scan = source.table("tiny", schema, rows, 100, 100, 100, 100, 100);
        PlanNode.Sort sort = new PlanNode.Sort(scan, List.of(SortKey.asc(FEW)));
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int threads : new int[] {1, 12}) {
                QueryException e = assertThrows(QueryException.class, () -> run(sort, 1, threads, true, spill));
                assertTrue(e.getMessage().matches("Memory budget of 1 bytes exceeded: \\d+ bytes needed after"
                        + " spilling \\d+ bytes in Sort#0"), e::getMessage);
                assertEquals("Sort#0", e.operator());
            }
            QueryException off = assertThrows(QueryException.class, () -> run(sort, 1, 1, true, null));
            assertTrue(off.getMessage().matches("Memory budget of 1 bytes exceeded: \\d+ bytes needed in Sort#0"),
                    off::getMessage);
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aCanceledMergeDeletesItsRuns() {
        Schema schema = schema();
        int n = 2000;
        List<Object[]> rows = rows(schema, n, 13);
        long total = Batches.estimateBytes(Batch.ofRows(schema, rows));
        PlanNode.Scan scan = source.table("cancel", schema, rows, 500, 500, 500, 500);
        PlanNode.Sort sort = new PlanNode.Sort(scan, List.of(SortKey.asc(FEW)));
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int threads : new int[] {1, 4}) {
                MemoryBudget budget = new MemoryBudget(total / 5);
                Executor executor = new Executor(source, budget, 64, Codegen.off(), threads, true, spill);
                QueryContext context = new QueryContext();
                try (Operator op = executor.open(sort, node -> Double.NaN, context)) {
                    assertNotNull(op.next());
                    assertTrue(context.spill().parts() > 0, "the sort spilled");
                    context.cancel();
                    assertThrows(QueryCancelledException.class, op::next);
                }
                assertEquals(0, budget.used(), "the budget is given back");
                assertNoRunsLeft(spill);
            }
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }
}
