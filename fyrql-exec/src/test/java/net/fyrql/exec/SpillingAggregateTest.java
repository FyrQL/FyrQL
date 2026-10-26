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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aggregations over the budget keep their groups, scatter their further
 * rows into partitions on disk and merge the partitions one after the
 * other, cutting those that do not fit into smaller ones: the same groups
 * in the same order and the same results as in memory, for every aggregate
 * function, also with {@code DISTINCT} and {@code ORDER BY}, on one thread
 * and on several; the budget is given back and the files are deleted, also
 * after a failure or a cancel.
 */
class SpillingAggregateTest {

    private static final Schema SCHEMA = Schema.of(new Schema.Field("k1", DataType.INTEGER),
            new Schema.Field("k2", DataType.VARCHAR), new Schema.Field("v", DataType.INTEGER),
            new Schema.Field("d", DataType.DOUBLE), new Schema.Field("s", DataType.VARCHAR),
            new Schema.Field("dec", DataType.decimal(15, 2)), new Schema.Field("b", DataType.BOOLEAN));
    private static final int K1 = 0;
    private static final int K2 = 1;
    private static final int V = 2;
    private static final int D = 3;
    private static final int S = 4;
    private static final int DEC = 5;
    private static final int B = 6;

    @TempDir
    Path temp;

    private final SplitTables source = new SplitTables();

