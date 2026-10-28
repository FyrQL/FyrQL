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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.EdgeValues;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Aggregations over segments, on several threads, against one thread: the
 * same groups in the same order with the keys of their first rows, the same
 * results, and the same failure, that of the first group whose result
 * fails; also once the workers keep their rows as they are, scattered into
 * the partitions, and when the groups are merged in partitions. Of equal
 * values, {@code min} and {@code max} keep the first, as on one thread.
 */
class ParallelAggregateTest {

    private static final List<DataType> VALUE_TYPES = List.of(DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.INTEGER, DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE,
            DataType.decimal(15, 2), DataType.decimal(18, 18), DataType.decimal(4, 0), DataType.decimal(30, 6),
            DataType.decimal(38, 0), DataType.DATE, DataType.TIMESTAMP, DataType.VARCHAR);

    private static final List<AggregateFunction> FUNCTIONS = List.of(AggregateFunction.COUNT, AggregateFunction.SUM,
            AggregateFunction.AVG, AggregateFunction.MIN, AggregateFunction.MAX, AggregateFunction.BOOL_AND,
            AggregateFunction.BOOL_OR);

    /** Keys of few values, a key of many, a key of {@code -0.0}, {@code 0.0} and NaN, then the values. */
    private static final Schema SCHEMA = schema();

    private static final int KEYS = 4;

    /** For {@link #run}: all columns exact, {@code -0.0} apart from {@code 0.0}. */
    private static final int EXACT = Integer.MAX_VALUE;

    private final SplitTables source = new SplitTables();

    private static Schema schema() {
        List<Schema.Field> fields = new ArrayList<>();
        fields.add(new Schema.Field("g1", DataType.INTEGER));
        fields.add(new Schema.Field("g2", DataType.VARCHAR));
        fields.add(new Schema.Field("g3", DataType.BIGINT));
        fields.add(new Schema.Field("g4", DataType.DOUBLE));
        for (int i = 0; i < VALUE_TYPES.size(); i++) {
            fields.add(new Schema.Field("v" + i, VALUE_TYPES.get(i)));
        }
        return new Schema(fields);
    }

    @AfterEach
    void restore() {
        HashAggregateOperator.maxLocalGroups = HashAggregateOperator.MAX_LOCAL_GROUPS;
        HashAggregateOperator.minRowsPerGroup = HashAggregateOperator.MIN_ROWS_PER_GROUP;
    }

    /** Makes a worker keep its rows as they are once it holds more than a number of groups. */
    private static void scatterBeyond(int groups) {
        HashAggregateOperator.maxLocalGroups = groups;
        HashAggregateOperator.minRowsPerGroup = Integer.MAX_VALUE;
    }

