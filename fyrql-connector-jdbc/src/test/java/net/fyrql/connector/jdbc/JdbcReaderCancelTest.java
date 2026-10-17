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

package net.fyrql.connector.jdbc;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canceling a reader of a JDBC source from another thread, against H2. */
class JdbcReaderCancelTest {

    private static BatchReader open(JdbcConnector connector, String table) {
        ScanPlan plan = connector.plan(TableRef.of(connector.name(), table), ScanRequest.columns(List.of(0)));
        return connector.open(connector.splits(plan).get(0));
    }

    @Test
    void cancelingStopsAStatementTheDatabaseWorksOn() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        // A billion rows, which H2 would sum for a minute or more.
        db.execute("CREATE VIEW \"slow\" AS SELECT sum(X) AS \"s\" FROM SYSTEM_RANGE(1, 1000000000)"
                + " WHERE MOD(X, 7) = 3");
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try (JdbcConnector connector = db.connector("h2"); BatchReader reader = open(connector, "slow")) {
            long start = System.nanoTime();
            timer.schedule(reader::cancel, 300, TimeUnit.MILLISECONDS);
            JdbcSourceException e = assertThrows(JdbcSourceException.class, reader::next);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 2000, "the statement ran " + millis + " ms");
            assertTrue(e.getMessage().startsWith("The JDBC source h2 fails on SELECT"), e.getMessage());
            assertTrue(e.getMessage().contains("canceled"), "H2 tells that the statement was canceled");
            assertNull(reader.next(), "a failed reader is at its end");
            reader.cancel();
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void aReaderCanceledBeforeItsFirstBatchSendsNoStatement() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE \"t\" (\"n\" INTEGER)", "INSERT INTO \"t\" VALUES (1), (2)");
        try (JdbcConnector connector = db.connector("h2")) {
            try (BatchReader reader = open(connector, "t")) {
                reader.cancel();
                JdbcSourceException e = assertThrows(JdbcSourceException.class, reader::next);
                assertTrue(e.getMessage().startsWith("The JDBC source h2 was asked to stop before it ran SELECT"),
                        e.getMessage());
            }
            try (BatchReader reader = open(connector, "t")) {
                Batch b = reader.next();
                assertEquals(2, b.rowCount());
                assertNull(reader.next());
                reader.cancel();
            }
        }
    }
}
