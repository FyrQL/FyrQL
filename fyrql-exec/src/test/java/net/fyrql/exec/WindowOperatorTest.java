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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.IntervalUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The results of the window functions, as DuckDB 1.5.6 gives them for the same rows. */
class WindowOperatorTest {

    private static final Schema T = Schema.of(new Schema.Field("p", DataType.INTEGER),
            new Schema.Field("o", DataType.INTEGER), new Schema.Field("x", DataType.INTEGER),
            new Schema.Field("k", DataType.INTEGER), new Schema.Field("d", DataType.INTEGER),
            new Schema.Field("m", DataType.INTEGER));
    private static final int P = 0;
    private static final int O = 1;
    private static final int X = 2;
    private static final int K = 3;
    private static final int D = 4;
    private static final int M = 5;

    /** The rows of the probes: a partition, an order with peers, a value, an offset, a default, -5. */
    private static final PlanNode.Values ROWS = new PlanNode.Values(T, List.of(
            new Object[] {1, 1, 10, 1, 1010, -5},
            new Object[] {1, 2, null, 2, null, -5},
            new Object[] {1, 2, 30, null, 1030, -5},
            new Object[] {1, 4, 40, -1, 1040, -5},
            new Object[] {2, 1, 50, 0, 1050, -5},
            new Object[] {2, null, 60, 3, 1060, -5},
            new Object[] {null, 5, 70, 1, 1070, -5}));

    private static final Schema W = Schema.of(new Schema.Field("p", DataType.INTEGER),
            new Schema.Field("o", DataType.INTEGER), new Schema.Field("x", DataType.INTEGER),
            new Schema.Field("k", DataType.INTEGER), new Schema.Field("gt20", DataType.BOOLEAN),
            new Schema.Field("kpos", DataType.BOOLEAN), new Schema.Field("knn", DataType.BOOLEAN),
            new Schema.Field("ne30", DataType.BOOLEAN), new Schema.Field("gt10", DataType.BOOLEAN),
            new Schema.Field("two", DataType.INTEGER), new Schema.Field("zero", DataType.INTEGER),
            new Schema.Field("minus", DataType.INTEGER));
    private static final int GT20 = 4;
    private static final int KPOS = 5;
    private static final int KNN = 6;
    private static final int NE30 = 7;
    private static final int GT10 = 8;
    private static final int TWO = 9;
    private static final int ZERO = 10;
    private static final int MINUS = 11;

    /**
     * The rows of the later probes: those of {@link #ROWS} without the
     * default, two more, and the filters {@code x > 20}, {@code k > 0},
     * {@code k IS NOT NULL}, {@code x <> 30} and {@code x > 10} and the
     * constants 2, 0 and -1 as columns.
     */
    private static final PlanNode.Values MORE = new PlanNode.Values(W, List.of(
            more(1, 1, 10, 1), more(1, 2, null, 2), more(1, 2, 30, null), more(1, 4, 40, -1),
            more(2, 1, 50, 0), more(2, null, 60, 3), more(null, 5, 70, 1), more(1, null, 80, 2),
            more(1, 7, null, 1)));

    private static Object[] more(Integer p, Integer o, Integer x, Integer k) {
        return new Object[] {p, o, x, k, x == null ? null : x > 20, k == null ? null : k > 0, k != null,
                x == null ? null : x != 30, x == null ? null : x > 10, 2, 0, -1};
    }

    private static Executor executor(MemoryBudget budget, int batchSize) {
        return new Executor(scan -> {
            throw new AssertionError("no scans here");
        }, budget, batchSize);
    }

    /** Runs a window at several batch sizes, which must agree, and returns its rows. */
    private static List<List<Object>> run(PlanNode.Window window) {
        List<List<Object>> result = null;
        for (int batchSize : new int[] {1, 7, 64}) {
            List<Batch> batches = executor(MemoryBudget.unlimited(), batchSize).execute(window);
            List<List<Object>> rows = new ArrayList<>();
            for (Batch b : batches) {
                assertTrue(b.rowCount() > 0 && b.rowCount() <= batchSize, "batch of " + b.rowCount());
                for (Object[] r : b.rows()) {
                    rows.add(Arrays.asList(r));
                }
            }
            if (result != null) {
                assertEquals(result, rows, "batch size " + batchSize);
            }
            result = rows;
        }
        return result;
    }

