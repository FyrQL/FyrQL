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

package net.fyrql;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeouts of queries: the one of the engine, the one a query asks
 * for, and the upper bound of both; they cover running a statement that
 * writes and reading the result of a query until it is closed.
 */
class QueryTimeoutTest {

    /** A source with a table {@code t} whose rows come again and again without end. */
    static final class EndlessConnector implements Connector {
        private final MemoryConnector memory = MemoryConnector.builder("endless").table("t",
                Schema.of(new Schema.Field("id", DataType.INTEGER)), List.of(new Object[] {1}, new Object[] {2}))
                .build();

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
            return new BatchReader() {
                private BatchReader rows = memory.open(split);

                @Override
                public Batch next() {
                    Batch b = rows.next();
                    if (b == null) {
                        rows.close();
                        rows = memory.open(split);
                        b = rows.next();
                    }
                    return b;
                }

                @Override
                public void close() {
                    rows.close();
                }
            };
        }
    }

    private static FyrQL.Builder engine() {
        return FyrQL.builder().register(new EndlessConnector()).register(MemoryConnector.builder("mem").build())
                .defaultSource("mem").threads(1);
    }

    /** Runs a statement that must time out, and returns after how many milliseconds it did. */
    static long timesOut(Executable statement) {
        long start = System.nanoTime();
        RuntimeException e = assertThrows(RuntimeException.class, statement);
        long millis = (System.nanoTime() - start) / 1_000_000;
        QueryCancelledException cancelled = null;
        for (Throwable t = e; t != null && cancelled == null; t = t.getCause()) {
            if (t instanceof QueryCancelledException c) {
                cancelled = c;
            }
        }
        assertNotNull(cancelled, e::toString);
        assertEquals(QueryContext.Reason.TIMEOUT, cancelled.reason());
        assertTrue(e.getMessage().contains("The query timed out"), e.getMessage());
        return millis;
    }

    @Test
    void theTimeoutOfTheEngineEndsQueriesAndWrites() {
        try (FyrQL engine = engine().queryTimeout(Duration.ofMillis(300)).build()) {
            assertEquals(Optional.of(Duration.ofMillis(300)), engine.queryTimeout());
            assertEquals(Optional.empty(), engine.maxQueryTimeout());
            long millis = timesOut(() -> engine.rows("SELECT count(*) FROM endless.t"));
            assertTrue(millis >= 250 && millis < 5000, "timed out after " + millis + " ms");
            timesOut(() -> engine.update("CREATE TABLE copy AS SELECT * FROM endless.t"));
            engine.update("CREATE TABLE target (id INTEGER)");
            timesOut(() -> engine.update("INSERT INTO target SELECT * FROM endless.t"));
            timesOut(() -> engine.sql("INSERT INTO target SELECT * FROM endless.t").close());
            assertEquals(List.of(2L), engine.rows("SELECT count(*) FROM (SELECT * FROM endless.t LIMIT 2)").stream()
                    .map(r -> r[0]).toList(), "a query that ends in time");
        }
    }

    @Test
    void aQueryAsksForATimeoutOfItsOwn() {
        try (FyrQL engine = engine().queryTimeout(Duration.ofHours(1)).build()) {
            long millis = timesOut(() -> {
                try (QueryResult r = engine.execute(engine.plan("SELECT count(*) FROM endless.t"),
                        QueryContext.withTimeout(Duration.ofMillis(200)))) {
                    r.rows();
                }
            });
            assertTrue(millis < 5000, "timed out after " + millis + " ms");
        }
        try (FyrQL engine = engine().build()) {
            timesOut(() -> engine.update(engine.statement("CREATE TABLE copy AS SELECT * FROM endless.t"),
                    QueryContext.withTimeout(Duration.ofMillis(200))));
        }
        assertThrows(IllegalArgumentException.class, () -> QueryContext.withTimeout(Duration.ZERO));
    }

    @Test
    void theUpperBoundShortensEveryTimeout() {
        try (FyrQL engine = engine().maxQueryTimeout(Duration.ofMillis(300)).build()) {
            assertEquals(Optional.of(Duration.ofMillis(300)), engine.maxQueryTimeout());
            long millis = timesOut(() -> {
                try (QueryResult r = engine.execute(engine.plan("SELECT count(*) FROM endless.t"),
                        QueryContext.withTimeout(Duration.ofHours(1)))) {
                    r.rows();
                }
            });
            assertTrue(millis < 5000, "timed out after " + millis + " ms");
            timesOut(() -> engine.rows("SELECT count(*) FROM endless.t"));
        }
        Timeouts timeouts = new Timeouts(Duration.ofSeconds(30), Duration.ofMinutes(5));
        assertEquals(Duration.ofSeconds(30), timeouts.effective(null));
        assertEquals(Duration.ofSeconds(10), timeouts.effective(Duration.ofSeconds(10)));
        assertEquals(Duration.ofMinutes(5), timeouts.effective(Duration.ofHours(1)));
        assertEquals(null, new Timeouts(null, null).effective(null));
    }

    @Test
    void readingTheResultCountsUntilItIsClosed() throws InterruptedException {
        try (FyrQL engine = engine().queryTimeout(Duration.ofMillis(300)).build()) {
            QueryResult r = engine.sql("SELECT id FROM endless.t");
            assertNotNull(r.nextBatch());
            Thread.sleep(600);
            timesOut(() -> {
                for (int i = 0; i < 1_000_000; i++) {
                    r.nextBatch();
                }
            });
            r.close();
        }
        try (FyrQL engine = engine().queryTimeout(Duration.ofHours(1)).build()) {
            QueryResult r = engine.sql("SELECT id FROM endless.t LIMIT 1");
            assertEquals(1, engine.timeouts().pending());
            assertEquals(1, r.rows().size());
            r.close();
            assertEquals(0, engine.timeouts().pending(), "closing the result ends its timeout");
            assertEquals(1L, engine.update("CREATE TABLE one AS SELECT * FROM endless.t LIMIT 1"));
            assertEquals(0, engine.timeouts().pending(), "a write ends its timeout");
        }
    }

    @Test
    void theSettingsAreChecked() {
        try (FyrQL engine = engine().queryTimeout(Duration.ZERO).maxQueryTimeout(null).build()) {
            assertEquals(Optional.empty(), engine.queryTimeout());
            assertEquals(Optional.empty(), engine.maxQueryTimeout());
            assertEquals(2, engine.rows("SELECT * FROM endless.t LIMIT 2").size());
            assertEquals(0, engine.timeouts().pending());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> engine()
                .queryTimeout(Duration.ofMinutes(10)).maxQueryTimeout(Duration.ofMinutes(5)).build());
        assertEquals("The queryTimeout 10m is above the maxQueryTimeout 5m", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> engine().queryTimeout(Duration.ofSeconds(-1)));
        assertEquals("1500ms", Timeouts.text(Duration.ofMillis(1500)));
        assertEquals("2h", Timeouts.text(Duration.ofHours(2)));
    }
}
