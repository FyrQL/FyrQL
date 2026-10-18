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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Segments over scans with several splits, on several threads, against one thread. */
class ParallelSegmentTest {

    private static final Schema NUMBERS = SplitTables.NUMBERS;

    private final SplitTables source = new SplitTables();

    private List<List<Object>> rows(PlanNode plan, int threads, boolean preserveOrder) {
        Executor executor = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), threads, preserveOrder);
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : executor.rows(plan)) {
            rows.add(Arrays.asList(row));
        }
        return rows;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Object::toString));
        return copy;
    }

    private List<PlanNode> plans() {
        PlanNode.Scan a = source.table("a", 5, 0, 7, 1, 12, 3, 9);
        PlanNode.Scan b = source.table("b", 4, 6);
        PlanNode filter = new PlanNode.Filter(a, gt(col(NUMBERS, 1), lit(1)));
        return List.of(a, filter,
                new PlanNode.Project(filter, List.of(new NamedExpression("m", add(col(NUMBERS, 0), lit(100))),
                        new NamedExpression("k", col(NUMBERS, 1)))),
                new PlanNode.Limit(filter, 6, 4),
                new PlanNode.Union(List.of(filter, b, a)),
                new PlanNode.Aggregate(filter, List.of(1), List.of(
                        new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "c"),
                        new AggregateCall(AggregateFunction.SUM, 0, false, "s"))),
                new PlanNode.Sort(a, List.of(SortKey.desc(1))),
                new PlanNode.Join(a, b, List.of(1), List.of(1), JoinType.INNER));
    }

    @Test
    void segmentsGiveWhatOneThreadGivesInTheSameOrder() {
        for (PlanNode plan : plans()) {
            List<List<Object>> expected = rows(plan, 1, true);
            assertTrue(expected.size() >= 3, plan::toString);
            for (int threads : new int[] {2, 3, 8}) {
                for (int run = 0; run < 5; run++) {
                    assertEquals(expected, rows(plan, threads, true), () -> threads + " threads: " + plan);
                }
            }
        }
        assertTrue(source.threads.stream().anyMatch(t -> t.startsWith("fyrql-worker-")), source.threads::toString);
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void withoutOrderTheRowsAreTheSame() {
        List<PlanNode> plans = plans();
        for (PlanNode plan : plans) {
            List<List<Object>> expected = rows(plan, 1, true);
            List<List<Object>> actual = rows(plan, 4, false);
            if (plan instanceof PlanNode.Limit) {
                assertEquals(expected.size(), actual.size());
            } else {
                assertEquals(sorted(expected), sorted(actual), plan::toString);
            }
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void aScanWithOneSplitRunsOnTheThreadOfTheQuery() {
        PlanNode.Scan one = source.table("one", 20);
        PlanNode plan = new PlanNode.Filter(one, gt(col(NUMBERS, 1), lit(1)));
        assertEquals(rows(plan, 1, true), rows(plan, 8, true));
        assertEquals(Set.of(Thread.currentThread().getName()), source.threads);
    }

    @Test
    void theTopsOfTheSegments() {
        PlanNode.Scan a = source.table("a", 1);
        PlanNode.Scan b = source.table("b", 1);
        PlanNode filter = new PlanNode.Filter(a, gt(col(NUMBERS, 1), lit(1)));
        PlanNode aggregate = new PlanNode.Aggregate(filter, List.of(0, 1), List.of());
        assertEquals(List.of(filter), List.copyOf(SegmentPlanner.tops(aggregate)));
        PlanNode union = new PlanNode.Union(List.of(filter, b));
        assertEquals(List.of(union), List.copyOf(SegmentPlanner.tops(union)));
        Set<PlanNode> tops = SegmentPlanner.tops(new PlanNode.Union(List.of(filter,
                new PlanNode.Aggregate(b, List.of(0, 1), List.of()))));
        assertEquals(2, tops.size());
        assertTrue(tops.contains(filter) && tops.contains(b));
        // A join is in the chain of the side it probes; its build side is a segment of its own.
        PlanNode join = new PlanNode.Join(filter, b, List.of(1), List.of(1), JoinType.INNER);
        PlanNode above = new PlanNode.Filter(join, gt(col(join.schema(), 0), lit(1)));
        tops = SegmentPlanner.tops(above);
        assertEquals(2, tops.size());
        assertTrue(tops.contains(above) && tops.contains(b));
        assertEquals(List.of(join), SegmentPlanner.joins(above));
        // A union of chains with joins is no segment, but each chain is one.
        PlanNode other = new PlanNode.Join(source.table("c", 1), b, List.of(1), List.of(1), JoinType.INNER);
        tops = SegmentPlanner.tops(new PlanNode.Union(List.of(join, other)));
        assertEquals(3, tops.size());
        assertTrue(tops.contains(join) && tops.contains(other) && tops.contains(b));
        // A join that emits its unmatched build rows is only the top of a chain.
        PlanNode right = new PlanNode.Join(filter, b, List.of(1), List.of(1), JoinType.RIGHT);
        tops = SegmentPlanner.tops(new PlanNode.Filter(right, gt(col(right.schema(), 0), lit(1))));
        assertEquals(2, tops.size());
        assertTrue(tops.contains(right) && tops.contains(b));
    }

    @Test
    void inOrderTheRowsBeforeTheFirstFailedSplitComeFirst() {
        PlanNode.Scan f = source.table("f", 3, 4, 5, 6, 7, 8);
        source.failing.addAll(List.of(2, 4));
        PlanNode plan = new PlanNode.Filter(f, gt(col(NUMBERS, 0), lit(-1)));
        for (int run = 0; run < 20; run++) {
            Executor executor = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), 3, true);
            int rows = 0;
            try (Operator op = executor.open(plan)) {
                QueryException e = assertThrows(QueryException.class, () -> {
                    for (Batch b = op.next(); b != null; b = op.next()) {
                        // Read on.
                    }
                });
                assertEquals("Scan#1", e.operator());
                assertEquals("bad split 2 in Scan#1", e.getMessage());
            }
            try (Operator op = executor.open(plan)) {
                try {
                    for (Batch b = op.next(); b != null; b = op.next()) {
                        rows += b.rowCount();
                    }
                } catch (QueryException e) {
                    // As above.
                }
            }
            assertEquals(7, rows, "the rows of the splits before the failed one");
            assertEquals(0, source.open.get(), "every reader is closed");
        }
        Executor unordered = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), 3, false);
        try (Operator op = unordered.open(plan)) {
            QueryException e = assertThrows(QueryException.class, () -> {
                for (Batch b = op.next(); b != null; b = op.next()) {
                    // Read on.
                }
            });
            assertTrue(e.getMessage().startsWith("bad split "), e.getMessage());
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void inOrderTheRowsOfAFailedSplitBeforeItsFailureComeFirst() {
        PlanNode.Scan f = source.table("f", 3, 4, 5, 6, 7, 8);
        source.failingLate.addAll(List.of(3, 5));
        PlanNode plan = new PlanNode.Filter(f, gt(col(NUMBERS, 0), lit(-1)));
        for (int run = 0; run < 20; run++) {
            Executor executor = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), 3, true);
            int rows = 0;
            try (Operator op = executor.open(plan)) {
                QueryException e = assertThrows(QueryException.class, () -> {
                    for (Batch b = op.next(); b != null; b = op.next()) {
                        // Read on.
                    }
                });
                assertEquals("bad split 3 in Scan#1", e.getMessage());
            }
            try (Operator op = executor.open(plan)) {
                for (Batch b = op.next(); b != null; b = op.next()) {
                    rows += b.rowCount();
                }
            } catch (QueryException e) {
                // As above.
            }
            assertEquals(15, rows, "the rows of the splits before the failed one and of its first batch");
        }
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void closingEarlyStopsTheWorkers() {
        int[] sizes = new int[200];
        Arrays.fill(sizes, 10);
        PlanNode plan = new PlanNode.Limit(source.table("big", sizes), 1, 0);
        assertEquals(List.of(List.of(0, 0)), rows(plan, 4, true));
        assertTrue(source.opened.get() < 200, "the workers stop before they read every split");
        assertEquals(0, source.open.get(), "every reader is closed");
    }

    @Test
    void cancelingStopsTheWaitingWorkersAndTheQuery() {
        int[] sizes = new int[8];
        Arrays.fill(sizes, 10);
        PlanNode plan = new PlanNode.Filter(source.table("slow", sizes), gt(col(NUMBERS, 0), lit(-1)));
        CountDownLatch waiting = new CountDownLatch(4);
        source.waiting = waiting;
        QueryContext context = new QueryContext();
        Thread canceler = new Thread(() -> {
            try {
                waiting.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.cancel();
        });
        canceler.start();
        Executor executor = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), 4, true);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Operator op = executor.open(plan, node -> Double.NaN, context)) {
                QueryCancelledException e = assertThrows(QueryCancelledException.class, op::next);
                assertEquals(QueryContext.Reason.CANCEL, e.reason());
            }
        });
        assertEquals(0, source.open.get(), "every reader is closed");
    }
}