    /** The value column and the results of the calls of the rows of a window over {@link #ROWS} or {@link #MORE}. */
    private static List<List<Object>> results(PlanNode.Window window) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : run(window)) {
            List<Object> r = new ArrayList<>();
            r.add(row.get(X));
            r.addAll(row.subList(window.input().schema().size(), row.size()));
            out.add(r);
        }
        return out;
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private static Expression.Literal offset(long n) {
        return new Expression.Literal(DataType.BIGINT, n);
    }

    private static FrameBound bound(BoundKind kind, long offset) {
        return FrameBound.of(kind, offset(offset));
    }

    private static Frame rows(FrameBound start, FrameBound end) {
        return new Frame(FrameUnit.ROWS, start, end, FrameExclusion.NO_OTHERS);
    }

    private static Frame frame(FrameUnit unit, FrameBound start, FrameBound end, FrameExclusion exclusion) {
        return new Frame(unit, start, end, exclusion);
    }

    private static Frame range(FrameBound start, FrameBound end) {
        return new Frame(FrameUnit.RANGE, start, end, FrameExclusion.NO_OTHERS);
    }

    private static Frame groups(FrameBound start, FrameBound end) {
        return new Frame(FrameUnit.GROUPS, start, end, FrameExclusion.NO_OTHERS);
    }

    /** A bound with an offset of a type. */
    private static FrameBound bound(BoundKind kind, DataType type, Object offset) {
        return FrameBound.of(kind, new Expression.Literal(type, offset));
    }

    /** A bound with an offset of a count of a unit. */
    private static FrameBound bound(BoundKind kind, long count, IntervalUnit unit) {
        return new FrameBound(kind, offset(count), unit);
    }

    private static FrameBound bound(BoundKind kind) {
        return FrameBound.of(kind);
    }

    private static WindowCall countStar(Frame frame) {
        return WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_star"), frame);
    }

    private static WindowCall filtered(AggregateCall call, int filter, Frame frame) {
        return new WindowCall(WindowFunction.AGGREGATE, call, List.of(), false, filter, frame, call.name());
    }

    private static WindowCall value(WindowFunction function, List<Integer> arguments, Frame frame) {
        return new WindowCall(function, null, arguments, false, -1, frame, function.sqlName());
    }

    private static WindowCall ignoringNulls(WindowFunction function, List<Integer> arguments, Frame frame) {
        return new WindowCall(function, null, arguments, true, -1, frame, function.sqlName());
    }

    /** The column {@code index} of the results of a window, over all rows. */
    private static List<Object> column(List<List<Object>> results, int index) {
        return results.stream().map(r -> r.get(index)).toList();
    }

    private static List<BigInteger> bigs(Long... values) {
        return Arrays.stream(values).map(v -> v == null ? null : big(v)).toList();
    }

    private static WindowCall aggregate(AggregateFunction function, int column, Frame frame, String name) {
        return WindowCall.of(new AggregateCall(function, column, false, name), frame);
    }

    private static BigInteger big(long x) {
        return BigInteger.valueOf(x);
    }

    @Test
    void theRankingsCountRowsAndPeersWithinEachPartition() {
        PlanNode.Window w = new PlanNode.Window(ROWS, List.of(P), List.of(SortKey.asc(O)), List.of(
                WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn"),
                WindowCall.of(WindowFunction.RANK, List.of(), "rank"),
                WindowCall.of(WindowFunction.DENSE_RANK, List.of(), "dense"),
                WindowCall.of(WindowFunction.PERCENT_RANK, List.of(), "pr"),
                WindowCall.of(WindowFunction.CUME_DIST, List.of(), "cd")));
        // The peers keep the order of the input; NULL partitions and keys come last.
        assertEquals(List.of(
                row(10, 1L, 1L, 1L, 0.0, 0.25),
                row(null, 2L, 2L, 2L, 1 / 3.0, 0.75),
                row(30, 3L, 2L, 2L, 1 / 3.0, 0.75),
                row(40, 4L, 4L, 3L, 1.0, 1.0),
                row(50, 1L, 1L, 1L, 0.0, 0.5),
                row(60, 2L, 2L, 2L, 1.0, 1.0),
                row(70, 1L, 1L, 1L, 0.0, 1.0)), results(w));
        assertEquals(Schema.of(new Schema.Field("rn", DataType.BIGINT, false),
                new Schema.Field("cd", DataType.DOUBLE, false)),
                Schema.of(w.schema().fields().get(6), w.schema().fields().get(10)));
    }

    @Test
    void ntileFillsTheFirstBucketsWithOneRowMore() {
        List<Long> three = new ArrayList<>();
        List<Long> hundred = new ArrayList<>();
        for (int row = 0; row < 7; row++) {
            three.add(WindowOperator.ntile(3, 7, row));
            hundred.add(WindowOperator.ntile(100, 7, row));
        }
        assertEquals(List.of(1L, 1L, 1L, 2L, 2L, 3L, 3L), three);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), hundred);
        List<Object[]> positive = new ArrayList<>();
        for (Object[] r : ROWS.rows()) {
            if (r[K] == null || (Integer) r[K] > 0) {
                positive.add(r);
            }
        }
        PlanNode.Window w = new PlanNode.Window(new PlanNode.Values(T, positive), List.of(P),
                List.of(SortKey.asc(O), SortKey.asc(X)), List.of(WindowCall.of(WindowFunction.NTILE, List.of(K),
                        "n")));
        assertEquals(List.of(row(10, 1L), row(30, null), row(null, 2L), row(60, 1L), row(70, 1L)), results(w));
        PlanNode.Window all = new PlanNode.Window(ROWS, List.of(P), List.of(SortKey.asc(O), SortKey.asc(X)),
                List.of(WindowCall.of(WindowFunction.NTILE, List.of(K), "n")));
        QueryException e = assertThrows(QueryException.class, () -> run(all));
        assertTrue(e.getMessage().contains("Argument for ntile must be greater than zero"), e.getMessage());
    }

    @Test
    void theValueFunctionsTakeTheirOffsetsAndDefaultsPerRow() {
        Frame partition = rows(FrameBound.of(BoundKind.UNBOUNDED_PRECEDING),
                FrameBound.of(BoundKind.UNBOUNDED_FOLLOWING));
        PlanNode.Window w = new PlanNode.Window(ROWS, List.of(P), List.of(SortKey.asc(O), SortKey.asc(X)), List.of(
                WindowCall.of(WindowFunction.LAG, List.of(X), "lag"),
                WindowCall.of(WindowFunction.LAG, List.of(X, K), "lagk"),
                WindowCall.of(WindowFunction.LAG, List.of(X, K, M), "lagkm"),
                WindowCall.of(WindowFunction.LEAD, List.of(X, K, D), "leadkd"),
                new WindowCall(WindowFunction.NTH_VALUE, null, List.of(X, K), false, -1, partition, "nth"),
                WindowCall.of(WindowFunction.FIRST_VALUE, List.of(X), "first"),
                WindowCall.of(WindowFunction.LAST_VALUE, List.of(X), "last")));
        // A NULL offset gives NULL, not the default; a negative one looks the other way.
        assertEquals(List.of(
                row(10, null, null, -5, 30, 10, 10, 10),
                row(30, 10, null, null, null, null, 10, 30),
                row(null, 30, 10, 10, null, 30, 10, null),
                row(40, null, null, -5, null, null, 10, 40),
                row(50, null, 50, 50, 50, null, 50, 50),
                row(60, 50, null, -5, 1060, null, 50, 60),
                row(70, null, null, -5, 1070, 70, 70, 70)), results(w));
    }

    @Test
    void theDefaultFrameEndsWithTheLastPeer() {
        PlanNode.Window w = new PlanNode.Window(ROWS, List.of(P), List.of(SortKey.asc(O)), List.of(
                WindowCall.of(WindowFunction.FIRST_VALUE, List.of(X), "first"),
                WindowCall.of(WindowFunction.LAST_VALUE, List.of(X), "last"),
                aggregate(AggregateFunction.SUM, X, Frame.DEFAULT, "sum"),
                aggregate(AggregateFunction.COUNT, X, Frame.DEFAULT, "count"),
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_star"),
                        Frame.DEFAULT),
                aggregate(AggregateFunction.MIN, X, Frame.DEFAULT, "min"),
                aggregate(AggregateFunction.AVG, X, Frame.DEFAULT, "avg")));
        assertEquals(List.of(
                row(10, 10, 10, big(10), 1L, 1L, 10, 10.0),
                row(null, 10, 30, big(40), 2L, 3L, 10, 20.0),
                row(30, 10, 30, big(40), 2L, 3L, 10, 20.0),
                row(40, 10, 40, big(80), 3L, 4L, 10, 26.666666666666668),
                row(50, 50, 50, big(50), 1L, 1L, 50, 50.0),
                row(60, 50, 60, big(110), 2L, 2L, 50, 55.0),
                row(70, 70, 70, big(70), 1L, 1L, 70, 70.0)), results(w));
        // Without an order, all rows of a partition are peers.
        PlanNode.Window whole = new PlanNode.Window(ROWS, List.of(P), List.of(), List.of(
                aggregate(AggregateFunction.SUM, X, Frame.DEFAULT, "sum")));
        assertEquals(List.of(big(80), big(80), big(80), big(80), big(110), big(110), big(70)),
                results(whole).stream().map(r -> r.get(1)).toList());
    }

    @Test
    void rowFramesCountRowsAndANegativeOffsetCountsTheOtherWay() {
        long max = Long.MAX_VALUE;
        Frame around = rows(bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 1));
        PlanNode.Window w = new PlanNode.Window(ROWS, List.of(), List.of(SortKey.asc(O), SortKey.asc(X)), List.of(
                aggregate(AggregateFunction.SUM, X, rows(bound(BoundKind.PRECEDING, -1),
                        bound(BoundKind.FOLLOWING, 1)), "next"),
                aggregate(AggregateFunction.SUM, X, rows(bound(BoundKind.PRECEDING, max),
                        bound(BoundKind.FOLLOWING, max)), "all"),
                aggregate(AggregateFunction.SUM, X, rows(FrameBound.of(BoundKind.CURRENT_ROW),
                        bound(BoundKind.FOLLOWING, -max)), "none"),
                aggregate(AggregateFunction.SUM, X, rows(FrameBound.of(BoundKind.UNBOUNDED_PRECEDING),
                        bound(BoundKind.PRECEDING, -2)), "ahead"),
                aggregate(AggregateFunction.STRING_AGG, X, around, "string_agg"),
                aggregate(AggregateFunction.LIST, X, around, "list"),
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT, P, true, "distinct"), Frame.DEFAULT),
                aggregate(AggregateFunction.MAX, X, rows(bound(BoundKind.FOLLOWING, 3),
                        bound(BoundKind.FOLLOWING, 5)), "max"),
                aggregate(AggregateFunction.LAST, X, around, "last"),
                aggregate(AggregateFunction.ANY_VALUE, X, around, "any_value"),
                aggregate(AggregateFunction.FIRST, X, around, "first")));
        assertEquals(List.of(
                row(10, big(50), big(260), null, big(90), "10,50", List.of(10, 50), 1L, 70, 50, 10, 10),
                row(50, big(30), big(260), null, big(90), "10,50,30", List.of(10, 50, 30), 2L, 70, 30, 10, 10),
                row(30, null, big(260), null, big(130), "50,30", Arrays.asList(50, 30, null), 2L, 70, null, 50,
                        50),
                row(null, big(40), big(260), null, big(200), "30,40", Arrays.asList(30, null, 40), 2L, 60, 40, 30,
                        30),
                row(40, big(70), big(260), null, big(260), "40,70", Arrays.asList(null, 40, 70), 2L, null, 70, 40,
                        null),
                row(70, big(60), big(260), null, big(260), "40,70,60", List.of(40, 70, 60), 2L, null, 60, 40, 40),
                row(60, null, big(260), null, big(260), "70,60", List.of(70, 60), 2L, null, 60, 70, 70)),
                results(w));
    }

    @Test
    void theTreesGiveWhatTheRowsOfEachFrameGive() {
        Schema s = Schema.of(new Schema.Field("p", DataType.INTEGER), new Schema.Field("o", DataType.INTEGER),
                new Schema.Field("x", DataType.BIGINT), new Schema.Field("b", DataType.BOOLEAN));
        SplittableRandom random = new SplittableRandom(20261017);
        for (int round = 0; round < 40; round++) {
            int n = 1 + random.nextInt(round < 20 ? 60 : 1500);
            List<Object[]> data = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int p = random.nextInt(4);
                long x = switch (random.nextInt(4)) {
                    case 0 -> Long.MAX_VALUE - random.nextInt(10);
                    case 1 -> Long.MIN_VALUE + random.nextInt(10);
                    default -> random.nextLong(-1000, 1000);
                };
                data.add(new Object[] {p == 3 ? null : p, random.nextInt(n / 3 + 1),
                        random.nextInt(8) == 0 ? null : x, random.nextInt(8) == 0 ? null : random.nextBoolean()});
            }
            PlanNode.Values values = new PlanNode.Values(s, data);
            Frame frame = randomFrame(random);
            // Every other round, the aggregates take only the rows whose b is true.
            int filter = round % 2 == 0 ? -1 : 3;
            List<WindowCall> calls = new ArrayList<>();
            for (AggregateFunction f : AggregateFunction.values()) {
                if (f == AggregateFunction.COUNT_STAR) {
                    calls.add(filtered(new AggregateCall(f, -1, false, "c"), filter, frame));
                } else if (f != AggregateFunction.STRING_AGG && f != AggregateFunction.LIST) {
                    int column = f == AggregateFunction.BOOL_AND || f == AggregateFunction.BOOL_OR ? 3 : 2;
                    calls.add(filtered(new AggregateCall(f, column, false, f.sqlName()), filter, frame));
                }
            }
            boolean ordered = frame.unit() == FrameUnit.RANGE && frame.hasOffset() || random.nextBoolean();
            List<SortKey> order = ordered ? List.of(new SortKey(1, random.nextBoolean(), random.nextBoolean()))
                    : List.of();
            PlanNode.Window w = new PlanNode.Window(values, random.nextBoolean() ? List.of(0) : List.of(), order,
                    calls);
            int batchSize = 1 + random.nextInt(100);
            String what = "round " + round + " " + frame;
            List<List<Object>> expected = direct(w, batchSize, false);
            List<List<Object>> actual = direct(w, batchSize, true);
            assertEquals(expected.size(), actual.size(), what);
            for (int r = 0; r < expected.size(); r++) {
                for (int c = 0; c < s.size() + calls.size(); c++) {
                    Object e = expected.get(r).get(c);
                    Object a = actual.get(r).get(c);
                    // The trees combine the moments of the statistics in another order than row by row.
                    if (e instanceof Double x && a instanceof Double y && c >= s.size()
                            && calls.get(c - s.size()).aggregate().function().isStatistic()) {
                        assertEquals(x, y, Math.max(Math.abs(x), Math.abs(y)) * 1e-9, what + " row " + r + " "
                                + calls.get(c - s.size()).name());
                    } else {
                        assertEquals(e, a, what + " row " + r + " column " + c);
                    }
                }
            }
        }
    }

    /**
     * A frame of any unit with any exclusion; the offsets of {@code ROWS}
     * may be negative, those of {@code GROUPS} and of {@code RANGE}, over
     * an {@code INTEGER} key, not.
     */
    private static Frame randomFrame(SplittableRandom random) {
        BoundKind[] kinds = BoundKind.values();
        FrameUnit[] units = FrameUnit.values();
        FrameExclusion[] exclusions = FrameExclusion.values();
        while (true) {
            BoundKind start = kinds[random.nextInt(kinds.length - 1)];
            BoundKind end = kinds[1 + random.nextInt(kinds.length - 1)];
            if (start.ordinal() > end.ordinal()) {
                continue;
            }
            FrameUnit unit = units[random.nextInt(units.length)];
            int least = unit == FrameUnit.ROWS ? -3 : 0;
            FrameBound a = start.hasOffset() ? offsetBound(unit, start, random.nextInt(least, 40)) : bound(start);
            FrameBound b = end.hasOffset() ? offsetBound(unit, end, random.nextInt(least, 40)) : bound(end);
            return new Frame(unit, a, b, exclusions[random.nextInt(exclusions.length)]);
        }
    }

    private static FrameBound offsetBound(FrameUnit unit, BoundKind kind, int offset) {
        return unit == FrameUnit.RANGE ? bound(kind, DataType.INTEGER, offset) : bound(kind, offset);
    }

    /** Runs a window over its values directly, with or without trees. */
    private static List<List<Object>> direct(PlanNode.Window w, int batchSize, boolean trees) {
        List<List<Object>> rows = new ArrayList<>();
        try (Operator op = new WindowOperator("Window#0", w, new ValuesOperator("Values#1",
                (PlanNode.Values) w.input(), batchSize), MemoryBudget.unlimited(), batchSize, trees)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                for (Object[] r : b.rows()) {
                    rows.add(Arrays.asList(r));
                }
            }
        }
        return rows;
    }

    @Test
    void anEmptyInputGivesNoRowsAndTheBudgetTakesTheInput() {
        PlanNode.Window w = new PlanNode.Window(new PlanNode.Values(T, List.of()), List.of(P), List.of(),
                List.of(WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn")));
        assertEquals(List.of(), run(w));
        PlanNode.Window over = new PlanNode.Window(ROWS, List.of(P), List.of(),
                List.of(WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn")));
        QueryException e = assertThrows(QueryException.class,
                () -> executor(new MemoryBudget(100), 64).execute(over));
        assertTrue(e.getMessage().contains("Memory budget of 100 bytes exceeded"), e.getMessage());
        MemoryBudget budget = new MemoryBudget(1 << 20);
        executor(budget, 64).execute(over);
        assertEquals(0, budget.used());
    }

    @Test
    void rangeFramesReachOverTheKeysWithinTheOffsets() {
        FrameBound one = bound(BoundKind.PRECEDING, DataType.INTEGER, 1);
        FrameBound current = bound(BoundKind.CURRENT_ROW);
        PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                aggregate(AggregateFunction.SUM, X, range(one, bound(BoundKind.FOLLOWING, DataType.INTEGER, 1)), "a"),
                aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.FOLLOWING, DataType.INTEGER, 3),
                        bound(BoundKind.FOLLOWING, DataType.INTEGER, 5)), "b"),
                aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.PRECEDING, DataType.INTEGER, 5),
                        bound(BoundKind.PRECEDING, DataType.INTEGER, 2)), "c"),
                aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.UNBOUNDED_PRECEDING), one), "d"),
                aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.PRECEDING, DataType.BIGINT, Long.MAX_VALUE),
                        current), "e"),
                aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.PRECEDING, DataType.decimal(2, 1),
                        new BigDecimal("1.5")), current), "f")));
        // A NULL key takes its peers, whatever the offsets.
        List<List<Object>> r = results(w);
        assertEquals(Arrays.asList(10, null, 30, 40, null, 80, 50, 60, 70), column(r, 0));
        assertEquals(bigs(40L, 40L, 40L, 40L, null, 80L, 50L, 60L, 70L), column(r, 1));
        assertEquals(bigs(40L, null, null, null, null, 80L, null, 60L, null), column(r, 2));
        assertEquals(bigs(null, null, null, 40L, 70L, 80L, null, 60L, null), column(r, 3));
        assertEquals(bigs(null, 10L, 10L, 40L, 80L, 160L, null, 110L, null), column(r, 4));
        assertEquals(bigs(10L, 40L, 40L, 80L, 80L, 80L, 50L, 60L, 70L), column(r, 5));
        assertEquals(bigs(10L, 40L, 40L, 40L, null, 80L, 50L, 60L, 70L), column(r, 6));

        // Descending, PRECEDING means the greater keys.
        Frame around = range(one, bound(BoundKind.FOLLOWING, DataType.INTEGER, 1));
        PlanNode.Window desc = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.desc(O)),
                List.of(aggregate(AggregateFunction.SUM, X, around, "s")));
        assertEquals(List.of(row(null, null), row(40, big(40)), row(null, big(40)), row(30, big(40)),
                row(10, big(40)), row(80, big(80)), row(50, big(50)), row(60, big(60)), row(70, big(70))),
                results(desc));
        PlanNode.Window nullsFirst = new PlanNode.Window(MORE, List.of(P), List.of(new SortKey(O, true, true)),
                List.of(aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.PRECEDING, DataType.INTEGER, 2),
                        current), "s")));
        assertEquals(bigs(80L, 10L, 40L, 40L, 70L, null, 60L, 50L, 70L), column(results(nullsFirst), 1));
        PlanNode.Window descNullsFirst = new PlanNode.Window(MORE, List.of(P), List.of(new SortKey(O, false, true)),
                List.of(aggregate(AggregateFunction.SUM, X, range(bound(BoundKind.PRECEDING, DataType.INTEGER, 2),
                        bound(BoundKind.FOLLOWING, DataType.INTEGER, 0)), "s")));
        assertEquals(List.of(row(80, big(80)), row(null, null), row(40, big(40)), row(null, big(70)),
                row(30, big(70)), row(10, big(40)), row(60, big(60)), row(50, big(50)), row(70, big(70))),
                results(descNullsFirst));
    }

    /**
     * Runs the frames over one key, ascending or not, each with
     * {@code count(*)} and {@code string_agg} of the key as text; returns
     * the text of the key and the results.
     */
    private static List<List<Object>> overKeys(DataType type, List<Object> keys, boolean ascending,
            Frame... frames) {
        Schema s = Schema.of(new Schema.Field("key", type), new Schema.Field("text", DataType.VARCHAR));
        List<Object[]> data = new ArrayList<>();
        for (Object k : keys) {
            data.add(new Object[] {k, k == null ? null : text(k, type)});
        }
        List<WindowCall> calls = new ArrayList<>();
        for (Frame f : frames) {
            calls.add(countStar(f));
            calls.add(aggregate(AggregateFunction.STRING_AGG, 1, f, "keys"));
        }
        PlanNode.Window w = new PlanNode.Window(new PlanNode.Values(s, data), List.of(),
                List.of(ascending ? SortKey.asc(0) : SortKey.desc(0)), calls);
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> r : run(w)) {
            out.add(r.subList(1, r.size()));
        }
        return out;
    }

    private static String text(Object key, DataType type) {
        if (type == DataType.DATE) {
            return LocalDate.ofEpochDay((Integer) key).toString();
        }
        if (type == DataType.TIMESTAMP) {
            return Conversions.toLocalDateTime((Long) key).toString();
        }
        return key.toString();
    }

    /** The counts of {@link #overKeys}, one list per frame. */
    private static List<List<Object>> counts(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (int i = 1; i < rows.get(0).size(); i += 2) {
            out.add(column(rows, i));
        }
        return out;
    }

    private static int day(String date) {
        return (int) LocalDate.parse(date).toEpochDay();
    }

    private static long micros(String timestamp) {
        return Conversions.toMicros(LocalDateTime.parse(timestamp));
    }

    @Test
    void rangeFramesComputeTheBoundsInTheTypeOfTheKey() {
        FrameBound current = bound(BoundKind.CURRENT_ROW);
        FrameBound p1 = bound(BoundKind.PRECEDING, DataType.INTEGER, 1);
        FrameBound f1 = bound(BoundKind.FOLLOWING, DataType.INTEGER, 1);
        assertEquals(List.of(List.of(0L, 1L, 1L, 3L, 1L), List.of(2L, 1L, 1L, 0L, 0L), List.of(0L, 1L, 1L, 2L, 0L),
                List.of(0L, 1L, 1L, 0L, 0L), List.of(0L, 1L, 1L, 2L, 0L)),
                counts(overKeys(DataType.INTEGER, List.of(1, 2, 2, 3, 5), true,
                        range(bound(BoundKind.PRECEDING, DataType.INTEGER, 2), p1), range(f1, f1), range(p1, p1),
                        frame(FrameUnit.RANGE, bound(BoundKind.PRECEDING, DataType.INTEGER, 0),
                                bound(BoundKind.FOLLOWING, DataType.INTEGER, 0), FrameExclusion.CURRENT_ROW),
                        frame(FrameUnit.RANGE, p1, current, FrameExclusion.GROUP))));

        // Doubles in the order of DuckDB: NaN above infinity, which stays where it is.
        Frame near = range(bound(BoundKind.PRECEDING, DataType.DOUBLE, 0.5),
                bound(BoundKind.FOLLOWING, DataType.DOUBLE, 0.25));
        List<Object> doubles = Arrays.asList(1.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0.75, null);
        assertEquals(List.of(List.of(1L, 2L, 2L, 2L, 1L, 1L, 1L)), counts(overKeys(DataType.DOUBLE, doubles, true,
                near)));
        assertEquals(List.of(List.of(1L, 1L, 1L, 3L, 2L, 1L, 1L)), counts(overKeys(DataType.DOUBLE, doubles,
                false, near)));
        assertEquals(List.of(List.of(2L, 2L, 2L, 1L)), counts(overKeys(DataType.decimal(5, 2),
                Arrays.asList(new BigDecimal("1.00"), new BigDecimal("1.50"), new BigDecimal("0.75"), null), true,
                range(bound(BoundKind.PRECEDING, DataType.decimal(2, 1), new BigDecimal("0.5")),
                        bound(BoundKind.FOLLOWING, DataType.decimal(3, 2), new BigDecimal("0.25"))))));

        // Months end within a shorter month.
        List<Object> dates = Arrays.asList(day("2024-01-31"), day("2024-02-29"), day("2024-03-31"),
                day("2024-03-01"), day("2024-02-28"), null);
        Frame month = range(bound(BoundKind.PRECEDING, 1, IntervalUnit.MONTH), current);
        assertEquals(Arrays.asList("2024-01-31", "2024-01-31,2024-02-28", "2024-01-31,2024-02-28,2024-02-29",
                "2024-02-28,2024-02-29,2024-03-01", "2024-02-29,2024-03-01,2024-03-31", null),
                column(overKeys(DataType.DATE, dates, true, month), 2));
        assertEquals(Arrays.asList("2024-03-31", "2024-03-31,2024-03-01", "2024-03-01,2024-02-29",
                "2024-03-01,2024-02-29,2024-02-28", "2024-02-29,2024-02-28,2024-01-31", null),
                column(overKeys(DataType.DATE, dates, false, month), 2));
        assertEquals(List.of(List.of(2L, 2L, 2L, 2L), List.of(1L, 2L, 3L, 3L)), counts(overKeys(DataType.DATE,
                List.of(day("2024-02-29"), day("2023-02-28"), day("2023-03-01"), day("2024-03-01")), true,
                range(bound(BoundKind.PRECEDING, 1, IntervalUnit.DAY), bound(BoundKind.FOLLOWING, 1, IntervalUnit.DAY)),
                range(bound(BoundKind.PRECEDING, 1, IntervalUnit.YEAR), current))));
        // A date moves as a timestamp.
        assertEquals(List.of(List.of(1L, 2L, 2L)), counts(overKeys(DataType.DATE,
                List.of(day("2024-02-29"), day("2024-02-28"), day("2024-02-27")), true,
                range(bound(BoundKind.PRECEDING, 36, IntervalUnit.HOUR), current))));
        assertEquals(List.of(List.of(1L, 2L, 2L, 3L, 2L), List.of(1L, 2L, 3L, 4L, 4L)), counts(overKeys(
                DataType.TIMESTAMP, List.of(micros("2024-03-31T10:00:00"), micros("2024-03-31T08:30:00"),
                        micros("2024-03-31T08:29:59.999999"), micros("2024-03-31T10:00:00.000001"),
                        micros("2024-02-29T10:00:00")), true,
                range(bound(BoundKind.PRECEDING, 90, IntervalUnit.MINUTE),
                        bound(BoundKind.FOLLOWING, 1, IntervalUnit.MICROSECOND)),
                range(bound(BoundKind.PRECEDING, 1, IntervalUnit.MONTH), current))));
    }

    @Test
    void aRangeBoundThatOverflowsOrANegativeOffsetFails() {
        FrameBound current = bound(BoundKind.CURRENT_ROW);
        QueryException e = assertThrows(QueryException.class, () -> overKeys(DataType.BIGINT,
                List.of(Long.MAX_VALUE, Long.MIN_VALUE, 0L), true,
                range(bound(BoundKind.PRECEDING, DataType.BIGINT, 2L), current)));
        assertTrue(e.getMessage().contains("Overflow in subtraction of BIGINT (-9223372036854775808 - 2)"),
                e.getMessage());
        e = assertThrows(QueryException.class, () -> overKeys(DataType.TINYINT, List.of((byte) 127, (byte) 0), true,
                range(bound(BoundKind.PRECEDING, DataType.SMALLINT, (short) 1000),
                        bound(BoundKind.FOLLOWING, DataType.TINYINT, (byte) 1))));
        assertTrue(e.getMessage().contains("Overflow in addition of TINYINT (127 + 1)"), e.getMessage());
        // Within the type of the key and the offset, there is no overflow.
        assertEquals(List.of(List.of(1L, 2L)), counts(overKeys(DataType.TINYINT, List.of((byte) 127, (byte) 0), true,
                range(bound(BoundKind.PRECEDING, DataType.SMALLINT, (short) 1000),
                        bound(BoundKind.FOLLOWING, DataType.SMALLINT, (short) 1)))));
        Frame negative = range(bound(BoundKind.PRECEDING, DataType.INTEGER, -1), current);
        e = assertThrows(QueryException.class, () -> overKeys(DataType.INTEGER, List.of(1, 2), true, negative));
        assertTrue(e.getMessage().contains("Invalid RANGE PRECEDING value"), e.getMessage());
        // Only a key that is not NULL computes a bound.
        assertEquals(List.of(List.of(2L, 2L)), counts(overKeys(DataType.INTEGER, Arrays.asList(null, null), true,
                negative)));
        e = assertThrows(QueryException.class, () -> overKeys(DataType.DATE, List.of(day("2024-01-01")), true,
                range(bound(BoundKind.PRECEDING, 1, IntervalUnit.DAY), bound(BoundKind.FOLLOWING, -1,
                        IntervalUnit.DAY))));
        assertTrue(e.getMessage().contains("Invalid RANGE FOLLOWING value"), e.getMessage());
    }

    @Test
    void groupFramesCountGroupsOfPeers() {
        long max = Long.MAX_VALUE;
        FrameBound current = bound(BoundKind.CURRENT_ROW);
        PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                aggregate(AggregateFunction.SUM, X, groups(bound(BoundKind.PRECEDING, 1),
                        bound(BoundKind.FOLLOWING, 1)), "a"),
                aggregate(AggregateFunction.SUM, X, groups(bound(BoundKind.FOLLOWING, 2),
                        bound(BoundKind.FOLLOWING, 3)), "b"),
                aggregate(AggregateFunction.SUM, X, groups(bound(BoundKind.PRECEDING, 0),
                        bound(BoundKind.FOLLOWING, 0)), "c"),
                aggregate(AggregateFunction.SUM, X, groups(bound(BoundKind.PRECEDING, max),
                        bound(BoundKind.PRECEDING, 1)), "d"),
                aggregate(AggregateFunction.SUM, X, groups(current, bound(BoundKind.FOLLOWING, max)), "e")));
        List<List<Object>> r = results(w);
        assertEquals(Arrays.asList(10, null, 30, 40, null, 80, 50, 60, 70), column(r, 0));
        assertEquals(bigs(40L, 80L, 80L, 70L, 120L, 80L, 110L, 110L, 70L), column(r, 1));
        assertEquals(bigs(40L, 80L, 80L, 80L, null, null, null, null, null), column(r, 2));
        assertEquals(bigs(10L, 30L, 30L, 40L, null, 80L, 50L, 60L, 70L), column(r, 3));
        assertEquals(bigs(null, 10L, 10L, 40L, 80L, 80L, null, 50L, null), column(r, 4));
        assertEquals(bigs(160L, 150L, 150L, 120L, 80L, 80L, 110L, 60L, 70L), column(r, 5));
        // Without an order, the partition is one group.
        PlanNode.Window whole = new PlanNode.Window(MORE, List.of(P), List.of(), List.of(
                aggregate(AggregateFunction.SUM, X, groups(bound(BoundKind.PRECEDING, 1), current), "s")));
        assertEquals(bigs(160L, 160L, 160L, 160L, 160L, 160L, 110L, 110L, 70L), column(results(whole), 1));
        PlanNode.Window desc = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.desc(O)), List.of(
                aggregate(AggregateFunction.SUM, X, frame(FrameUnit.GROUPS, bound(BoundKind.PRECEDING, 2),
                        bound(BoundKind.PRECEDING, 1), FrameExclusion.TIES), "s")));
        assertEquals(List.of(row(null, null), row(40, null), row(null, big(40)), row(30, big(40)), row(10, big(70)),
                row(80, big(40)), row(50, null), row(60, big(50)), row(70, null)), results(desc));
        PlanNode.Window negative = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                aggregate(AggregateFunction.SUM, X, groups(current, bound(BoundKind.FOLLOWING, -1)), "s")));
        QueryException e = assertThrows(QueryException.class, () -> run(negative));
        assertTrue(e.getMessage().contains("Invalid GROUPS FOLLOWING value"), e.getMessage());
        // nth_value checks the frame also where no position reaches it, as DuckDB does.
        for (int position : List.of(ZERO, MINUS)) {
            PlanNode.Window nth = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                    value(WindowFunction.NTH_VALUE, List.of(X, position), groups(bound(BoundKind.FOLLOWING, 0),
                            bound(BoundKind.FOLLOWING, -1)))));
            QueryException n = assertThrows(QueryException.class, () -> run(nth));
            assertTrue(n.getMessage().contains("Invalid GROUPS FOLLOWING value"), n.getMessage());
        }
    }

    @Test
    void anExclusionCutsRowsOutOfTheFrame() {
        Map<FrameExclusion, List<List<Object>>> byExclusion = new EnumMap<>(FrameExclusion.class);
        byExclusion.put(FrameExclusion.CURRENT_ROW, List.of(
                row(10, null, null, null, null, null, 1L),
                row(null, big(40), "10,30", 10, 30, 30, 2L),
                row(30, big(40), "40", null, 40, 40, 2L),
                row(40, big(30), "30", 30, null, null, 2L),
                row(null, big(120), "40,80", 40, 80, 80, 2L),
                row(80, null, null, null, null, null, 1L),
                row(50, big(60), "60", 60, 60, null, 1L),
                row(60, big(50), "50", 50, 50, null, 1L),
                row(70, null, null, null, null, null, 0L)));
        byExclusion.put(FrameExclusion.GROUP, List.of(
                row(10, null, null, null, null, null, 1L),
                row(null, big(10), "10", 10, 10, null, 1L),
                row(30, big(40), "40", 40, 40, null, 1L),
                row(40, big(30), "30", 30, null, null, 2L),
                row(null, big(120), "40,80", 40, 80, 80, 2L),
                row(80, null, null, null, null, null, 1L),
                row(50, big(60), "60", 60, 60, null, 1L),
                row(60, big(50), "50", 50, 50, null, 1L),
                row(70, null, null, null, null, null, 0L)));
        byExclusion.put(FrameExclusion.TIES, List.of(
                row(10, big(10), "10", 10, null, null, 2L),
                row(null, big(10), "10", 10, null, null, 2L),
                row(30, big(70), "30,40", 30, 40, 40, 2L),
                row(40, big(70), "30,40", 30, null, 40, 3L),
                row(null, big(120), "40,80", 40, 80, null, 3L),
                row(80, big(80), "80", null, 80, 80, 2L),
                row(50, big(110), "50,60", 50, 60, 60, 2L),
                row(60, big(110), "50,60", 50, 60, 60, 2L),
                row(70, big(70), "70", 70, 70, null, 1L)));
        for (Map.Entry<FrameExclusion, List<List<Object>>> e : byExclusion.entrySet()) {
            Frame f = frame(FrameUnit.ROWS, bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 1), e.getKey());
            PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                    aggregate(AggregateFunction.SUM, X, f, "sum"),
                    aggregate(AggregateFunction.STRING_AGG, X, f, "string_agg"),
                    value(WindowFunction.FIRST_VALUE, List.of(X), f),
                    value(WindowFunction.LAST_VALUE, List.of(X), f),
                    value(WindowFunction.NTH_VALUE, List.of(X, TWO), f),
                    countStar(f)));
            assertEquals(e.getValue(), results(w), e.getKey().toString());
        }

        // The current row lies outside the frame; its ties are cut out.
        Frame ahead = frame(FrameUnit.ROWS, bound(BoundKind.FOLLOWING, 2), bound(BoundKind.FOLLOWING, 3),
                FrameExclusion.TIES);
        PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                aggregate(AggregateFunction.SUM, X, ahead, "sum"),
                aggregate(AggregateFunction.STRING_AGG, X, ahead, "string_agg"),
                value(WindowFunction.FIRST_VALUE, List.of(X), ahead),
                value(WindowFunction.LAST_VALUE, List.of(X), ahead),
                countStar(ahead)));
        assertEquals(List.of(
                row(10, big(70), "30,40", 30, 40, 2L),
                row(null, big(40), "40", 40, null, 2L),
                row(30, big(80), "80", null, 80, 2L),
                row(40, big(80), "80", 80, 80, 1L),
                row(null, null, null, null, null, 0L),
                row(80, null, null, null, null, 0L),
                row(50, null, null, null, null, 0L),
                row(60, null, null, null, null, 0L),
                row(70, null, null, null, null, 0L)), results(w));

        Frame others = frame(FrameUnit.RANGE, bound(BoundKind.UNBOUNDED_PRECEDING),
                bound(BoundKind.UNBOUNDED_FOLLOWING), FrameExclusion.CURRENT_ROW);
        PlanNode.Window all = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O)), List.of(
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT, X, true, "distinct"), others),
                aggregate(AggregateFunction.LIST, X, others, "list"),
                aggregate(AggregateFunction.MIN, X, others, "min")));
        assertEquals(List.of(
                row(10, 3L, Arrays.asList(null, 30, 40, null, 80), 30),
                row(null, 4L, Arrays.asList(10, 30, 40, null, 80), 10),
                row(30, 3L, Arrays.asList(10, null, 40, null, 80), 10),
                row(40, 3L, Arrays.asList(10, null, 30, null, 80), 10),
                row(null, 4L, Arrays.asList(10, null, 30, 40, 80), 10),
                row(80, 3L, Arrays.asList(10, null, 30, 40, null), 10),
                row(50, 1L, List.of(60), 60),
                row(60, 1L, List.of(50), 50),
                row(70, 0L, null, null)), results(all));
    }

    @Test
    void ignoreNullsCountsOnlyTheValuesThatAreNotNull() {
        PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(new SortKey(O, true, true),
                SortKey.asc(X)), List.of(
                ignoringNulls(WindowFunction.LAG, List.of(X), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LEAD, List.of(X), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LAG, List.of(X, TWO), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LAG, List.of(X, K, MINUS), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LEAD, List.of(X, K, MINUS), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LAG, List.of(X, ZERO), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LEAD, List.of(X, ZERO), Frame.DEFAULT),
                ignoringNulls(WindowFunction.LAG, List.of(X, MINUS), Frame.DEFAULT)));
        // An offset of 0 is the current row, even if its value is NULL.
        assertEquals(List.of(
                row(80, null, 10, null, -1, 30, 80, 80, 10),
                row(10, 80, 30, null, 80, 30, 10, 10, 30),
                row(30, 10, 40, 80, null, null, 30, 30, 40),
                row(null, 30, 40, 10, 10, -1, null, null, 40),
                row(40, 30, null, 10, -1, 30, 40, 40, null),
                row(null, 40, null, 30, 40, -1, null, null, null),
                row(60, null, 50, null, -1, -1, 60, 60, 50),
                row(50, 60, null, null, 50, 50, 50, 50, null),
                row(70, null, null, null, -1, -1, 70, 70, null)), results(w));

        Frame near = rows(bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 2));
        Frame others = frame(FrameUnit.ROWS, bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 2),
                FrameExclusion.CURRENT_ROW);
        PlanNode.Window framed = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O), SortKey.asc(X)),
                List.of(
                        ignoringNulls(WindowFunction.FIRST_VALUE, List.of(X), near),
                        ignoringNulls(WindowFunction.LAST_VALUE, List.of(X), near),
                        ignoringNulls(WindowFunction.NTH_VALUE, List.of(X, TWO), near),
                        ignoringNulls(WindowFunction.NTH_VALUE, List.of(X, K), near),
                        ignoringNulls(WindowFunction.FIRST_VALUE, List.of(X), others),
                        ignoringNulls(WindowFunction.LAST_VALUE, List.of(X), others),
                        ignoringNulls(WindowFunction.NTH_VALUE, List.of(X, TWO), others)));
        assertEquals(List.of(
                row(10, 10, 30, 30, 10, 30, 30, null),
                row(30, 10, 40, 30, null, 10, 40, 40),
                row(null, 30, 40, 40, 40, 30, 40, 40),
                row(40, 40, 80, 80, null, 80, 80, null),
                row(null, 40, 80, 80, 40, 40, 80, 80),
                row(80, 80, 80, null, null, null, null, null),
                row(50, 50, 60, 60, null, 60, 60, null),
                row(60, 50, 60, 60, null, 50, 50, null),
                row(70, 70, 70, null, 70, null, null, null)), results(framed));
    }

    @Test
    void aFilterLeavesRowsOutOfAnAggregate() {
        Frame near = rows(bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 1));
        PlanNode.Window w = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O), SortKey.asc(X)), List.of(
                filtered(new AggregateCall(AggregateFunction.SUM, X, false, "sum"), GT20, near),
                filtered(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_star"), KPOS, near),
                filtered(new AggregateCall(AggregateFunction.COUNT, P, true, "distinct"), KNN, near),
                filtered(new AggregateCall(AggregateFunction.STRING_AGG, X, false, "string_agg"), NE30, near),
                filtered(new AggregateCall(AggregateFunction.MIN, X, false, "min"), KPOS, near),
                filtered(new AggregateCall(AggregateFunction.ANY_VALUE, X, false, "any_value"), GT10, near)));
        assertEquals(List.of(
                row(10, big(30), 1L, 1L, "10", 10, 30),
                row(30, big(30), 2L, 1L, "10", 10, 30),
                row(null, big(70), 1L, 1L, "40", null, 30),
                row(40, big(40), 2L, 1L, "40", null, 40),
                row(null, big(120), 2L, 1L, "40,80", 80, 40),
                row(80, big(80), 2L, 1L, "80", 80, 80),
                row(50, big(110), 1L, 1L, "50,60", 60, 50),
                row(60, big(110), 1L, 1L, "50,60", 60, 50),
                row(70, big(70), 1L, 0L, "70", 70, 70)), results(w));

        Frame others = frame(FrameUnit.ROWS, bound(BoundKind.PRECEDING, 1), bound(BoundKind.FOLLOWING, 1),
                FrameExclusion.CURRENT_ROW);
        PlanNode.Window excluded = new PlanNode.Window(MORE, List.of(P), List.of(SortKey.asc(O), SortKey.asc(X)),
                List.of(
                        filtered(new AggregateCall(AggregateFunction.SUM, X, false, "sum"), GT20, others),
                        filtered(new AggregateCall(AggregateFunction.COUNT, X, false, "count"), GT20, others),
                        filtered(new AggregateCall(AggregateFunction.LIST, X, false, "list", null,
                                List.of(SortKey.asc(X))), GT10, others),
                        WindowCall.of(new AggregateCall(AggregateFunction.STRING_AGG, X, false, "string_agg", ",",
                                List.of(SortKey.desc(X))), others)));
        assertEquals(List.of(
                row(10, big(30), 1L, List.of(30), "30"),
                row(30, null, 0L, null, "10"),
                row(null, big(70), 2L, List.of(30, 40), "40,30"),
                row(40, null, 0L, null, null),
                row(null, big(120), 2L, List.of(40, 80), "80,40"),
                row(80, null, 0L, null, null),
                row(50, big(60), 1L, List.of(60), "60"),
                row(60, big(50), 1L, List.of(50), "50"),
                row(70, null, 0L, null, null)), results(excluded));
    }

    @Test
    void aCanceledQueryStopsWhileTheFramesAreComputed() {
        Schema s = Schema.of(new Schema.Field("x", DataType.INTEGER));
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            data.add(new Object[] {i});
        }
        PlanNode.Values values = new PlanNode.Values(s, data);
        // Each row aggregates the rows before it one by one.
        PlanNode.Window w = new PlanNode.Window(values, List.of(), List.of(SortKey.asc(0)), List.of(aggregate(
                AggregateFunction.STRING_AGG, 0, rows(bound(BoundKind.UNBOUNDED_PRECEDING),
                        bound(BoundKind.CURRENT_ROW)), "s")));
        QueryContext context = new QueryContext();
        ValuesOperator source = new ValuesOperator("Values#1", values, 1024);
        // The input cancels when it has delivered its rows, which are too few to check while they are sorted.
        Operator input = new Operator() {
            @Override
            public Schema schema() {
                return s;
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
        WindowOperator op = new WindowOperator("Window#0", w, input, MemoryBudget.unlimited(), 1024);
        op.attach(context);
        QueryCancelledException e = assertThrows(QueryCancelledException.class, op::next);
        assertEquals("Window#0", e.operator());
        assertTrue(Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getClassName().equals(
                WindowOperator.class.getName()) && f.getMethodName().equals("step")), "stopped in a frame");
        op.close();
    }

    @Test
    void theBudgetGetsBackTheValuesOfEachFrame() {
        Schema s = Schema.of(new Schema.Field("x", DataType.INTEGER));
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            data.add(new Object[] {i % 10});
        }
        // Two million values over all frames, two thousand at a time; the strings of the results take 4 MB.
        PlanNode.Window w = new PlanNode.Window(new PlanNode.Values(s, data), List.of(), List.of(SortKey.asc(0)),
                List.of(aggregate(AggregateFunction.STRING_AGG, 0, rows(bound(BoundKind.UNBOUNDED_PRECEDING),
                        bound(BoundKind.CURRENT_ROW)), "s"), aggregate(AggregateFunction.SUM, 0,
                        rows(bound(BoundKind.PRECEDING, 5), bound(BoundKind.FOLLOWING, 5)), "sum")));
        MemoryBudget budget = new MemoryBudget(8 << 20);
        List<Batch> out = executor(budget, 1024).execute(w);
        assertEquals(2000, out.stream().mapToInt(Batch::rowCount).sum());
        assertEquals(0, budget.used());
        MemoryBudget small = new MemoryBudget(2 << 20);
        QueryException e = assertThrows(QueryException.class, () -> executor(small, 1024).execute(w));
        assertTrue(e.getMessage().startsWith("Memory budget of 2097152 bytes exceeded"), e.getMessage());
        assertEquals(0, small.used());
    }

    @Test
    void theResultsOfTheFramesTakeTheBudget() {
        Schema s = Schema.of(new Schema.Field("v", DataType.VARCHAR), new Schema.Field("w", DataType.VARCHAR));
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            data.add(new Object[] {"v" + i % 10, String.format("%04d", i).repeat(250)});
        }
        PlanNode.Values values = new PlanNode.Values(s, data);
        Frame growing = rows(bound(BoundKind.UNBOUNDED_PRECEDING), bound(BoundKind.CURRENT_ROW));
        // A list of half a million strings over all frames, about 25 MB, and a string of 1000 characters for
        // each row, about 1 MB, while the frames themselves keep at most a thousand values.
        List<Map.Entry<WindowCall, Long>> calls = List.of(
                Map.entry(WindowCall.of(new AggregateCall(AggregateFunction.LIST, 0, false, "list", null,
                        List.of(SortKey.desc(0))), growing), 4L << 20),
                Map.entry(aggregate(AggregateFunction.MAX, 1, growing, "max"), 1536L << 10));
        for (Map.Entry<WindowCall, Long> c : calls) {
            PlanNode.Window w = new PlanNode.Window(values, List.of(), List.of(SortKey.asc(1)),
                    List.of(c.getKey()));
            MemoryBudget budget = new MemoryBudget(64 << 20);
            List<Batch> out = executor(budget, 1024).execute(w);
            assertEquals(1000, out.stream().mapToInt(Batch::rowCount).sum());
            assertEquals(0, budget.used());
            MemoryBudget small = new MemoryBudget(c.getValue());
            QueryException e = assertThrows(QueryException.class, () -> executor(small, 1024).execute(w),
                    c.getKey().name());
            assertTrue(e.getMessage().startsWith("Memory budget of " + c.getValue() + " bytes exceeded"),
                    e.getMessage());
            assertEquals(0, small.used());
        }
    }

    @Test
    void theBytesOfAResultGrowWithWhatItHolds() {
        assertEquals(0, ValueAccumulator.resultBytes(null));
        assertEquals(0, ValueAccumulator.resultBytes(42L));
        assertEquals(0, ValueAccumulator.resultBytes(new BigDecimal("1.5")));
        assertEquals(43, ValueAccumulator.resultBytes("abc"));
        assertEquals(20, ValueAccumulator.resultBytes(new byte[4]));
        assertEquals(16 + 3 * 8 + 41 + 42, ValueAccumulator.resultBytes(Arrays.asList("a", null, "bc")));
        assertEquals(16 + 8 + 16 + 8 + 41, ValueAccumulator.resultBytes(List.of(List.of("a"))));
        assertEquals(32 + 32 + 41, ValueAccumulator.resultBytes(Map.of("a", 1)));
    }
}
