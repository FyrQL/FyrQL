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

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The events of Java Flight Recorder of the engine: {@code net.fyrql.Query} and {@code net.fyrql.Spill}. */
class JfrEventsTest {

    private static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.BIGINT),
            new Schema.Field("s", DataType.VARCHAR));

    @TempDir
    Path temp;

    private interface Work {
        void run() throws Exception;
    }

    private static FyrQL.Builder builder() {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < 20_000; n++) {
            rows.add(new Object[] {(long) n, "row " + (n * 7919 % 20_000)});
        }
        return FyrQL.builder().threads(2).register(MemoryConnector.builder("mem").table("t", NUMBERS, rows).build())
                .register(MemoryConnector.builder("out").build());
    }

    /** Records the events of FyrQL while work runs. */
    private List<RecordedEvent> record(String name, Work work) throws Exception {
        Path file = temp.resolve(name + ".jfr");
        try (Recording recording = new Recording()) {
            for (String event : List.of("net.fyrql.Query", "net.fyrql.Spill")) {
                recording.enable(event).withThreshold(Duration.ZERO);
            }
            recording.start();
            work.run();
            recording.stop();
            recording.dump(file);
        }
        return events(file);
    }

    private static List<RecordedEvent> events(Path file) throws IOException {
        return RecordingFile.readAllEvents(file).stream().filter(e -> e.getEventType().getName()
                .startsWith("net.fyrql.")).toList();
    }

    private static List<RecordedEvent> of(List<RecordedEvent> events, String name) {
        return events.stream().filter(e -> e.getEventType().getName().equals(name)).toList();
    }

    @Test
    void eachQueryIsAnEventWithItsTextRowsAndOutcome() throws Exception {
        try (FyrQL engine = builder().build()) {
            PlanNode plan = engine.plan("SELECT n FROM t WHERE n < 3");
            List<RecordedEvent> events = record("queries", () -> {
                assertEquals(10, engine.rows("SELECT n FROM t WHERE n < 10").size());
                assertThrows(RuntimeException.class, () -> engine.rows("SELECT CAST(s AS INTEGER) FROM t"));
                try (QueryResult result = engine.execute(engine.plan(
                        "SELECT count(*) FROM t a, t b WHERE a.n + b.n = 7"), QueryContext.withTimeout(
                        Duration.ofMillis(100)))) {
                    assertThrows(QueryCancelledException.class, result::rows);
                }
                assertEquals(3, engine.rows(plan).size());
                assertEquals(20_000, engine.update("CREATE TABLE out.copy AS SELECT * FROM t"));
            });
            List<RecordedEvent> queries = of(events, "net.fyrql.Query");
            assertEquals(5, queries.size(), events::toString);
            RecordedEvent ok = queries.get(0);
            assertEquals("SELECT n FROM t WHERE n < 10", ok.getString("sql"));
            assertEquals(10, ok.getLong("rows"));
            assertEquals("ok", ok.getString("outcome"));
            assertNull(ok.getString("error"));
            assertFalse(ok.getDuration().isNegative());
            RecordedEvent failed = queries.get(1);
            assertEquals("failed", failed.getString("outcome"));
            assertTrue(failed.getString("error").contains("row "), failed.getString("error"));
            RecordedEvent timeout = queries.get(2);
            assertEquals("timeout", timeout.getString("outcome"));
            // Without the text of the statement, the first line of the plan it ran.
            assertEquals(engine.explain("SELECT count(*) FROM t a, t b WHERE a.n + b.n = 7").split(" \\[|\n")[0],
                    timeout.getString("sql"));
            assertEquals(engine.explain(plan).split(" \\[|\n")[0], queries.get(3).getString("sql"));
            RecordedEvent write = queries.get(4);
            assertEquals("CREATE TABLE out.copy AS SELECT * FROM t", write.getString("sql"));
            assertEquals(20_000, write.getLong("rows"));
            assertEquals("ok", write.getString("outcome"));
        }
    }

    @Test
    void eachPartSpilledToDiskIsAnEvent() throws Exception {
        try (FyrQL engine = builder().memoryBudget(64 * 1024).spillDirectory(temp.resolve("spill")).build()) {
            List<RecordedEvent> events = record("spill", () -> {
                try (QueryResult result = engine.sql("SELECT n, s FROM t ORDER BY s")) {
                    assertEquals(20_000, result.rows().size());
                    assertTrue(result.spill().parts() > 0);
                }
            });
            List<RecordedEvent> spills = of(events, "net.fyrql.Spill");
            assertFalse(spills.isEmpty(), events::toString);
            for (RecordedEvent e : spills) {
                assertTrue(e.getString("operator").startsWith("Sort#"), e.getString("operator"));
                assertTrue(e.getLong("bytes") > 0 && e.getLong("rows") > 0 && e.getLong("batches") > 0,
                        e::toString);
            }
            RecordedEvent query = of(events, "net.fyrql.Query").get(0);
            assertEquals(spills.stream().mapToLong(e -> e.getLong("bytes")).sum(), query.getLong("spilledBytes"));
        }
    }

    @Test
    void withoutARecordingNothingChanges() {
        try (FyrQL engine = builder().build()) {
            assertEquals(10, engine.rows("SELECT n FROM t WHERE n < 10").size());
        }
    }
}
