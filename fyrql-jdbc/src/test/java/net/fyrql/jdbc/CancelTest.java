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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryCancelledException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canceling queries of the driver from another thread: on request, by their timeout, and by closing. */
class CancelTest {

    /**
     * A source with a table {@code t} whose readers either wait after their
     * first batch until they are asked to stop, or deliver the rows of the
     * table again and again without end.
     */
    static final class SlowConnector implements Connector {
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        private final MemoryConnector memory;
        private final boolean endless;

        SlowConnector(String name, boolean endless) {
            this.memory = MemoryConnector.builder(name).table("t", ConnectionTest.Row.class,
                    List.of(new ConnectionTest.Row(1, "a"), new ConnectionTest.Row(2, "b"))).build();
            this.endless = endless;
        }

        @Override
        public String name() {
            return memory.name();
        }

        @Override
        public Catalog catalog() {
            return memory.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return memory.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return memory.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            CountDownLatch stopped = new CountDownLatch(1);
            return new BatchReader() {
                private BatchReader rows = memory.open(split);
                private boolean first = true;

                @Override
                public Batch next() {
                    if (endless) {
                        Batch b = rows.next();
                        if (b == null) {
                            rows.close();
                            rows = memory.open(split);
                            b = rows.next();
                        }
                        return b;
                    }
                    if (first) {
                        first = false;
                        return rows.next();
                    }
                    waiting.countDown();
                    try {
                        stopped.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("The slow source was asked to stop");
                }

                @Override
                public void cancel() {
                    cancels.incrementAndGet();
                    stopped.countDown();
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                    rows.close();
                }
            };
        }
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Runs an action once the source waits, and returns when it ran, by {@link System#nanoTime()}. */
    private static long[] whenWaiting(SlowConnector slow, ThrowingAction action) {
        long[] at = new long[1];
        Thread t = new Thread(() -> {
            try {
                if (slow.waiting.await(10, TimeUnit.SECONDS)) {
                    at[0] = System.nanoTime();
                    action.run();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return at;
    }

    @FunctionalInterface
    interface ThrowingAction {
        void run() throws Exception;
    }

    @Test
    void cancelEndsAQueryThatWaitsOnASourceAndTheStatementRunsTheNextOne() throws Exception {
        SlowConnector slow = new SlowConnector("slow", false);
        try (FyrQL engine = FyrQL.builder().register(slow).register(new ConnectionTest.CountingConnector("shop"))
                .build();
                Connection c = FyrQLDriver.connect(engine, false); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT id FROM slow.t");
            assertTrue(rs.next());
            assertTrue(rs.next());
            long[] canceled = whenWaiting(slow, s::cancel);
            SQLException e = assertThrows(SQLException.class, rs::next);
            long millis = millisSince(canceled[0]);
            assertEquals(SqlStates.QUERY_CANCELED, e.getSQLState());
            assertTrue(e.getMessage().startsWith("The query was canceled in Scan#"), e.getMessage());
            assertInstanceOf(QueryCancelledException.class, e.getCause());
            assertEquals("The slow source was asked to stop", e.getCause().getCause().getMessage());
            assertTrue(millis < 2000, "the query ended " + millis + " ms after cancel");
            assertEquals(1, slow.cancels.get());
            assertEquals(1, slow.closes.get(), "the failed query released its source");
            assertFalse(rs.next(), "after the failure there are no more rows");
            ResultSet next = s.executeQuery("SELECT count(*) FROM shop.t");
            assertTrue(next.next(), "the statement runs the next query");
            assertEquals(2, next.getInt(1));
        }
    }

    @Test
    void cancelWithoutARunningQueryDoesNothing() throws Exception {
        try (FyrQL engine = FyrQL.builder().register(new ConnectionTest.CountingConnector("shop")).build();
                Connection c = FyrQLDriver.connect(engine, false); Statement s = c.createStatement()) {
            s.cancel();
            ResultSet rs = s.executeQuery("SELECT id FROM shop.t ORDER BY id");
            assertTrue(rs.next());
            assertTrue(rs.next());
            assertFalse(rs.next());
            s.cancel();
            rs = s.executeQuery("SELECT id FROM shop.t ORDER BY id");
            assertTrue(rs.next(), "a cancel of the query before does not reach the next one");
            assertEquals(1, rs.getInt(1));
            s.close();
            assertThrows(SQLException.class, s::cancel);
        }
    }

    @Test
    void aQueryOverAnEndlessSourceTimesOut() throws Exception {
        SlowConnector endless = new SlowConnector("endless", true);
        try (FyrQL engine = FyrQL.builder().register(endless).build();
                Connection c = FyrQLDriver.connect(engine, false)) {
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(1);
                assertEquals(1, s.getQueryTimeout());
                long start = System.nanoTime();
                // The query runs until its first rows while it is executed, as a count until its end.
                SQLTimeoutException e = assertThrows(SQLTimeoutException.class,
                        () -> s.executeQuery("SELECT count(*) FROM endless.t"));
                long millis = millisSince(start);
                assertEquals(SqlStates.TIMEOUT, e.getSQLState());
                assertTrue(e.getMessage().startsWith("The query timed out in "), e.getMessage());
                assertTrue(millis >= 900 && millis < 3000, "the query timed out after " + millis + " ms");
            }
            try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM endless.t WHERE id > ?")) {
                p.setQueryTimeout(1);
                p.setInt(1, 0);
                assertEquals(SqlStates.TIMEOUT, assertThrows(SQLTimeoutException.class, p::executeQuery).getSQLState());
            }
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(1);
                ResultSet rs = s.executeQuery("SELECT id FROM endless.t");
                assertTrue(rs.next());
                Thread.sleep(1500);
                assertTrue(rs.next(), "the rows of the batch read before the timeout stay");
                assertEquals(SqlStates.TIMEOUT, assertThrows(SQLTimeoutException.class, rs::next).getSQLState(),
                        "the timeout covers the reading of the result");
                rs = s.executeQuery("SELECT id FROM endless.t LIMIT 2");
                assertTrue(rs.next());
                assertTrue(rs.next());
                assertFalse(rs.next(), "a query that ends in time is not canceled");
            }
        }
    }

    @Test
    void theTimeoutsOfTheEngineApplyToStatements() throws Exception {
        SlowConnector endless = new SlowConnector("endless", true);
        try (FyrQL engine = FyrQL.builder().register(endless).queryTimeout(Duration.ofSeconds(1)).build();
                Connection c = FyrQLDriver.connect(engine, false); Statement s = c.createStatement()) {
            assertEquals(0, s.getQueryTimeout());
            long start = System.nanoTime();
            SQLTimeoutException e = assertThrows(SQLTimeoutException.class,
                    () -> s.executeQuery("SELECT count(*) FROM endless.t"));
            long millis = millisSince(start);
            assertEquals(SqlStates.TIMEOUT, e.getSQLState());
            assertTrue(millis >= 900 && millis < 3000, "the query timed out after " + millis + " ms");
        }
        try (FyrQL engine = FyrQL.builder().register(endless).maxQueryTimeout(Duration.ofSeconds(1)).build();
                Connection c = FyrQLDriver.connect(engine, false); Statement s = c.createStatement()) {
            s.setQueryTimeout(60);
            long start = System.nanoTime();
            assertEquals(SqlStates.TIMEOUT, assertThrows(SQLTimeoutException.class,
                    () -> s.executeQuery("SELECT count(*) FROM endless.t")).getSQLState());
            long millis = millisSince(start);
            assertTrue(millis >= 900 && millis < 3000, "the upper bound ended the query after " + millis + " ms");
        }
    }

    @Test
    void aCrossJoinIsCanceledInTime() throws Exception {
        // Four hundred million pairs, in probe batches of 16 rows.
        try (FyrQL engine = FyrQL.builder().register(new ConnectionTest.CountingConnector("big", 20_000, 16))
                .build();
                Connection c = FyrQLDriver.connect(engine, false); Statement s = c.createStatement()) {
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            try {
                long[] canceled = new long[1];
                timer.schedule(() -> {
                    canceled[0] = System.nanoTime();
                    s.cancel();
                    return null;
                }, 300, TimeUnit.MILLISECONDS);
                SQLException e = assertThrows(SQLException.class,
                        () -> s.executeQuery("SELECT count(*) FROM big.t a CROSS JOIN big.t b"));
                long millis = millisSince(canceled[0]);
                assertEquals(SqlStates.QUERY_CANCELED, e.getSQLState(), e.getMessage());
                assertTrue(millis < 2000, "the query ended " + millis + " ms after cancel");
            } finally {
                timer.shutdownNow();
            }
        }
    }

    @Test
    void closingTheConnectionFromAnotherThreadEndsItsQuery() throws Exception {
        SlowConnector slow = new SlowConnector("slow", false);
        try (FyrQL engine = FyrQL.builder().register(slow).build()) {
            Connection c = FyrQLDriver.connect(engine, false);
            Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("SELECT id FROM slow.t");
            assertTrue(rs.next());
            assertTrue(rs.next());
            long[] closed = whenWaiting(slow, c::close);
            SQLException e = assertThrows(SQLException.class, rs::next);
            assertEquals(SqlStates.QUERY_CANCELED, e.getSQLState(), e.getMessage());
            assertTrue(millisSince(closed[0]) < 2000);
            for (int i = 0; i < 100 && !c.isClosed(); i++) {
                Thread.sleep(20);
            }
            assertTrue(c.isClosed());
            assertTrue(s.isClosed());
            assertTrue(rs.isClosed());
            assertEquals(1, slow.cancels.get());
            assertEquals(1, slow.closes.get());
        }
    }
}
