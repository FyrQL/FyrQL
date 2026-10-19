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
import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canceling a query from another thread: between batches, while a source waits, and in the middle of a sort. */
class CancelTest {

    private static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.INTEGER));
    private static final PlanNode.Scan SCAN = PlanNode.Scan.all(TableRef.of("t", "numbers"), NUMBERS);
    /** A filter over the scan, which keeps every row. */
    private static final PlanNode FILTER = new PlanNode.Filter(SCAN, gt(col(NUMBERS, 0), lit(-1)));

    private static Batch numbers(int from, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {from + i});
        }
        return Batch.ofRows(NUMBERS, rows);
    }

    /** Runs the operators until they fail, and returns the failure. */
    private static QueryCancelledException runUntilCancelled(Operator op) {
        return assertThrows(QueryCancelledException.class, () -> {
            while (op.next() != null) {
                // Read on.
            }
        });
    }

    /**
     * An input that is no operator of the executor, and so does not check
     * the context itself; it cancels the context at a call of {@code next()}.
     */
    private static Operator input(Schema schema, List<Batch> batches, int cancelAtCall, QueryContext context) {
        return new Operator() {
            private int calls;

            @Override
            public Schema schema() {
                return schema;
            }

            @Override
            public Batch next() {
                if (calls++ == cancelAtCall) {
                    context.cancel();
                }
                return calls <= batches.size() ? batches.get(calls - 1) : null;
            }

            @Override
            public void close() {
            }
        };
    }

    private static boolean ranIn(Throwable e, String className, String method) {
        return Arrays.stream(e.getStackTrace())
                .anyMatch(f -> f.getClassName().equals(className) && f.getMethodName().equals(method));
    }

    @Test
    void aSourceThatWaitsIsAskedToStopAndTheQueryFailsAsCanceled() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        BatchReader reader = new BatchReader() {
            private boolean first = true;

            @Override
            public Batch next() {
                if (first) {
                    first = false;
                    return numbers(0, 3);
                }
                waiting.countDown();
                try {
                    stopped.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("The source was asked to stop");
            }

            @Override
            public void cancel() {
                cancels.incrementAndGet();
                stopped.countDown();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
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
        long start = System.nanoTime();
        try (Operator op = new Executor(scan -> reader).open(FILTER, node -> Double.NaN, context)) {
            assertEquals(3, op.next().rowCount());
            QueryCancelledException e = runUntilCancelled(op);
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "the source was not asked to stop");
            assertEquals(QueryContext.Reason.CANCEL, e.reason());
            assertTrue(e.operator().startsWith("Scan#"), e.operator());
            assertEquals("The query was canceled in " + e.operator(), e.getMessage());
            assertEquals("The source was asked to stop", e.getCause().getMessage(),
                    "the error of the source is the cause");
        }
        canceler.join();
        assertEquals(1, cancels.get());
        assertEquals(1, closes.get());
    }

    @Test
    void aQueryOverAnEndlessSourceTimesOut() {
        AtomicLong batches = new AtomicLong();
        BatchReader endless = () -> numbers((int) batches.getAndIncrement(), 100);
        QueryContext context = new QueryContext();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try (Operator op = new Executor(scan -> endless).open(FILTER, node -> Double.NaN, context)) {
            timer.schedule(() -> context.cancel(QueryContext.Reason.TIMEOUT), 200, TimeUnit.MILLISECONDS);
            QueryCancelledException e = runUntilCancelled(op);
            assertEquals(QueryContext.Reason.TIMEOUT, e.reason());
            assertTrue(e.getMessage().startsWith("The query timed out in "), e.getMessage());
            assertTrue(batches.get() > 0);
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void aSortStopsInTheMiddle() {
        Random random = new Random(1);
        List<Batch> batches = new ArrayList<>();
        for (int b = 0; b < 32; b++) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 65_536; i++) {
                rows.add(new Object[] {random.nextInt()});
            }
            batches.add(Batch.ofRows(NUMBERS, rows));
        }
        QueryContext context = new QueryContext();
        // The input cancels when it has delivered its two million rows, before the sort starts.
        Operator input = input(NUMBERS, batches, batches.size(), context);
        SortOperator sort = new SortOperator("Sort#0", new PlanNode.Sort(SCAN, List.of(SortKey.asc(0))), input,
                MemoryBudget.unlimited(), 2048);
        sort.attach(context);
        QueryCancelledException e = runUntilCancelled(sort);
        assertEquals("Sort#0", e.operator());
        assertTrue(ranIn(e, "java.util.TimSort", "sort"), "the sort stopped in its middle");
        sort.close();
    }

    @Test
    void aCrossJoinStopsBetweenTheRowsOfAProbe() {
        QueryContext context = new QueryContext();
        PlanNode.Scan other = PlanNode.Scan.all(TableRef.of("t", "other"), NUMBERS);
        // The left input cancels when it delivers its first batch, after the right one is built.
        Operator left = input(NUMBERS, List.of(numbers(0, 2048)), 0, context);
        Operator right = input(NUMBERS, Batches.split(numbers(0, 20_000), 2048), -1, context);
        HashJoinOperator join = new HashJoinOperator("Join#0",
                new PlanNode.Join(SCAN, other, List.of(), List.of(), JoinType.CROSS), left, right,
                MemoryBudget.unlimited(), Codegen.off());
        join.attach(context);
        QueryCancelledException e = runUntilCancelled(join);
        assertEquals("Join#0", e.operator());
        assertTrue(ranIn(e, HashJoinOperator.class.getName(), "probe"), "the probe stopped");
        join.close();
    }

    @Test
    void cancelingIsFinalAndAsksEveryReaderOnce() {
        QueryContext context = new QueryContext();
        AtomicInteger cancels = new AtomicInteger();
        BatchReader counting = new BatchReader() {
            @Override
            public Batch next() {
                return null;
            }

            @Override
            public void cancel() {
                cancels.incrementAndGet();
            }
        };
        context.register(counting);
        assertFalse(context.isCancelled());
        assertNull(context.reason());
        context.check("Scan#1");
        context.cancel(QueryContext.Reason.TIMEOUT);
        context.cancel();
        assertEquals(QueryContext.Reason.TIMEOUT, context.reason(), "the first reason stays");
        assertEquals(1, cancels.get());
        context.register(counting);
        assertEquals(2, cancels.get(), "a reader registered after canceling is asked at once");
        QueryCancelledException e = assertThrows(QueryCancelledException.class, () -> context.check("Scan#1"));
        assertEquals("The query timed out in Scan#1", e.getMessage());
        assertSame(QueryContext.Reason.TIMEOUT, e.reason());

        QueryContext.NONE.register(counting);
        QueryContext.NONE.cancel();
        assertFalse(QueryContext.NONE.isCancelled(), "the context of queries that cannot be canceled");
        assertEquals(2, cancels.get());
    }
}
