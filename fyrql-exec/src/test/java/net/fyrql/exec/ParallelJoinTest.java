/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.exec;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Joins in segments, on several threads, against one thread. */
class ParallelJoinTest {

    /** The probe side: a key, a text key, and a value. */
    private static final Schema P = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("x", DataType.BIGINT));

    /** The build sides, with the columns of the probe side. */
    private static final Schema B = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("y", DataType.BIGINT));

    private final SplitTables source = new SplitTables();

    /** Rows with few distinct keys, so that rows match, some more than once, with {@code NULL}; or one per key. */
    private static List<Object[]> rows(long seed, int count, boolean unique) {
        SplittableRandom random = new SplittableRandom(seed);
        String[] strings = {"a", "b", "c"};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {unique ? Integer.valueOf(i) : random.nextInt(10) == 0 ? null : random.nextInt(6),
                random.nextInt(10) == 0 ? null : unique ? "u" + i : strings[random.nextInt(3)],
                random.nextInt(10) == 0 ? null : (long) random.nextInt(10)});
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
                default -> 1 + random.nextInt(25);
            });
            sizes.add(size);
            left -= size;
        }
        sizes.add(0);
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    private PlanNode.Scan table(String name, Schema schema, List<Object[]> rows, long seed) {
        return source.table(name, schema, rows, splits(new SplittableRandom(seed), rows.size()));
    }

    private static PlanNode.Join join(PlanNode left, PlanNode right, List<Integer> keys, JoinType type,
            boolean condition) {
        Schema pair = left.schema().concat(right.schema());
        int x = left.schema().size() - 1;
        return new PlanNode.Join(left, right, keys, keys, type, condition
                ? gt(col(pair, x), col(pair, left.schema().size() + 2)) : null);
    }

    /**
     * The plans for a type of join: the join above a filter of the probe
     * side; a projection above it; a second join above it; a join whose
     * build side is a join itself; and a filter above it.
     */
    private List<PlanNode> plans(JoinType type, List<Integer> keys, boolean condition, List<Object[]> probe,
            List<Object[]> build, long seed) {
        PlanNode.Filter p = new PlanNode.Filter(table("p", P, probe, seed), or(isNull(col(P, 2)),
                ge(col(P, 2), lit(1L))));
        PlanNode.Scan b = table("b", B, build, seed + 1);
        PlanNode.Scan c = table("c", B, rows(seed + 2, 30, false), seed + 2);
        PlanNode.Join join = join(p, b, keys, type, condition);
        List<PlanNode> plans = new ArrayList<>();
        plans.add(join);
        Schema s = join.schema();
        plans.add(new PlanNode.Project(join, List.of(new NamedExpression("x1", add(col(s, 2), lit(1L))),
                new NamedExpression("k", col(s, 0)))));
        plans.add(new PlanNode.Filter(join, or(isNull(col(s, 0)), gt(col(s, 0), lit(1)))));
        if (!SegmentPlanner.keepsUnmatchedRight(join)) {
            plans.add(new PlanNode.Join(join, c, List.of(0), List.of(0), JoinType.INNER));
        }
        PlanNode.Join inner = new PlanNode.Join(b, c, List.of(0), List.of(0), JoinType.LEFT);
        PlanNode.Project buildJoin = new PlanNode.Project(inner, List.of(new NamedExpression("k", col(inner.schema(),
                0)), new NamedExpression("s", col(inner.schema(), 4)), new NamedExpression("y", col(inner.schema(),
                2))));
        plans.add(join(p, buildJoin, keys, type, condition));
        return plans;
    }

    private List<List<Object>> run(PlanNode plan, int threads, boolean preserveOrder, MemoryBudget budget) {
        Executor executor = new Executor(source, budget, 3, Codegen.off(), threads, preserveOrder);
        List<List<Object>> rows = new ArrayList<>();
        try (Operator op = executor.open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                for (Object[] row : b.rows()) {
                    rows.add(Arrays.asList(row));
                }
            }
        } catch (QueryException e) {
            rows.add(List.of("error", e.operator(), e.getMessage()));
        }
        return rows;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Object::toString));
        return copy;
    }

    @Test
    void joinsGiveWhatOneThreadGives() {
        List<List<Integer>> keySets = List.of(List.of(0), List.of(1), List.of(0, 1), List.of());
        MemoryBudget budget = new MemoryBudget(1L << 40);
        int runs = 0;
        int rows = 0;
        for (JoinType type : JoinType.values()) {
            for (List<Integer> keys : keySets) {
                for (boolean condition : new boolean[] {false, true}) {
                    if (type == JoinType.CROSS ? !keys.isEmpty() || condition : keys.isEmpty() && type.needsKeys()) {
                        continue;
                    }
                    for (int data = 0; data < 4; data++) {
                        long seed = runs * 7L;
                        List<Object[]> probe = data == 2 ? List.of() : rows(seed, 150, false);
                        List<Object[]> build = data == 1 ? List.of() : rows(seed + 1, data == 3 ? 6 : 40, data == 3);
                        for (PlanNode plan : plans(type, keys, condition, probe, build, seed)) {
                            List<List<Object>> expected = run(plan, 1, true, budget);
                            for (int threads : new int[] {2, 3, 8}) {
                                assertEquals(expected, run(plan, threads, true, budget), () -> threads + ": " + plan);
                            }
                            List<List<Object>> unordered = run(plan, 4, false, budget);
                            if (expected.stream().anyMatch(r -> "error".equals(r.get(0)))) {
                                assertEquals(expected.get(expected.size() - 1), unordered.get(unordered.size() - 1));
                            } else {
                                assertEquals(sorted(expected), sorted(unordered), plan::toString);
                            }
                            assertEquals(0, budget.used(), "the build sides are released");
                            runs++;
                            rows += expected.size();
                        }
                    }
                }
            }
        }
        assertTrue(rows > runs * 10, rows + " rows in " + runs + " runs");
        assertTrue(source.threads.stream().anyMatch(t -> t.startsWith("fyrql-worker-")), source.threads::toString);
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aSingleJoinFailsAsOnOneThread() {
        List<Object[]> probe = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            probe.add(new Object[] {k, String.valueOf(k), (long) k});
        }
        List<Object[]> build = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            build.add(new Object[] {k, String.valueOf(k), (long) k});
        }
        build.add(new Object[] {37, "37", 37L});
        int[] sizes = new int[12];
        Arrays.fill(sizes, 5);
        PlanNode.Join single = new PlanNode.Join(source.table("p", P, probe, sizes), table("b", B, build, 5),
                List.of(0), List.of(0), JoinType.SINGLE);
        List<List<Object>> expected = run(single, 1, true, MemoryBudget.unlimited());
        assertEquals(List.of("error", "Join#0", HashJoinOperator.MORE_THAN_ONE_ROW + " in Join#0"),
                expected.get(expected.size() - 1));
        assertEquals(35, expected.size() - 1, "the rows of the batches before the failed one");
        for (int i = 0; i < 10; i++) {
            assertEquals(expected, run(single, 4, true, MemoryBudget.unlimited()));
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void anEmptyBuildSideOfAnInnerJoinReadsNothingBelowIt() {
        PlanNode.Scan probe = table("p", P, rows(1, 100, false), 1);
        PlanNode.Scan empty = source.table("e", B, List.of(), 0, 0);
        PlanNode.Join lower = new PlanNode.Join(probe, table("c", B, rows(2, 30, false), 2), List.of(0),
                List.of(0), JoinType.INNER);
        PlanNode.Join upper = new PlanNode.Join(lower, empty, List.of(0), List.of(0), JoinType.SEMI);
        for (int threads : new int[] {1, 4}) {
            source.asked.clear();
            assertEquals(List.of(), run(upper, threads, true, MemoryBudget.unlimited()));
            assertEquals(Set.of("e"), source.asked, "the scans below the empty build side are not read");
        }
        PlanNode.Join right = new PlanNode.Join(lower, table("r", B, rows(3, 20, true), 3), List.of(0), List.of(0),
                JoinType.RIGHT);
        PlanNode.Join emptyBelow = new PlanNode.Join(new PlanNode.Join(probe, empty, List.of(0), List.of(0),
                JoinType.INNER), table("r", B, rows(3, 20, true), 3), List.of(0), List.of(0), JoinType.RIGHT);
        for (PlanNode plan : List.of(right, emptyBelow)) {
            List<List<Object>> expected = run(plan, 1, true, MemoryBudget.unlimited());
            assertFalse(expected.isEmpty());
            assertEquals(expected, run(plan, 4, true, MemoryBudget.unlimited()));
        }
    }
}