    /** Rows with keys and values that are random, edge values or {@code NULL}. */
    private static List<Object[]> rows(SplittableRandom random, int n) {
        List<Object[]> rows = new ArrayList<>();
        // Rounds with mostly edge values let sums overflow; the others keep them small enough to succeed.
        boolean edgy = random.nextBoolean();
        int many = 1 + random.nextInt(Math.max(1, n));
        for (int r = 0; r < n; r++) {
            Object[] row = new Object[SCHEMA.size()];
            row[0] = random.nextInt(8) == 0 ? null : random.nextInt(5);
            row[1] = random.nextInt(8) == 0 ? null : List.of("a", "b", "c").get(random.nextInt(3));
            row[2] = random.nextInt(20) == 0 ? null : (long) random.nextInt(many);
            row[3] = switch (random.nextInt(5)) {
                case 0 -> -0.0;
                case 1 -> 0.0;
                case 2 -> Double.NaN;
                case 3 -> null;
                default -> 1.5;
            };
            for (int c = KEYS; c < row.length; c++) {
                DataType type = SCHEMA.type(c);
                int pick = random.nextInt(10);
                if (pick == 0) {
                    row[c] = null;
                } else if (edgy && pick < 6 || pick == 1) {
                    List<Object> edges = EdgeValues.of(type);
                    row[c] = edges.get(random.nextInt(edges.size()));
                } else {
                    row[c] = DataGenerator.randomValue(type, random, 0);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /** Split sizes that add up to a count: some empty, some of one row, the rest random. */
    private static int[] splits(SplittableRandom random, int count) {
        List<Integer> sizes = new ArrayList<>();
        int left = count;
        while (left > 0) {
            int size = Math.min(left, switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> 1;
                default -> 1 + random.nextInt(40);
            });
            sizes.add(size);
            left -= size;
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Runs a plan and returns its rows, with {@code -0.0} as {@code 0.0}
     * in the columns after the first {@code exact} ones, or the error it
     * fails with.
     */
    private Object run(PlanNode plan, int threads, boolean preserveOrder, int exact) {
        MemoryBudget budget = new MemoryBudget(1L << 40);
        Executor executor = new Executor(source, budget, 7, Codegen.off(), threads, preserveOrder);
        List<List<Object>> rows = new ArrayList<>();
        try {
            for (Object[] row : executor.rows(plan)) {
                for (int c = exact; c < row.length; c++) {
                    if (row[c] instanceof Double d && d == 0) {
                        row[c] = 0.0;
                    } else if (row[c] instanceof Float f && f == 0) {
                        row[c] = 0.0f;
                    }
                }
                rows.add(Arrays.asList(row));
            }
        } catch (RuntimeException e) {
            return e;
        } finally {
            assertEquals(0, budget.used(), "the budget is given back");
        }
        return rows;
    }

    private static void assertSame(Object expected, Object actual, String what) {
        if (expected instanceof RuntimeException e) {
            if (!(actual instanceof RuntimeException a)) {
                fail(what + ": one thread fails with " + e + ", several threads give " + actual);
                return;
            }
            assertEquals(e.getClass(), a.getClass(), what);
            assertEquals(e.getMessage(), a.getMessage(), what);
        } else {
            assertEquals(expected, actual, what);
        }
    }

    @Test
    void aggregatesGiveWhatOneThreadGives() {
        SplittableRandom random = new SplittableRandom(20261018);
        int failures = 0;
        int rawRounds = 0;
        for (int round = 0; round < 200; round++) {
            List<Object[]> rows = rows(random, random.nextInt(5) == 0 ? random.nextInt(4) : random.nextInt(600));
            PlanNode.Scan scan = source.table("t" + round, SCHEMA, rows, splits(random, rows.size()));
            List<Integer> keys = new ArrayList<>();
            for (int k = 0; k < KEYS; k++) {
                if (random.nextInt(3) == 0) {
                    keys.add(k);
                }
            }
            List<AggregateCall> calls = new ArrayList<>();
            calls.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"));
            boolean mergeable = random.nextInt(5) > 0;
            for (int k = 0; k < 6; k++) {
                int column = KEYS + random.nextInt(VALUE_TYPES.size());
                DataType type = SCHEMA.type(column);
                AggregateFunction function = FUNCTIONS.get(random.nextInt(FUNCTIONS.size()));
                if (type.isFloatingPoint() && (function == AggregateFunction.SUM
                        || function == AggregateFunction.AVG)) {
                    // Their sums add up in another order; see floatingPointSumsDifferInTheLastBitsOnly.
                    continue;
                }
                AggregateCall call = new AggregateCall(function, column, false, "a" + k);
                if (!mergeable && k == 0) {
                    call = random.nextBoolean() ? new AggregateCall(function, column, true, "a" + k)
                            : new AggregateCall(AggregateFunction.FIRST, column, false, "a" + k);
                }
                if (random.nextInt(4) == 0) {
                    // The first value column is a BOOLEAN.
                    call = call.withFilter(KEYS);
                }
                try {
                    call.check(SCHEMA);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                calls.add(call);
            }
            if (random.nextBoolean()) {
                calls.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "nf").withFilter(KEYS));
            }
            PlanNode input = random.nextBoolean() ? scan
                    : new PlanNode.Filter(scan, gt(col(SCHEMA, 2), lit(0L)));
            PlanNode.Aggregate aggregate = new PlanNode.Aggregate(input, keys, calls);
            boolean raw = random.nextBoolean();
            if (raw) {
                scatterBeyond(random.nextInt(4));
                rawRounds++;
            }
            String what = "round " + round + " keys " + keys + " calls " + calls + " raw " + raw;
            try {
                Object expected = run(aggregate, 1, true, EXACT);
                if (expected instanceof RuntimeException) {
                    failures++;
                }
                for (int threads : new int[] {2, 3, 8}) {
                    assertSame(expected, run(aggregate, threads, true, EXACT), threads + " threads, " + what);
                }
                Object unordered = run(aggregate, 4, false, EXACT);
                if (expected instanceof List<?> e && unordered instanceof List<?> u) {
                    assertEquals(sorted(e), sorted(u), "unordered, " + what);
                } else {
                    assertSame(expected, unordered, "unordered, " + what);
                }
            } finally {
                restore();
            }
        }
        // Some sums of HUGEINTs and of DECIMAL(38,0)s overflow; the test must see such failures.
        assertTrue(failures > 0, "no round failed");
        assertTrue(rawRounds > 0, "no round kept rows as they are");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void anAggregateWithAFilterTakesTheRowsAFilterBelowTakes() {
        SplittableRandom random = new SplittableRandom(20261026);
        for (int round = 0; round < 60; round++) {
            List<Object[]> rows = rows(random, random.nextInt(400));
            PlanNode.Scan scan = source.table("f" + round, SCHEMA, rows, splits(random, rows.size()));
            List<AggregateCall> plain = new ArrayList<>();
            plain.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"));
            for (int c = KEYS; c < SCHEMA.size(); c++) {
                for (AggregateFunction function : List.of(AggregateFunction.COUNT, AggregateFunction.MIN,
                        AggregateFunction.MAX, AggregateFunction.LIST, AggregateFunction.FIRST)) {
                    plain.add(new AggregateCall(function, c, false, function + "" + c));
                }
                if (SCHEMA.type(c).isIntegral() || SCHEMA.type(c) instanceof DataType.Decimal) {
                    plain.add(new AggregateCall(AggregateFunction.SUM, c, random.nextBoolean(), "s" + c));
                }
            }
            List<AggregateCall> filtered = plain.stream().map(c -> c.withFilter(KEYS)).toList();
            PlanNode below = new PlanNode.Aggregate(new PlanNode.Filter(scan, col(SCHEMA, KEYS)), List.of(), plain);
            PlanNode inside = new PlanNode.Aggregate(scan, List.of(), filtered);
            Object expected = run(below, 1, true, EXACT);
            for (int threads : new int[] {1, 4}) {
                assertSame(expected, run(inside, threads, true, EXACT), "round " + round + ", " + threads + " threads");
            }
        }
    }

    private static List<String> sorted(List<?> rows) {
        List<String> copy = new ArrayList<>();
        for (Object row : rows) {
            copy.add(row.toString());
        }
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    @Test
    void manyGroupsMergeInPartitions() {
        SplittableRandom random = new SplittableRandom(5);
        int n = 60_000;
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            // Mostly new keys, so that the groups hold few rows each.
            rows.add(new Object[] {r % 7, "s" + r % 3, (long) random.nextInt(2 * n), r % 2 == 0 ? -0.0 : 0.0,
                (long) r});
        }
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("s", DataType.VARCHAR),
                new Schema.Field("k", DataType.BIGINT), new Schema.Field("z", DataType.DOUBLE),
                new Schema.Field("x", DataType.BIGINT));
        int[] sizes = new int[60];
        Arrays.fill(sizes, 1000);
        PlanNode.Scan scan = source.table("big", schema, rows, sizes);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(2, 3), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 4, false, "s"),
                new AggregateCall(AggregateFunction.MIN, 1, false, "m"),
                new AggregateCall(AggregateFunction.MAX, 0, false, "x")));
        Object expected = run(aggregate, 1, true, 2);
        assertTrue(((List<?>) expected).size() > HashAggregateOperator.PARTITIONED_MERGE);
        assertSame(expected, run(aggregate, 4, true, 2), "grouped on the workers");
        // Most keys are new, so that the workers stop grouping by themselves, in the middle of a morsel.
        HashAggregateOperator.maxLocalGroups = 100;
        assertSame(expected, run(aggregate, 4, true, 2), "rows kept as they are");
        assertTrue(source.threads.stream().anyMatch(t -> t.startsWith("fyrql-worker-")), source.threads::toString);
    }

    @Test
    void ofGroupsInManyPartitionsTheFirstThatFailsFailsTheQuery() {
        BigInteger max = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
        Schema schema = Schema.of(new Schema.Field("k", DataType.BIGINT), new Schema.Field("h", DataType.HUGEINT));
        int n = 40_000;
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> late = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            long key = r * 7919L % n;
            if (r % 1999 == 1000) {
                // The sum of the group overflows, with a message of its own.
                rows.add(new Object[] {key, max.subtract(BigInteger.valueOf(r))});
                late.add(new Object[] {key, max});
            } else {
                rows.add(new Object[] {key, BigInteger.ONE});
            }
        }
        rows.addAll(late);
        int[] sizes = new int[(rows.size() + 499) / 500];
        Arrays.fill(sizes, 500);
        sizes[sizes.length - 1] = rows.size() - 500 * (sizes.length - 1);
        PlanNode.Scan scan = source.table("overflow", schema, rows, sizes);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(0), List.of(
                new AggregateCall(AggregateFunction.SUM, 1, false, "s")));
        Object expected = run(aggregate, 1, true, 1);
        assertEquals("Out of range for HUGEINT: " + max.add(max).subtract(BigInteger.valueOf(1000)),
                ((RuntimeException) expected).getMessage().replace(" in Aggregate#0", ""));
        for (int run = 0; run < 5; run++) {
            assertSame(expected, run(aggregate, 4, true, 1), "run " + run);
            assertSame(expected, run(aggregate, 3, false, 1), "unordered, run " + run);
            HashAggregateOperator.maxLocalGroups = 100;
            assertSame(expected, run(aggregate, 4, true, 1), "rows kept as they are, run " + run);
            restore();
        }
    }

    @Test
    void theKeyOfAGroupIsThatOfItsFirstRow() {
        Schema schema = Schema.of(DataType.DOUBLE, DataType.INTEGER);
        List<Object[]> rows = new ArrayList<>();
        int[] sizes = new int[40];
        for (int i = 0; i < 40; i++) {
            // The zeros begin with -0.0 in the first split; most later splits begin with 0.0.
            rows.add(new Object[] {i == 3 ? -0.0 : i < 3 ? (double) i + 1 : 0.0, i});
            rows.add(new Object[] {i % 5 == 0 ? -0.0 : 0.0, i});
            sizes[i] = 2;
        }
        PlanNode.Scan scan = source.table("zeros", schema, rows, sizes);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(0), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.MIN, 1, false, "m")));
        Object expected = run(aggregate, 1, true, 1);
        assertEquals(List.of(List.of(1.0, 1L, 0), List.of(-0.0, 77L, 0), List.of(2.0, 1L, 1), List.of(3.0, 1L, 2)),
                expected);
        for (int run = 0; run < 20; run++) {
            assertSame(expected, run(aggregate, 4, true, 1), "run " + run);
            scatterBeyond(0);
            assertSame(expected, run(aggregate, 4, true, 1), "rows kept as they are, run " + run);
            restore();
        }
    }

    @Test
    void minAndMaxKeepTheFirstOfEqualValues() {
        SplittableRandom random = new SplittableRandom(20261019);
        Schema schema = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("d", DataType.DOUBLE),
                new Schema.Field("f", DataType.FLOAT));
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < 4000; r++) {
            // Zeros of both signs in every group, which a later split may bring first to its worker.
            double d = random.nextInt(4) == 0 ? 1.0 : random.nextBoolean() ? -0.0 : 0.0;
            rows.add(new Object[] {r % 37, d, random.nextInt(4) == 0 ? null : (float) -d});
        }
        PlanNode.Scan scan = source.table("signed zeros", schema, rows, splits(random, rows.size()));
        List<AggregateCall> calls = List.of(new AggregateCall(AggregateFunction.MIN, 1, false, "min_d"),
                new AggregateCall(AggregateFunction.MAX, 1, false, "max_d"),
                new AggregateCall(AggregateFunction.MIN, 2, false, "min_f"),
                new AggregateCall(AggregateFunction.MAX, 2, false, "max_f"));
        for (List<Integer> keys : List.of(List.of(0), List.<Integer>of())) {
            PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, keys, calls);
            Object expected = run(aggregate, 1, true, EXACT);
            for (int run = 0; run < 10; run++) {
                for (int threads : new int[] {2, 3, 8}) {
                    assertSame(expected, run(aggregate, threads, true, EXACT), threads + " threads, run " + run);
                }
                scatterBeyond(run % 3);
                assertSame(expected, run(aggregate, 4, true, EXACT), "rows kept as they are, run " + run);
                restore();
            }
        }
    }

    @Test
    void floatingPointSumsAndStatisticsDifferInTheLastBitsOnly() {
        SplittableRandom random = new SplittableRandom(11);
        Schema schema = Schema.of(DataType.INTEGER, DataType.DOUBLE, DataType.FLOAT);
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < 3000; r++) {
            rows.add(new Object[] {r % 3, random.nextDouble() * 1000, (float) (random.nextDouble() * 10)});
        }
        int[] sizes = new int[30];
        Arrays.fill(sizes, 100);
        PlanNode.Scan scan = source.table("floats", schema, rows, sizes);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(0), List.of(
                new AggregateCall(AggregateFunction.SUM, 1, false, "s"),
                new AggregateCall(AggregateFunction.AVG, 1, false, "a"),
                new AggregateCall(AggregateFunction.SUM, 2, false, "f"),
                new AggregateCall(AggregateFunction.STDDEV_SAMP, 1, false, "sd"),
                new AggregateCall(AggregateFunction.VAR_POP, 2, false, "vf"),
                new AggregateCall(AggregateFunction.VAR_SAMP, 1, false, "v"),
                new AggregateCall(AggregateFunction.STDDEV_POP, 2, false, "sdf")));
        @SuppressWarnings("unchecked")
        List<List<Object>> expected = (List<List<Object>>) run(aggregate, 1, true, 1);
        @SuppressWarnings("unchecked")
        List<List<Object>> actual = (List<List<Object>>) run(aggregate, 4, true, 1);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).get(0), actual.get(i).get(0));
            for (int c = 1; c < 8; c++) {
                double e = ((Number) expected.get(i).get(c)).doubleValue();
                double a = ((Number) actual.get(i).get(c)).doubleValue();
                assertEquals(e, a, Math.ulp(e) * 64, "row " + i + " column " + c);
            }
        }
    }

    @Test
    void partitionsSpreadSmallKeys() {
        int[] counts = new int[Partitions.PARTITIONS];
        for (int key = 0; key < 16_000; key++) {
            // The interpreted hash of one INTEGER key.
            int h = 17 * 31 + key;
            counts[Partitions.partition(h ^ h >>> 16, counts.length)]++;
        }
        for (int c : counts) {
            assertTrue(c > 800 && c < 1200, Arrays.toString(counts));
        }
    }

    @Test
    void segmentsComeInTheOrderOfTheirPlaces() {
        for (boolean interleaved : new boolean[] {false, true}) {
            // The places of two runs, the morsel in the high bits; the first run of two sources.
            long[] first = {place(0, 1), place(0, 4), place(2, 0)};
            long[] second = interleaved ? new long[] {place(0, 2), place(1, 0), place(1, 5)}
                    : new long[] {place(1, 0), place(1, 5), place(3, 2)};
            List<HashAggregateOperator.Segment> segments = new ArrayList<>();
            int entry = HashAggregateOperator.cut(segments, 0, keys(first, 0, 2), first, 0);
            HashAggregateOperator.cut(segments, 0, keys(first, 2, 3), first, entry);
            HashAggregateOperator.cut(segments, 1, keys(second, 0, 3), second, 0);
            List<HashAggregateOperator.Segment> ordered = HashAggregateOperator.inOrder(segments,
                    List.of(first, second));
            List<Long> places = new ArrayList<>();
            for (HashAggregateOperator.Segment s : ordered) {
                long[] run = s.part() == 0 ? first : second;
                for (int r = s.from(); r < s.to(); r++) {
                    long place = (Long) s.keys().column(0).get(r);
                    assertEquals(run[s.entry() + r - s.from()], place, "the entry of a row");
                    places.add(place);
                }
            }
            List<Long> expected = new ArrayList<>();
            for (long p : first) {
                expected.add(p);
            }
            for (long p : second) {
                expected.add(p);
            }
            expected.sort(Comparator.naturalOrder());
            assertEquals(expected, places, "interleaved " + interleaved);
            // Apart, there is a segment per source and morsel; interleaved, one per row.
            assertEquals(interleaved ? 6 : 4, ordered.size(), "interleaved " + interleaved);
        }
    }

    private static long place(int morsel, int row) {
        return (long) morsel << 40 | row;
    }

    /** The places of the entries {@code from} to {@code to} of a run as the keys of its source. */
    private static Batch keys(long[] places, int from, int to) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(new Object[] {places[i]});
        }
        return Batch.ofRows(Schema.of(DataType.BIGINT), rows);
    }

    @Test
    void runsMergeInTheOrderOfTheirPlaces() {
        assertEquals(List.of(0, 3, 1, 4, 5, 2), Arrays.stream(Partitions.mergeOrder(List.of(
                new long[] {1, 5, 9}, new long[] {}, new long[] {2, 5, 7}))).boxed().toList());
    }
}
