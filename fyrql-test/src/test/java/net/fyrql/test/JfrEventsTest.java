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

package net.fyrql.test;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.s3.FakeS3Server;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.s3.S3Credentials;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenMode;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The events of Java Flight Recorder of the modules besides the engine:
 * {@code net.fyrql.Compile} of the code generation and
 * {@code net.fyrql.SourceRequest} of the connectors.
 */
class JfrEventsTest {

    private static final Schema ROWS = Schema.of(new Schema.Field("a", DataType.BIGINT),
            new Schema.Field("b", DataType.INTEGER), new Schema.Field("c", DataType.BIGINT));

    @TempDir
    Path temp;

    private interface Work {
        void run() throws Exception;
    }

    private static List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < 5000; n++) {
            rows.add(new Object[] {(long) n, n % 101, (long) (n * 31 % 977)});
        }
        return rows;
    }

    /** Records the events of FyrQL while work runs. */
    private List<RecordedEvent> record(String name, Work work) throws Exception {
        Path file = temp.resolve(name + ".jfr");
        try (Recording recording = new Recording()) {
            for (String event : List.of("net.fyrql.Query", "net.fyrql.Compile", "net.fyrql.SourceRequest")) {
                recording.enable(event).withThreshold(Duration.ZERO);
            }
            recording.start();
            work.run();
            recording.stop();
            recording.dump(file);
        }
        return RecordingFile.readAllEvents(file).stream().filter(e -> e.getEventType().getName()
                .startsWith("net.fyrql.")).toList();
    }

    private static List<RecordedEvent> of(List<RecordedEvent> events, String name) {
        return events.stream().filter(e -> e.getEventType().getName().equals(name)).toList();
    }

    @Test
    void eachClassThatIsCompiledIsAnEvent() throws Exception {
        try (FyrQL engine = FyrQL.builder().codegen(CodegenMode.ALWAYS)
                .register(MemoryConnector.builder("mem").table("t", ROWS, rows()).build()).build()) {
            // An expression no other test compiles, so that the shared cache of the compiler does not hold it.
            String sql = "SELECT CASE WHEN a % 97 = 3 THEN abs(b - 11) * 13 ELSE least(a, c) + 5 END AS x FROM t"
                    + " WHERE c * 7 - b > a % 89";
            List<RecordedEvent> events = record("compile", () -> assertFalse(engine.rows(sql).isEmpty()));
            List<RecordedEvent> compiles = of(events, "net.fyrql.Compile");
            assertFalse(compiles.isEmpty(), events::toString);
            for (RecordedEvent e : compiles) {
                assertTrue(Set.of("CompiledFilter", "CompiledProjection", "CompiledPipeline", "KeyFunctions")
                        .contains(e.getString("kind")), e::toString);
                assertEquals("compiled", e.getString("outcome"), e::toString);
                assertTrue(e.getLong("sourceLength") > 0, e::toString);
                assertFalse(e.getString("className").isEmpty(), e::toString);
            }
        }
    }

    @Test
    void theStatementsSentToADatabaseAreRequests() throws Exception {
        TestDatabase db = TestDatabases.h2();
        db.load("numbers", ROWS, rows());
        try (FyrQL engine = FyrQL.builder().threads(1).register(db.connector("db")).build()) {
            List<RecordedEvent> events = record("jdbc", () -> assertEquals(5000L,
                    engine.rows("SELECT count(*) FROM db.numbers WHERE b >= 0").get(0)[0]));
            List<RecordedEvent> requests = of(events, "net.fyrql.SourceRequest");
            assertFalse(requests.isEmpty(), events::toString);
            for (RecordedEvent e : requests) {
                assertEquals("db", e.getString("source"));
                assertEquals("query", e.getString("kind"));
                assertTrue(e.getString("target").startsWith("SELECT "), e.getString("target"));
            }
        }
    }

    @Test
    void theRequestsToS3AreEvents() throws Exception {
        try (FakeS3Server server = FakeS3Server.start("fyrql", "fyrql-secret");
                FyrQL engine = FyrQL.builder()
                        .register(MemoryConnector.builder("mem").table("t", ROWS, rows()).build())
                        .register(S3Connector.builder("lake").endpoint(server.endpoint())
                                .credentials(S3Credentials.of("fyrql", "fyrql-secret")).useEnvironment(false).build())
                        .build()) {
            server.createBucket("lake");
            assertEquals(5000, engine.update("COPY mem.t TO 's3://lake/t.parquet'"));
            List<RecordedEvent> events = record("s3", () -> assertEquals(5000L,
                    engine.rows("SELECT count(b) FROM 's3://lake/t.parquet'").get(0)[0]));
            List<RecordedEvent> requests = of(events, "net.fyrql.SourceRequest");
            assertFalse(requests.isEmpty(), events::toString);
            assertTrue(requests.stream().anyMatch(e -> e.getString("kind").equals("GET")
                    && e.getInt("status") / 100 == 2 && e.getLong("bytes") > 0), requests::toString);
            for (RecordedEvent e : requests) {
                assertEquals("lake", e.getString("source"));
                assertTrue(e.getString("target").startsWith(server.endpoint()), e.getString("target"));
            }
            assertEquals(1, of(events, "net.fyrql.Query").size(), events::toString);
        }
    }
}