    private static List<Object[]> rows(SplittableRandom random, int n, int many) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object d = switch (random.nextInt(6)) {
                case 0 -> -0.0;
                case 1 -> 0.0;
                case 2 -> Double.NaN;
                case 3 -> null;
                default -> (double) random.nextInt(7);
            };
            // Rare NULL keys, so that their group stays small.
            rows.add(new Object[] {random.nextInt(2000) == 0 ? null : random.nextInt(many),
                    random.nextInt(10) == 0 ? null : List.of("a", "b", "c").get(random.nextInt(3)),
                    random.nextInt(10) == 0 ? null : random.nextInt(1000) - 500, d,
                    random.nextInt(10) == 0 ? null : "s" + random.nextInt(40),
                    random.nextInt(10) == 0 ? null : BigDecimal.valueOf(random.nextInt(1_000_000) - 500_000, 2),
                    random.nextInt(10) == 0 ? null : random.nextBoolean()});
        }
        return rows;
    }

    /** Split sizes that add up to a count. */
    private static int[] splits(SplittableRandom random, int count) {
        List<Integer> sizes = new ArrayList<>();
        int left = count;
        while (left > 0) {
            int size = Math.min(left, random.nextInt(5) == 0 ? 0 : 1 + random.nextInt(400));
            sizes.add(size);
            left -= size;
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Aggregates that merge, which workers compute each for their rows, two of them filtered. */
    private static List<AggregateCall> mergeable() {
        return List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.COUNT, S, false, "cs"),
                new AggregateCall(AggregateFunction.SUM, V, false, "sv"),
                new AggregateCall(AggregateFunction.AVG, DEC, false, "adec"),
                new AggregateCall(AggregateFunction.SUM, DEC, false, "sdec"),
                new AggregateCall(AggregateFunction.MIN, D, false, "mind"),
                new AggregateCall(AggregateFunction.MAX, D, false, "maxd"),
                new AggregateCall(AggregateFunction.MAX, S, false, "maxs"),
                new AggregateCall(AggregateFunction.BOOL_AND, B, false, "band"),
                new AggregateCall(AggregateFunction.BOOL_OR, B, false, "bor"),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "nb").withFilter(B),
                new AggregateCall(AggregateFunction.SUM, V, false, "svb").withFilter(B));
    }

    /**
     * Every other aggregate: with DISTINCT, with ORDER BY, lists and those of
     * the first or last row, filtered too, and the statistics, whose doubles
     * differ in the last bits when workers compute them.
     */
    private static List<AggregateCall> others() {
        List<AggregateCall> calls = new ArrayList<>(mergeable());
        calls.add(new AggregateCall(AggregateFunction.SUM, V, true, "sdv"));
        calls.add(new AggregateCall(AggregateFunction.COUNT, S, true, "cds"));
        calls.add(new AggregateCall(AggregateFunction.STRING_AGG, S, false, "sa", "|",
                List.of(new SortKey(V, false, true), new SortKey(D, true, false))));
        calls.add(new AggregateCall(AggregateFunction.STRING_AGG, S, true, "sad", ",",
                List.of(SortKey.asc(S))));
        calls.add(new AggregateCall(AggregateFunction.LIST, V, false, "lv"));
        calls.add(new AggregateCall(AggregateFunction.LIST, D, false, "ld", null, List.of(SortKey.asc(D))));
        calls.add(new AggregateCall(AggregateFunction.FIRST, S, false, "fs"));
        calls.add(new AggregateCall(AggregateFunction.LAST, D, false, "ld2"));
        calls.add(new AggregateCall(AggregateFunction.ANY_VALUE, DEC, false, "anydec"));
        calls.add(new AggregateCall(AggregateFunction.MIN, D, false, "mind2", null, List.of(SortKey.asc(V))));
        calls.add(new AggregateCall(AggregateFunction.LIST, V, false, "lvb").withFilter(B));
        calls.add(new AggregateCall(AggregateFunction.STRING_AGG, S, true, "sadb", ",", List.of(SortKey.asc(S)))
                .withFilter(B));
        calls.add(new AggregateCall(AggregateFunction.VAR_SAMP, V, false, "varv"));
        calls.add(new AggregateCall(AggregateFunction.STDDEV_POP, DEC, false, "sddecb").withFilter(B));
        return calls;
    }

    /** Runs a plan; the budget is given back and no file is left. */
    private List<List<Object>> run(PlanNode plan, long budget, int threads, boolean preserveOrder,
            SpillDirectory spill) {
        MemoryBudget b = new MemoryBudget(budget);
        Executor executor = new Executor(source, b, 64, Codegen.off(), threads, preserveOrder, spill);
        try {
            List<List<Object>> rows = new ArrayList<>();
            for (Object[] row : executor.rows(plan)) {
                rows.add(Arrays.asList(row));
            }
            return rows;
        } finally {
            assertEquals(0, b.used(), "the budget is given back");
            assertNoFilesLeft(spill);
        }
    }

    private static void assertNoFilesLeft(SpillDirectory spill) {
        Path dir = spill == null ? null : spill.path();
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.toList(), "the files are deleted");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Object::toString));
        return copy;
    }

    @Test
    void everyAggregateOverTheBudgetGivesWhatItGivesInMemory() {
        SplittableRandom random = new SplittableRandom(20261021);
        long refined = HashAggregateOperator.REFINED.get();
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int round = 0; round < 3; round++) {
                int n = 5000 + random.nextInt(4000);
                int many = round % 2 == 0 ? 3000 : 40;
                List<Object[]> rows = rows(random, n, many);
                PlanNode.Scan scan = source.table("t" + round, SCHEMA, rows, splits(random, n));
                long total = Batches.estimateBytes(Batch.ofRows(SCHEMA, rows));
                List<Integer> keys = switch (round % 3) {
                    case 0 -> List.of(K1);
                    case 1 -> List.of(K1, K2);
                    default -> List.of(K2, K1);
                };
                for (List<AggregateCall> calls : List.of(mergeable(), others())) {
                    PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, keys, calls);
                    List<List<Object>> expected = run(aggregate, Long.MAX_VALUE, 1, true, null);
                    // A group of lists of hundreds of values takes tens of kilobytes, twice while it is merged, and
                    // the merge of the results two of them.
                    boolean lists = calls.size() > mergeable().size();
                    long[] budgets = !lists ? new long[] {total / 4, total / 16, Math.max(total / 64, 16 * 1024)}
                            : many < 100 ? new long[] {total, total / 2}
                            : new long[] {total / 2, total / 4, 96 * 1024};
                    for (long budget : budgets) {
                        for (int threads : new int[] {1, 12}) {
                            String what = "round " + round + ", " + calls.size() + " aggregates, budget " + budget
                                    + ", " + threads + " threads";
                            long parts = spill.statistics().parts();
                            List<List<Object>> actual;
                            try {
                                actual = run(aggregate, budget, threads, true, spill);
                            } catch (QueryException e) {
                                throw new AssertionError(what, e);
                            }
                            assertEquals(expected, actual, what);
                            if (many > 100 || lists) {
                                // Few small groups fit.
                                assertTrue(spill.statistics().parts() > parts, what + ": rows went to disk");
                            }
                        }
                    }
                    // In any order, the same groups.
                    assertEquals(sorted(expected), sorted(run(aggregate, budgets[1], 12, false, spill)),
                            "round " + round + ", order not preserved");
                }
            }
        }
        assertTrue(HashAggregateOperator.REFINED.get() > refined, "partitions that did not fit were cut");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aDistinctProjectionOverTheBudgetSpillsToo() {
        SplittableRandom random = new SplittableRandom(3);
        List<Object[]> rows = rows(random, 8000, 2000);
        PlanNode.Scan scan = source.table("distinct", SCHEMA, rows, splits(random, rows.size()));
        PlanNode.Aggregate distinct = new PlanNode.Aggregate(scan, List.of(K1, S), List.of());
        List<List<Object>> expected = run(distinct, Long.MAX_VALUE, 1, true, null);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int threads : new int[] {1, 12}) {
                assertEquals(expected, run(distinct, 32 * 1024, threads, true, spill));
            }
            assertTrue(spill.statistics().parts() > 0);
        }
    }

    @Test
    void aGroupThatDoesNotFitFailsAfterSpilling() {
        SplittableRandom random = new SplittableRandom(4);
        List<Object[]> rows = rows(random, 20_000, 1);
        for (Object[] row : rows) {
            row[K1] = 1;
        }
        PlanNode.Scan scan = source.table("one", SCHEMA, rows, 5000, 5000, 5000, 5000);
        PlanNode.Aggregate all = new PlanNode.Aggregate(scan, List.of(K1), List.of(
                new AggregateCall(AggregateFunction.LIST, S, false, "l")));
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            long refined = HashAggregateOperator.REFINED.get();
            QueryException e = assertThrows(QueryException.class, () -> run(all, 64 * 1024, 1, true, spill));
            assertTrue(e.getMessage().matches("Memory budget of 65536 bytes exceeded: \\d+ bytes needed after"
                    + " spilling \\d+ bytes in Aggregate#0"), e::getMessage);
            assertEquals(3, HashAggregateOperator.REFINED.get() - refined, "cut down to 65536 partitions");
        }
        QueryException off = assertThrows(QueryException.class, () -> run(all, 64 * 1024, 1, true, null));
        assertTrue(off.getMessage().matches("Memory budget of 65536 bytes exceeded: \\d+ bytes needed in"
                + " Aggregate#0"), off::getMessage);
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aCanceledAggregationDeletesItsFiles() {
        SplittableRandom random = new SplittableRandom(5);
        List<Batch> batches = Batches.split(Batch.ofRows(SCHEMA, rows(random, 8000, 4000)), 500);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(new PlanNode.Values(SCHEMA, List.of()), List.of(K1),
                others());
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int cancelAt : new int[] {8, 16}) {
                MemoryBudget budget = new MemoryBudget(32 * 1024);
                QueryContext context = new QueryContext();
                Operator input = new Operator() {
                    private int calls;

                    @Override
                    public Schema schema() {
                        return SCHEMA;
                    }

                    @Override
                    public Batch next() {
                        if (calls++ == cancelAt) {
                            context.cancel();
                        }
                        return calls <= batches.size() ? batches.get(calls - 1) : null;
                    }

                    @Override
                    public void close() {
                    }
                };
                HashAggregateOperator op = new HashAggregateOperator("Aggregate#1", aggregate, input, budget, 64,
                        Codegen.off(), 1, true, spill);
                op.attach(context);
                try (op) {
                    assertThrows(QueryCancelledException.class, op::next);
                }
                assertEquals(0, budget.used(), "the budget is given back");
                assertNoFilesLeft(spill);
            }
            assertNotNull(spill.path());
        }
    }
}
