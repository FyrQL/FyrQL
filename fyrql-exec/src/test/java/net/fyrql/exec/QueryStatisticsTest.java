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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the context of a query counts for its operators and scans. */
class QueryStatisticsTest {

    private static final Schema NUMBERS = SplitTables.NUMBERS;

    @TempDir
    Path temp;

    private final SplitTables tables = new SplitTables();
    private final Reporting source = new Reporting();

    /** The tables of {@link SplitTables}, which report their splits and a byte per row, and may be slow. */
    private final class Reporting implements TableSource {
        volatile long millisPerBatch;

        @Override
        public BatchReader open(PlanNode.Scan scan) {
            return Splits.sequential(splits(scan, QueryContext.NONE), QueryContext.NONE);
        }

        @Override
        public Splits splits(PlanNode.Scan scan, QueryContext context) {
            Splits splits = tables.splits(scan, context);
            SourceCounters.current().splits(splits.count());
            return new Splits() {
                @Override
                public int count() {
                    return splits.count();
                }

                @Override
                public BatchReader open(int index) {
                    BatchReader reader = splits.open(index);
                    return new BatchReader() {
                        @Override
                        public Batch next() {
                            Batch b = reader.next();
                            if (b != null) {
                                SourceCounters.current().bytes(b.rowCount());
                                pause();
                            }
                            return b;
                        }

                        @Override
                        public void close() {
                            reader.close();
                        }
                    };
                }
            };
        }

