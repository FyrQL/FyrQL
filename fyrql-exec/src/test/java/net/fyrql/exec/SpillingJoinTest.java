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
import net.fyrql.core.plan.PlanNode.JoinType;
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
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joins whose build side does not fit in the budget spill both sides to
 * disk and join them partition by partition, cutting those that do not fit
 * into smaller ones: the same rows in the same order as in memory, for
 * every type of join with keys, with and without a condition; the budget is
 * given back and the files are deleted, also after a failure or a cancel.
 */
class SpillingJoinTest {

    /** The probe side: a key, a text key, and a value. */
    private static final Schema P = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("x", DataType.BIGINT));

    /** The build sides, with the columns of the probe side. */
    private static final Schema B = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("y", DataType.BIGINT));

    @TempDir
    Path temp;

    private final SplitTables source = new SplitTables();

    /** Rows with keys of {@code many} values, so that rows match, some more than once, with rare {@code NULL}s. */
    private static List<Object[]> rows(SplittableRandom random, int count, int many, boolean unique) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {unique ? Integer.valueOf(i) : random.nextInt(50) == 0 ? null : random.nextInt(many),
                random.nextInt(50) == 0 ? null : "s" + (unique ? i : random.nextInt(many)),
                random.nextInt(10) == 0 ? null : (long) random.nextInt(10)});
        }
        return rows;
    }

    /** Split sizes that add up to a count. */
    private static int[] splits(SplittableRandom random, int count) {
        List<Integer> sizes = new ArrayList<>();
        int left = count;
        while (left > 0) {
            int size = Math.min(left, random.nextInt(5) == 0 ? 0 : 1 + random.nextInt(300));
            sizes.add(size);
            left -= size;
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static PlanNode.Join join(PlanNode left, PlanNode right, List<Integer> keys, JoinType type,
            boolean condition) {
        Schema pair = left.schema().concat(right.schema());
        return new PlanNode.Join(left, right, keys, keys, type, condition
                ? gt(col(pair, 2), col(pair, left.schema().size() + 2)) : null);
    }

    /** Runs a plan on one thread; the budget is given back and no file is left. */
    private List<List<Object>> run(PlanNode plan, long budget, SpillDirectory spill) {
        return run(plan, budget, spill, 1, true);
    }

    /** Runs a plan; the budget is given back and no file is left. */
    private List<List<Object>> run(PlanNode plan, long budget, SpillDirectory spill, int threads,
            boolean preserveOrder) {
        MemoryBudget b = new MemoryBudget(budget);
        Executor executor = new Executor(source, b, 64, Codegen.off(), threads, preserveOrder, spill);
        List<List<Object>> rows = new ArrayList<>();
        try (Operator op = executor.open(plan)) {
            for (Batch batch = op.next(); batch != null; batch = op.next()) {
                for (Object[] row : batch.rows()) {
                    rows.add(Arrays.asList(row));
                }
            }
        } catch (QueryException e) {
            rows.add(List.of("error", e.operator(), e.getMessage()));
        }
        assertEquals(0, b.used(), "the budget is given back");
        assertNoFilesLeft(spill);
        return rows;
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

    /** Asserts equal rows in the same order, naming the first that differs rather than all of them. */
    private static void assertRows(List<List<Object>> expected, List<List<Object>> actual, String what) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                throw new AssertionError(what + ": row " + i + " of " + expected.size() + " is " + actual.get(i)
                        + " instead of " + expected.get(i) + " (" + actual.size() + " rows)");
            }
        }
        assertEquals(expected.size(), actual.size(), what + ": rows");
    }

    private static boolean failed(List<List<Object>> rows) {
        return !rows.isEmpty() && "error".equals(rows.get(rows.size() - 1).get(0));
    }

    @Test
    void everyJoinOverTheBudgetGivesWhatItGivesInMemory() {
        SplittableRandom random = new SplittableRandom(20261028);
        long refined = GraceHashJoin.REFINED.get();
        int runs = 0;
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (JoinType type : JoinType.values()) {
                if (type == JoinType.CROSS) {
                    continue;
                }
                // An integer key; a text key with a condition; both keys.
                for (int variant = 0; variant < 3; variant++) {
                    List<Integer> keys = List.of(List.of(0), List.of(1), List.of(0, 1)).get(variant);
                    boolean condition = variant == 1;
                    boolean unique = type == JoinType.SINGLE && !condition;
                    List<Object[]> probeRows = rows(random, 1500 + random.nextInt(1000), 1500, false);
                    List<Object[]> buildRows = rows(random, 5000 + random.nextInt(2000), 1500, unique);
                    PlanNode.Scan probe = source.table("p" + runs, P, probeRows, splits(random, probeRows.size()));
                    PlanNode.Scan build = source.table("b" + runs, B, buildRows, splits(random, buildRows.size()));
                    long total = Batches.estimateBytes(Batch.ofRows(B, buildRows));
                    PlanNode.Join j = join(probe, build, keys, type, condition);
                    Schema s = j.schema();
                    List<PlanNode> plans = List.of(j, new PlanNode.Filter(j, or(isNull(col(s, 0)), gt(col(s, 0),
                            lit(400)))));
                    for (PlanNode plan : plans) {
                        List<List<Object>> expected = run(plan, Long.MAX_VALUE, null);
                        // In the smallest budget every partition is cut, which takes hundreds of files.
                        long[] budgets = plan != j ? new long[] {total / 4}
                                : variant == 0 ? new long[] {total / 2, total / 4, 16 * 1024}
                                : new long[] {total / 2, total / 4};
                        for (long budget : budgets) {
                            String what = type + " on " + keys + (condition ? " with a condition" : "")
                                    + ", budget " + budget;
                            long parts = spill.statistics().parts();
                            List<List<Object>> actual = run(plan, budget, spill);
                            if (failed(expected)) {
                                // A SINGLE join fails as in memory, though after other rows there.
                                assertTrue(failed(actual), what);
                                assertEquals(expected.get(expected.size() - 1), actual.get(actual.size() - 1),
                                        what);
                            } else {
                                assertRows(expected, actual, what);
                            }
                            assertTrue(spill.statistics().parts() > parts, what + ": rows went to disk");
                        }
                        if (plan == j) {
                            // On workers, whose segments read both sides: in order, or in any order for a condition.
                            long budget = total / 4;
                            boolean preserveOrder = !condition;
                            String what = type + " on " + keys + ", budget " + budget + ", 12 threads"
                                    + (preserveOrder ? "" : ", order not preserved");
                            List<List<Object>> actual = run(plan, budget, spill, 12, preserveOrder);
                            if (failed(expected)) {
                                assertEquals(expected.get(expected.size() - 1), actual.get(actual.size() - 1), what);
                            } else {
                                assertRows(preserveOrder ? expected : sorted(expected),
                                        preserveOrder ? actual : sorted(actual), what);
                            }
                        }
                        runs++;
                    }
                }
            }
        }
        assertTrue(runs > 40, runs + " runs");
        assertTrue(GraceHashJoin.REFINED.get() > refined, "partitions that did not fit were cut");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void joinsOfJoinsSpillToo() {
        SplittableRandom random = new SplittableRandom(7);
        List<Object[]> probeRows = rows(random, 5000, 600, false);
        PlanNode.Scan probe = source.table("p", P, probeRows, splits(random, probeRows.size()));
        PlanNode.Scan b = source.table("b", B, rows(random, 3000, 600, false), splits(random, 3000));
        PlanNode.Scan c = source.table("c", B, rows(random, 2000, 600, false), splits(random, 2000));
        PlanNode.Join inner = join(probe, b, List.of(0), JoinType.LEFT, false);
        // Above a join; and with a join as build side.
        PlanNode.Join above = new PlanNode.Join(inner, c, List.of(1), List.of(1), JoinType.FULL);
        PlanNode.Join bc = join(b, c, List.of(0), JoinType.INNER, false);
        PlanNode.Join buildJoin = new PlanNode.Join(probe, new PlanNode.Project(bc, List.of(
                new PlanNode.NamedExpression("k", col(bc.schema(), 0)))), List.of(0), List.of(0), JoinType.RIGHT);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (PlanNode plan : List.of(above, buildJoin)) {
                List<List<Object>> expected = run(plan, Long.MAX_VALUE, null);
                for (long budget : new long[] {64 * 1024, 16 * 1024}) {
                    String what = (plan == above ? "above" : "build join") + ", budget " + budget;
                    assertRows(expected, run(plan, budget, spill), what);
                    assertRows(expected, run(plan, budget, spill, 12, true), what + ", 12 threads");
                    assertRows(sorted(expected), sorted(run(plan, budget, spill, 12, false)), what
                            + ", 12 threads, order not preserved");
                }
            }
            assertTrue(spill.statistics().parts() > 0);
        }
    }

    @Test
    void theOutputComesUnorderedWhereTheOrderDoesNotMatter() {
        SplittableRandom random = new SplittableRandom(11);
        List<Batch> probe = Batches.split(Batch.ofRows(P, rows(random, 6000, 900, false)), 100);
        List<Batch> build = Batches.split(Batch.ofRows(B, rows(random, 3000, 900, false)), 100);
        PlanNode.Values p = new PlanNode.Values(P, List.of());
        PlanNode.Values b = new PlanNode.Values(B, List.of());
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            for (JoinType type : List.of(JoinType.INNER, JoinType.FULL, JoinType.MARK, JoinType.ANTI)) {
                PlanNode.Join j = join(p, b, List.of(0), type, true);
                List<List<Object>> expected = runOperator(j, probe, build, Long.MAX_VALUE, true, null);
                List<List<Object>> ordered = runOperator(j, probe, build, 32 * 1024, true, spill);
                List<List<Object>> unordered = runOperator(j, probe, build, 32 * 1024, false, spill);
                assertRows(expected, ordered, type.toString());
                assertRows(sorted(expected), sorted(unordered), type + " unordered");
            }
        }
    }

    private List<List<Object>> runOperator(PlanNode.Join j, List<Batch> probe, List<Batch> build, long limit,
            boolean ordered, SpillDirectory spill) {
        MemoryBudget budget = new MemoryBudget(limit);
        HashJoinOperator op = new HashJoinOperator("Join#0", j, input(P, probe, null, -1), input(B, build, null, -1),
                budget, Codegen.off(), 64, ordered, spill);
        op.attach(new QueryContext());
        List<List<Object>> rows = new ArrayList<>();
        try (op) {
            for (Batch batch = op.next(); batch != null; batch = op.next()) {
                for (Object[] row : batch.rows()) {
                    rows.add(Arrays.asList(row));
                }
            }
        }
        assertEquals(0, budget.used(), "the budget is given back");
        assertNoFilesLeft(spill);
        return rows;
    }

    /** An input of batches that cancels the query when it is asked for a batch the {@code cancelAt}th time. */
    private static Operator input(Schema schema, List<Batch> batches, QueryContext context, int cancelAt) {
        return new Operator() {
            private int calls;

            @Override
            public Schema schema() {
                return schema;
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
    }

    @Test
    void aKeyThatDoesNotFitFailsAfterSpilling() {
        SplittableRandom random = new SplittableRandom(4);
        List<Object[]> buildRows = rows(random, 20_000, 1, false);
        for (Object[] row : buildRows) {
            row[0] = 1;
        }
        PlanNode.Scan probe = source.table("p", P, rows(random, 100, 3, false), 50, 50);
        PlanNode.Scan build = source.table("one", B, buildRows, 5000, 5000, 5000, 5000);
        PlanNode.Join j = join(probe, build, List.of(0), JoinType.INNER, false);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            long refined = GraceHashJoin.REFINED.get();
            List<List<Object>> rows = run(j, 64 * 1024, spill);
            assertEquals(1, rows.size(), rows::toString);
            assertEquals("Join#0", rows.get(0).get(1));
            assertTrue(((String) rows.get(0).get(2)).matches("Memory budget of 65536 bytes exceeded: \\d+ bytes"
                    + " needed after spilling \\d+ bytes in Join#0"), () -> rows.get(0).get(2).toString());
            assertEquals(refined, GraceHashJoin.REFINED.get(), "rows of one hash fail at once");
        }
        List<List<Object>> off = run(j, 64 * 1024, null);
        assertTrue(((String) off.get(0).get(2)).matches("Memory budget of 65536 bytes exceeded: \\d+ bytes needed"
                + " in Join#0"), () -> off.get(0).get(2).toString());
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aCanceledJoinDeletesItsFiles() {
        SplittableRandom random = new SplittableRandom(5);
        List<Batch> probe = Batches.split(Batch.ofRows(P, rows(random, 8000, 900, false)), 200);
        List<Batch> build = Batches.split(Batch.ofRows(B, rows(random, 4000, 900, false)), 200);
        PlanNode.Join j = join(new PlanNode.Values(P, List.of()), new PlanNode.Values(B, List.of()), List.of(0),
                JoinType.FULL, false);
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            // While the build side goes to disk, and while the probe side does.
            for (int[] at : new int[][] {{15, -1}, {-1, 20}}) {
                MemoryBudget budget = new MemoryBudget(32 * 1024);
                QueryContext context = new QueryContext();
                HashJoinOperator op = new HashJoinOperator("Join#1", j, input(P, probe, context, at[1]),
                        input(B, build, context, at[0]), budget, Codegen.off(), 64, true, spill);
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
