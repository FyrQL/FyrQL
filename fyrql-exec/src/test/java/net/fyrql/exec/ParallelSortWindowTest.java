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
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
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

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sorts and windows on several threads, against one thread: the same rows
 * in the same order, bit for bit, also among equal keys and for
 * {@code -0.0} and {@code 0.0}, the same failure, that of the first
 * partition that fails, and a canceled query stops on the threads.
 */
class ParallelSortWindowTest {

    /** A unique id, a key of few values, a key of many, a double, a string, a bucket count, a flag. */
    private static final Schema SCHEMA = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("few", DataType.INTEGER), new Schema.Field("many", DataType.BIGINT),
            new Schema.Field("d", DataType.DOUBLE), new Schema.Field("s", DataType.VARCHAR),
            new Schema.Field("buckets", DataType.INTEGER), new Schema.Field("b", DataType.BOOLEAN));
    private static final int ID = 0;
    private static final int FEW = 1;
    private static final int MANY = 2;
    private static final int D = 3;
    private static final int S = 4;
    private static final int BUCKETS = 5;
    private static final int B = 6;

    private final SplitTables source = new SplitTables();

    @TempDir
    Path temp;

    /** Rows with keys of few and of many values, {@code NULL}s, zeros of both signs and NaNs. */
    private static List<Object[]> rows(SplittableRandom random, int n, boolean zeroBuckets) {
        int many = 1 + random.nextInt(Math.max(1, n / 4));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object d = switch (random.nextInt(6)) {
                case 0 -> -0.0;
                case 1 -> 0.0;
                case 2 -> Double.NaN;
                case 3 -> null;
                default -> (double) random.nextInt(5);
            };
            rows.add(new Object[] {i, random.nextInt(10) == 0 ? null : random.nextInt(4),
                    random.nextInt(20) == 0 ? null : (long) random.nextInt(many), d,
                    random.nextInt(10) == 0 ? null : List.of("a", "b", "c").get(random.nextInt(3)),
                    random.nextInt(10) == 0 ? null : (zeroBuckets ? 0 : 1) + random.nextInt(5),
                    random.nextInt(5) == 0 ? null : random.nextBoolean()});
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

    /** Runs a plan and returns its rows, or the error it fails with; the budget gets everything back. */
    private Object run(PlanNode plan, int threads, boolean preserveOrder) {
        return run(plan, threads, preserveOrder, 1L << 40, null);
    }

    /**
     * Runs a plan as {@link #run(PlanNode, int, boolean)} does, with a
     * budget and a directory to spill to; no file is left.
     */
    private Object run(PlanNode plan, int threads, boolean preserveOrder, long limit, SpillDirectory spill) {
        MemoryBudget budget = new MemoryBudget(limit);
        Executor executor = new Executor(source, budget, 1000, Codegen.off(), threads, preserveOrder, spill);
        List<List<Object>> rows = new ArrayList<>();
        try {
            for (Object[] row : executor.rows(plan)) {
                rows.add(Arrays.asList(row));
            }
        } catch (RuntimeException e) {
            return e;
        } finally {
            assertEquals(0, budget.used(), "the budget is given back");
            Path dir = spill == null ? null : spill.path();
            if (dir != null) {
                try (Stream<Path> files = Files.list(dir)) {
                    assertEquals(List.of(), files.toList(), "the files are deleted");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return rows;
    }

    @Test
    void windowsOverTheBudgetGiveWhatTheyGiveInMemory() {
        SplittableRandom random = new SplittableRandom(20261021);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int round = 0; round < 6; round++) {
                int n = 6000 + random.nextInt(6000);
                List<Object[]> rows = rows(random, n, round % 3 == 0);
                for (Object[] row : rows) {
                    // Partitions of about five rows.
                    row[MANY] = (long) random.nextInt(n / 5);
                }
                PlanNode.Scan scan = source.table("spill" + round, SCHEMA, rows, splits(random, n));
                long total = Batches.estimateBytes(Batch.ofRows(SCHEMA, rows));
                // Many small partitions, by a key of many values or the unique id.
                List<Integer> partition = round % 2 == 0 ? List.of(MANY) : List.of(ID, FEW);
                List<SortKey> order = new ArrayList<>();
                if (random.nextInt(4) > 0) {
                    order.add(new SortKey(random.nextBoolean() ? D : FEW, random.nextBoolean(),
                            random.nextBoolean()));
                }
                PlanNode.Window window = new PlanNode.Window(scan, partition, order,
                        calls(random, order, round % 3 == 0));
                String what = "round " + round + ", partition " + partition + ", order " + order;
                Object expected = run(window, 1, true);
                for (int threads : new int[] {1, 12}) {
                    long parts = spill.statistics().parts();
                    assertSame(expected, run(window, threads, true, total / 8, spill), what + ", " + threads
                            + " threads");
                    assertTrue(spill.statistics().parts() > parts, what + ": the input went to disk");
                }
            }
            // Without a partition, all rows are one, which does not fit.
            List<Object[]> rows = rows(random, 5000, false);
            PlanNode.Scan scan = source.table("one", SCHEMA, rows, splits(random, rows.size()));
            PlanNode.Window all = new PlanNode.Window(scan, List.of(), List.of(SortKey.asc(ID)),
                    List.of(WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn")));
            long total = Batches.estimateBytes(Batch.ofRows(SCHEMA, rows));
            Object failed = run(all, 4, true, total / 8, spill);
            assertTrue(failed instanceof QueryException e && e.getMessage().matches("Memory budget of \\d+ bytes"
                    + " exceeded: \\d+ bytes needed after spilling \\d+ bytes in Window#0"), String.valueOf(failed));
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void manyRunsLeaveTheWindowHalfOfTheBudget() {
        SplittableRandom random = new SplittableRandom(20261026);
        int n = 20_000;
        List<Object[]> rows = rows(random, n, false);
        for (Object[] row : rows) {
            // Partitions of about five rows.
            row[MANY] = (long) random.nextInt(n / 5);
        }
        PlanNode.Scan scan = source.table("runs", SCHEMA, rows, n);
        long total = Batches.estimateBytes(Batch.ofRows(SCHEMA, rows));
        PlanNode.Window window = new PlanNode.Window(scan, List.of(MANY), List.of(SortKey.asc(ID)),
                List.of(WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn"),
                        WindowCall.of(WindowFunction.LEAD, List.of(S), "lead")));
        Object expected = run(window, 1, true);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            // So small that the batches of the runs, one of each, would take nearly all of it.
            for (int part = 40; part <= 64; part += 4) {
                long parts = spill.statistics().parts();
                assertSame(expected, run(window, 1, true, total / part, spill), "budget " + total / part);
                assertTrue(spill.statistics().parts() - parts > 64, "more runs than are merged at once");
            }
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void resultsOverTheBudgetFailAfterSpilling() {
        SplittableRandom random = new SplittableRandom(20261025);
        int n = 6000;
        List<Object[]> rows = rows(random, n, false);
        for (Object[] row : rows) {
            // Partitions of about 60 rows, whose rows fit in the budget but not the lists of their frames.
            row[MANY] = (long) random.nextInt(n / 60);
        }
        PlanNode.Scan scan = source.table("lists", SCHEMA, rows, splits(random, n));
        long total = Batches.estimateBytes(Batch.ofRows(SCHEMA, rows));
        Frame growing = new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.UNBOUNDED_PRECEDING),
                FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS);
        PlanNode.Window counts = new PlanNode.Window(scan, List.of(MANY), List.of(SortKey.asc(ID)),
                List.of(WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_star"),
                        growing)));
        PlanNode.Window window = new PlanNode.Window(scan, List.of(MANY), List.of(SortKey.asc(ID)),
                List.of(WindowCall.of(new AggregateCall(AggregateFunction.LIST, S, false, "list", null,
                        List.of(SortKey.asc(S))), growing)));
        assertTrue(run(window, 1, true) instanceof List<?>, "the window runs in memory");
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (int threads : new int[] {1, 4}) {
                long parts = spill.statistics().parts();
                Object counted = run(counts, threads, true, total / 8, spill);
                assertTrue(counted instanceof List<?>, "the counts of the frames fit: " + counted);
                assertTrue(spill.statistics().parts() > parts, "the input went to disk");
                Object failed = run(window, threads, true, total / 8, spill);
                assertTrue(failed instanceof QueryException e && e.getMessage().matches("Memory budget of \\d+"
                        + " bytes exceeded: \\d+ bytes needed after spilling \\d+ bytes in Window#0"),
                        String.valueOf(failed));
            }
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    private static void assertSame(Object expected, Object actual, String what) {
        if (expected instanceof RuntimeException e) {
            if (!(actual instanceof RuntimeException a)) {
                fail(what + ": one thread fails with " + e + ", several threads give rows");
                return;
            }
            assertEquals(e.getClass(), a.getClass(), what);
            assertEquals(e.getMessage(), a.getMessage(), what);
        } else if (!expected.equals(actual)) {
            // Lists of rows compare -0.0 and 0.0 as different and NaN as equal to itself.
            fail(what + ": " + (actual instanceof RuntimeException a ? "several threads fail with " + a
                    : "the rows differ"));
        }
    }

    @Test
    void anIndexSortsAsOnOneThreadOnAnyNumberOfThreads() {
        SplittableRandom random = new SplittableRandom(20261018);
        for (int n : new int[] {RowOrder.PARALLEL_ROWS - 1, RowOrder.PARALLEL_ROWS, RowOrder.PARALLEL_ROWS + 1,
                20_000, 50_001, 120_000}) {
            Batch batch = Batch.ofRows(SCHEMA, rows(random, n, false));
            for (int round = 0; round < 4; round++) {
                List<SortKey> keys = new ArrayList<>();
                for (int c : new int[] {FEW, D, MANY, S}) {
                    if (random.nextInt(3) > 0 || round == 0 && c == FEW) {
                        keys.add(new SortKey(c, random.nextBoolean(), random.nextBoolean()));
                    }
                }
                if (round == 3) {
                    // Few distinct keys, so that the runs hold long ranges of equal rows.
                    keys = List.of(SortKey.asc(FEW));
                }
                RowOrder order = new RowOrder(batch, keys);
                int[] one = order.sort(() -> {
                });
                for (int threads : new int[] {2, 3, 5, 8, 12}) {
                    assertArrayEquals(one, order.sort(() -> {
                    }, threads), n + " rows, keys " + keys + ", " + threads + " threads");
                }
            }
        }
    }

    @Test
    void sortsGiveWhatOneThreadGives() {
        SplittableRandom random = new SplittableRandom(18);
        for (int round = 0; round < 24; round++) {
            int n = round % 3 == 0 ? random.nextInt(200) : 15_000 + random.nextInt(25_000);
            List<Object[]> rows = rows(random, n, false);
            PlanNode.Scan scan = source.table("sort" + round, SCHEMA, rows, splits(random, n));
            List<SortKey> keys = new ArrayList<>();
            for (int c : new int[] {FEW, D, S, MANY}) {
                if (random.nextBoolean()) {
                    keys.add(new SortKey(c, random.nextBoolean(), random.nextBoolean()));
                }
            }
            if (keys.isEmpty()) {
                keys.add(SortKey.asc(FEW));
            }
            PlanNode input = random.nextBoolean() ? scan : new PlanNode.Filter(scan, gt(col(SCHEMA, MANY), lit(2L)));
            PlanNode.Sort sort = new PlanNode.Sort(input, keys);
            Object expected = run(sort, 1, true);
            assertTrue(expected instanceof List<?>, "round " + round + ": " + expected);
            for (int threads : new int[] {2, 5, 12}) {
                assertSame(expected, run(sort, threads, true), "round " + round + ", " + threads + " threads");
            }
            // The order of the input does not matter to a sort, but that of equal rows does.
            assertSame(expected, run(sort, 4, false), "round " + round + ", order not preserved");
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void windowsGiveWhatOneThreadGives() {
        SplittableRandom random = new SplittableRandom(20261018);
        int failures = 0;
        for (int round = 0; round < 40; round++) {
            int n = round % 4 == 0 ? random.nextInt(300) : 10_000 + random.nextInt(20_000);
            boolean zeroBuckets = random.nextInt(6) == 0;
            List<Object[]> rows = rows(random, n, zeroBuckets);
            PlanNode.Scan scan = source.table("window" + round, SCHEMA, rows, splits(random, n));
            List<Integer> partition = switch (random.nextInt(4)) {
                case 0 -> List.of();
                case 1 -> List.of(FEW);
                case 2 -> List.of(MANY);
                default -> List.of(FEW, S);
            };
            List<SortKey> order = new ArrayList<>();
            int orderColumn = random.nextBoolean() ? MANY : FEW;
            if (random.nextInt(4) > 0) {
                order.add(new SortKey(orderColumn, random.nextBoolean(), random.nextBoolean()));
            }
            List<WindowCall> calls = calls(random, order, zeroBuckets);
            PlanNode input = random.nextBoolean() ? scan : new PlanNode.Filter(scan, gt(col(SCHEMA, ID), lit(5)));
            PlanNode.Window window = new PlanNode.Window(input, partition, order, calls);
            String what = "round " + round + ", partition " + partition + ", order " + order;
            Object expected = run(window, 1, true);
            if (expected instanceof RuntimeException) {
                failures++;
            }
            for (int threads : new int[] {2, 5, 12}) {
                assertSame(expected, run(window, threads, true), what + ", " + threads + " threads");
            }
            assertSame(expected, run(window, 4, false), what + ", order not preserved");
        }
        assertTrue(failures > 0, "no round failed");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    /** Calls of every kind over random frames; with an order key, frames of {@code RANGE} with offsets too. */
    private static List<WindowCall> calls(SplittableRandom random, List<SortKey> order, boolean zeroBuckets) {
        List<WindowCall> calls = new ArrayList<>();
        calls.add(WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn"));
        calls.add(WindowCall.of(WindowFunction.RANK, List.of(), "rank"));
        calls.add(WindowCall.of(WindowFunction.DENSE_RANK, List.of(), "dense_rank"));
        calls.add(WindowCall.of(WindowFunction.PERCENT_RANK, List.of(), "percent_rank"));
        calls.add(WindowCall.of(WindowFunction.CUME_DIST, List.of(), "cume_dist"));
        if (zeroBuckets || random.nextBoolean()) {
            calls.add(WindowCall.of(WindowFunction.NTILE, List.of(BUCKETS), "ntile"));
        }
        calls.add(WindowCall.of(WindowFunction.LAG, List.of(D, BUCKETS, D), "lag"));
        calls.add(WindowCall.of(WindowFunction.LEAD, List.of(S), "lead"));
        calls.add(new WindowCall(WindowFunction.LAG, null, List.of(D), true, -1, Frame.DEFAULT, "lag_ignoring"));
        Frame frame = frame(random, order);
        calls.add(new WindowCall(WindowFunction.FIRST_VALUE, null, List.of(D), false, -1, frame, "first_value"));
        calls.add(new WindowCall(WindowFunction.LAST_VALUE, null, List.of(S), random.nextBoolean(), -1, frame,
                "last_value"));
        calls.add(new WindowCall(WindowFunction.NTH_VALUE, null, List.of(MANY, BUCKETS), false, -1, frame,
                "nth_value"));
        int filter = random.nextBoolean() ? -1 : B;
        for (AggregateFunction f : List.of(AggregateFunction.COUNT_STAR, AggregateFunction.SUM,
                AggregateFunction.MIN, AggregateFunction.MAX, AggregateFunction.AVG, AggregateFunction.FIRST)) {
            int column = f == AggregateFunction.COUNT_STAR ? -1 : random.nextBoolean() ? MANY : D;
            AggregateCall call = new AggregateCall(f, column, false, f.sqlName());
            calls.add(new WindowCall(WindowFunction.AGGREGATE, call, List.of(), false, filter,
                    frame(random, order), call.name()));
        }
        // An aggregate that keeps its values takes the rows of each frame one by one: a short frame.
        Frame around = new Frame(FrameUnit.ROWS, bound(BoundKind.PRECEDING, 3), bound(BoundKind.FOLLOWING, 2),
                FrameExclusion.values()[random.nextInt(FrameExclusion.values().length)]);
        calls.add(WindowCall.of(new AggregateCall(AggregateFunction.STRING_AGG, S, false, "string_agg"), around));
        return calls;
    }

    /** A random frame; {@code RANGE} with offsets only over one order key. */
    private static Frame frame(SplittableRandom random, List<SortKey> order) {
        BoundKind[] kinds = BoundKind.values();
        FrameUnit[] units = FrameUnit.values();
        FrameExclusion[] exclusions = FrameExclusion.values();
        while (true) {
            BoundKind start = kinds[random.nextInt(kinds.length - 1)];
            BoundKind end = kinds[1 + random.nextInt(kinds.length - 1)];
            FrameUnit unit = units[random.nextInt(units.length)];
            boolean offsets = start.hasOffset() || end.hasOffset();
            if (start.ordinal() > end.ordinal() || unit == FrameUnit.RANGE && offsets && order.size() != 1) {
                continue;
            }
            int least = unit == FrameUnit.ROWS ? -3 : 0;
            FrameBound a = start.hasOffset() ? offset(unit, start, random.nextInt(least, 40)) : FrameBound.of(start);
            FrameBound b = end.hasOffset() ? offset(unit, end, random.nextInt(least, 40)) : FrameBound.of(end);
            return new Frame(unit, a, b, exclusions[random.nextInt(exclusions.length)]);
        }
    }

    private static FrameBound offset(FrameUnit unit, BoundKind kind, int offset) {
        return unit == FrameUnit.RANGE ? FrameBound.of(kind, new Expression.Literal(DataType.INTEGER, offset))
                : bound(kind, offset);
    }

    private static FrameBound bound(BoundKind kind, long offset) {
        return FrameBound.of(kind, new Expression.Literal(DataType.BIGINT, offset));
    }

    @Test
    void ofManyPartitionsThatFailTheFirstFailsTheQuery() {
        Schema s = Schema.of(new Schema.Field("p", DataType.INTEGER), new Schema.Field("o", DataType.INTEGER),
                new Schema.Field("x", DataType.INTEGER));
        SplittableRandom random = new SplittableRandom(7);
        for (int round = 0; round < 10; round++) {
            // In a partition that fails, the order key overflows when the frame adds its offset.
            int partitions = 4000;
            boolean[] failing = new boolean[partitions];
            int first = partitions;
            for (int k = 0; k < 40; k++) {
                int p = partitions / 10 + random.nextInt(partitions - partitions / 10);
                failing[p] = true;
                first = Math.min(first, p);
            }
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < partitions * 10; i++) {
                int p = random.nextInt(partitions);
                rows.add(new Object[] {p, failing[p] ? Integer.MAX_VALUE - p : random.nextInt(100), i});
            }
            PlanNode.Window window = new PlanNode.Window(new PlanNode.Values(s, rows), List.of(0),
                    List.of(SortKey.asc(1)), List.of(WindowCall.of(new AggregateCall(AggregateFunction.SUM, 2,
                            false, "sum"), new Frame(FrameUnit.RANGE, FrameBound.of(BoundKind.CURRENT_ROW),
                            FrameBound.of(BoundKind.FOLLOWING, new Expression.Literal(DataType.INTEGER, 5000)),
                            FrameExclusion.NO_OTHERS))));
            Object expected = run(window, 1, true);
            assertTrue(expected instanceof RuntimeException e
                    && e.getMessage().contains(String.valueOf(Integer.MAX_VALUE - first)), "round " + round
                    + ": " + expected);
            for (int threads : new int[] {2, 5, 12}) {
                assertSame(expected, run(window, threads, true), "round " + round + ", " + threads + " threads");
            }
        }
    }

    @Test
    void aCanceledQueryStopsOnTheThreadsOfASort() {
        PlanNode.Values values = numbers(200_000);
        PlanNode.Sort sort = new PlanNode.Sort(values, List.of(SortKey.desc(0)));
        QueryContext context = new QueryContext();
        SortOperator sorter = new SortOperator("Sort#0", sort, cancelingAtTheEnd(values, context),
                MemoryBudget.unlimited(), 1024, 8);
        sorter.attach(context);
        QueryCancelledException e = assertThrows(QueryCancelledException.class, sorter::next);
        assertEquals("Sort#0", e.operator());
        sorter.close();
    }

    @Test
    void aCanceledQueryStopsOnTheThreadsOfAWindow() {
        PlanNode.Values values = numbers(200_000);
        PlanNode.Window w = new PlanNode.Window(values, List.of(0), List.of(), List.of(WindowCall.of(
                WindowFunction.ROW_NUMBER, List.of(), "rn")));
        QueryContext context = new QueryContext();
        WindowOperator op = new WindowOperator("Window#0", w, cancelingAtTheEnd(values, context),
                MemoryBudget.unlimited(), 1024, 8);
        op.attach(context);
        QueryCancelledException e = assertThrows(QueryCancelledException.class, op::next);
        assertEquals("Window#0", e.operator());
        op.close();
    }

    private static PlanNode.Values numbers(int n) {
        Schema s = Schema.of(new Schema.Field("x", DataType.INTEGER));
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            data.add(new Object[] {i % 1000});
        }
        return new PlanNode.Values(s, data);
    }

    /** The rows of values, canceling the query once it has delivered them. */
    private static Operator cancelingAtTheEnd(PlanNode.Values values, QueryContext context) {
        ValuesOperator source = new ValuesOperator("Values#1", values, 1024);
        return new Operator() {
            @Override
            public Schema schema() {
                return values.schema();
            }

            @Override
            public Batch next() {
                Batch b = source.next();
                if (b == null) {
                    context.cancel();
                }
                return b;
            }

            @Override
            public void close() {
                source.close();
            }
        };
    }
}