        private void pause() {
            if (millisPerBatch > 0) {
                try {
                    Thread.sleep(millisPerBatch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record Run(long rows, long batches, QueryStatistics statistics, Map<PlanNode, Integer> ids) {
        OperatorStatistics of(PlanNode node) {
            return statistics.operator(Executor.label(node, ids));
        }

        SourceStatistics read(PlanNode node) {
            return statistics.source(Executor.label(node, ids));
        }
    }

    private Run run(PlanNode plan, int threads, MemoryBudget budget, SpillDirectory spill) {
        Executor executor = new Executor(source, budget, 3, Codegen.off(), threads, true, spill);
        QueryContext context = new QueryContext();
        long rows = 0;
        long batches = 0;
        try (Operator op = executor.open(plan, node -> Double.NaN, context)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                rows += b.rowCount();
                batches++;
            }
        }
        context.finished();
        return new Run(rows, batches, context.statistics(), Plans.ids(plan));
    }

    private Run run(PlanNode plan, int threads) {
        return run(plan, threads, MemoryBudget.unlimited(), null);
    }

    private PlanNode.Scan numbers() {
        return tables.table("a", 5, 0, 7, 1, 12, 3, 9);
    }

    private static long passing(int rows) {
        long n = 0;
        for (int i = 0; i < rows; i++) {
            if (i % 5 > 1) {
                n++;
            }
        }
        return n;
    }

    @Test
    void eachOperatorCountsTheRowsAndBatchesItReturns() {
        PlanNode.Scan scan = numbers();
        PlanNode.Filter filter = new PlanNode.Filter(scan, gt(col(NUMBERS, 1), lit(1)));
        PlanNode.Limit limit = new PlanNode.Limit(filter, 100, 0);
        Run r = run(limit, 1);
        assertEquals(passing(37), r.rows());
        assertEquals(r.rows(), r.of(limit).rows());
        assertEquals(r.batches(), r.of(limit).batches());
        assertEquals(passing(37), r.of(filter).rows());
        assertEquals(37, r.of(scan).rows());
        // Splits of 5, 7, 1, 12, 3 and 9 rows in batches of three.
        assertEquals(2 + 3 + 1 + 4 + 1 + 3, r.of(scan).batches());
        assertEquals(1, r.of(scan).threads());
        assertEquals(1, r.statistics().threads());
        assertEquals(7, r.read(scan).splits());
        assertEquals(37, r.read(scan).bytes());
        // In the order the operators were built, the inputs first.
        assertEquals(List.of(Executor.label(scan, r.ids()), Executor.label(filter, r.ids()),
                Executor.label(limit, r.ids())), List.copyOf(r.statistics().operators().keySet()));
        assertNull(r.statistics().plan(), "the engine notes the plan");
    }

    @Test
    void theCopiesOfASegmentCountOnceUnderTheirLabels() {
        PlanNode.Scan scan = numbers();
        PlanNode.Filter filter = new PlanNode.Filter(scan, gt(col(NUMBERS, 1), lit(1)));
        PlanNode.Limit limit = new PlanNode.Limit(filter, 100, 0);
        for (int run = 0; run < 5; run++) {
            Run r = run(limit, 4);
            assertEquals(passing(37), r.rows());
            assertEquals(passing(37), r.of(filter).rows(), "the segment itself counts nothing");
            assertEquals(37, r.of(scan).rows());
            // The workers, and the thread of the query, which asks for the splits.
            assertTrue(r.of(scan).threads() >= 1 && r.of(scan).threads() <= 5, r::toString);
            assertEquals(7, r.read(scan).splits());
            assertEquals(37, r.read(scan).bytes(), "the workers read for the scan");
        }
    }

    @Test
    void anAggregateThatDrainsASegmentCountsItsWorkOnTheWorkers() {
        PlanNode.Scan scan = numbers();
        PlanNode.Filter filter = new PlanNode.Filter(scan, gt(col(NUMBERS, 1), lit(1)));
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(filter, List.of(1), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "c")));
        Run r = run(aggregate, 4);
        assertEquals(3, r.rows());
        assertEquals(3, r.of(aggregate).rows());
        assertEquals(passing(37), r.of(filter).rows());
        assertEquals(37, r.of(scan).rows());
        assertTrue(r.of(aggregate).nanos() > 0);
        assertTrue(r.statistics().threads() >= 1);
    }

    @Test
    void theBuildSideOfAJoinCountsUnderItsScanAndTheJoin() {
        PlanNode.Scan a = numbers();
        PlanNode.Scan b = tables.table("b", 4, 6);
        PlanNode.Join join = new PlanNode.Join(a, b, List.of(1), List.of(1), JoinType.INNER);
        for (int threads : new int[] {1, 4}) {
            Run r = run(join, threads);
            assertEquals(r.rows(), r.of(join).rows());
            assertEquals(37, r.of(a).rows());
            assertEquals(10, r.of(b).rows());
            assertEquals(10, r.read(b).bytes());
            assertEquals(2, r.read(b).splits());
            assertTrue(r.of(join).nanos() > 0);
        }
    }

    @Test
    void anOperatorAboveASlowSourceCountsOnlyItsOwnTime() {
        PlanNode.Scan scan = tables.table("slow", 12);
        PlanNode.Filter filter = new PlanNode.Filter(scan, gt(col(NUMBERS, 1), lit(1)));
        PlanNode.Limit limit = new PlanNode.Limit(filter, 100, 0);
        source.millisPerBatch = 25;
        Run r = run(limit, 1);
        long scanMillis = Duration.ofNanos(r.of(scan).nanos()).toMillis();
        long filterMillis = Duration.ofNanos(r.of(filter).nanos()).toMillis();
        long limitMillis = Duration.ofNanos(r.of(limit).nanos()).toMillis();
        assertTrue(scanMillis >= 4 * 25, () -> "the scan waited " + scanMillis + " ms");
        assertTrue(filterMillis < 50 && limitMillis < 50, () -> filterMillis + " and " + limitMillis + " ms");
        assertTrue(r.statistics().elapsed().toMillis() >= scanMillis);
    }

    @Test
    void anOperatorThatSpillsCountsItsParts() {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < 4000; n++) {
            rows.add(new Object[] {n, 3999 - n});
        }
        PlanNode.Scan scan = tables.table("many", NUMBERS, rows, 1000, 1000, 1000, 1000);
        PlanNode.Sort sort = new PlanNode.Sort(scan, List.of(SortKey.asc(1)));
        try (SpillDirectory spill = new SpillDirectory(temp)) {
            Run r = run(sort, 1, new MemoryBudget(16 * 1024), spill);
            assertEquals(4000, r.rows());
            OperatorStatistics s = r.of(sort);
            assertTrue(s.spilledParts() > 0, s::toString);
            assertEquals(r.statistics().spill().parts(), s.spilledParts());
            assertEquals(r.statistics().spill().bytesWritten(), s.spilledBytes());
            assertEquals(r.statistics().spill().bytesRead(), s.readBack());
            assertEquals(0, r.of(scan).spilledParts());
        }
    }

    @Test
    void theSharedContextCountsNothing() {
        PlanNode.Scan scan = numbers();
        Executor executor = new Executor(source, MemoryBudget.unlimited(), 3, Codegen.off(), 4, true, null);
        assertEquals(passing(37), executor.rows(new PlanNode.Filter(scan, gt(col(NUMBERS, 1), lit(1)))).size());
        QueryStatistics s = QueryContext.NONE.statistics();
        assertEquals(Map.of(), s.operators());
        assertEquals(Map.of(), s.sources());
        assertEquals(0, s.threads());
    }

    @Test
    void theStatisticsStopWithTheQuery() throws InterruptedException {
        QueryContext context = new QueryContext();
        context.finished();
        Duration first = context.statistics().elapsed();
        Thread.sleep(20);
        assertEquals(first, context.statistics().elapsed());
    }
}
