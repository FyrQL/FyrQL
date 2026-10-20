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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.s3.FakeS3Server;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.s3.S3Credentials;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round trip of {@link S3RoundTrip} against the fake S3 of the tests,
 * which checks every signature, and what the fake shows besides: which
 * ranges of an object a query reads, and that a request that fails for a
 * while is tried again.
 */
class S3RoundTripTest extends S3RoundTrip {

    private FakeS3Server server;

    @Override
    S3Connector connect() {
        server = FakeS3Server.start("fyrql", "fyrql-secret");
        server.createBucket(bucket());
        return S3Connector.builder("s3").endpoint(server.endpoint()).credentials(S3Credentials.of("fyrql",
                "fyrql-secret")).useEnvironment(false).build();
    }

    @Override
    String bucket() {
        return "lake";
    }

    @Override
    void cleanUp() {
        server.close();
    }

    @Test
    void theFooterTakesOneRequestAndAPrunedObjectNoMore() {
        String location = location("requests/big.parquet");
        assertEquals(BIG_ROWS, engine.update("COPY memory.big TO '" + location + "' (ROW_GROUP_SIZE 1000)"));
        server.clearRequests();
        assertEquals(List.of(0L), engine.rows("SELECT count(*) FROM '" + location + "' WHERE id < 0").stream()
                .map(r -> r[0]).toList());
        // The footer; without pushdown the column chunk of each row group besides.
        List<String> gets = server.requests().stream().filter(r -> r.startsWith("GET /lake/requests/")).toList();
        assertEquals(pushdown() ? 1 : 11, gets.size(), server.requests().toString());
        assertTrue(gets.stream().allMatch(g -> g.contains("[bytes=")), gets.toString());
        assertEquals(gets.size(), gets.stream().distinct().count(), "no range is read twice: " + gets);
        assertTrue(server.requests().stream().allMatch(r -> r.startsWith("GET ") || r.startsWith("HEAD ")),
                server.requests().toString());
    }

    @Test
    void theTablesOfSourcesOfFormatsMayBeInS3AndAreLookedUpWhenFirstUsed() {
        assertEquals(6, engine.update("COPY memory.t TO '" + location("config/t.csv") + "'"));
        assertEquals(6, engine.update("COPY memory.t TO '" + location("config/t.json.gz") + "'"));
        try (FyrQL other = FyrQL.builder()
                .register(CsvConnector.builder("files").file("t", location("config/t.csv"))
                        .file("none", location("config/none.csv")).build())
                .register(JsonConnector.builder("docs").file("t", location("config/t.json.gz")).build())
                .register(S3Connector.builder("s3").endpoint(server.endpoint()).credentials(S3Credentials.of("fyrql",
                        "fyrql-secret")).useEnvironment(false).build()).build()) {
            assertEquals(List.of(6L), other.rows("SELECT count(*) FROM files.t").stream().map(r -> r[0]).toList());
            assertEquals(List.of(6L), other.rows("SELECT count(*) FROM docs.t").stream().map(r -> r[0]).toList());
            assertEquals("The table none of the CSV source files: No files found that match the pattern \""
                    + location("config/none.csv") + "\"", assertThrows(IllegalArgumentException.class,
                            () -> other.rows("SELECT * FROM files.none")).getMessage());
        }
    }

    @Test
    void aRequestThatFailsForAWhileIsTriedAgain() {
        assertEquals(6, engine.update("COPY memory.t TO '" + location("retry/t.parquet") + "'"));
        server.fail(2, 503);
        assertEquals(List.of(6L), engine.rows("SELECT count(*) FROM '" + location("retry/t.parquet") + "'").stream()
                .map(r -> r[0]).toList());
    }

    @Test
    void aWrongSecretIsAnErrorWithTheCodeOfS3() {
        try (FyrQL other = FyrQL.builder().register(MemoryConnector.builder("memory").build())
                .register(S3Connector.builder("s3").endpoint(server.endpoint()).credentials(S3Credentials.of("fyrql",
                        "wrong")).useEnvironment(false).build()).build()) {
            String reason = assertThrows(SqlException.class, () -> other.rows("SELECT * FROM '"
                    + location("retry/t.parquet") + "'")).reason();
            assertTrue(reason.contains("S3 refused HEAD " + location("retry/t.parquet") + ": 403"), reason);
        }
    }

    @Test
    void withoutASourceOfS3ALocationOfS3IsAnError() throws IOException {
        try (FyrQL other = FyrQL.builder().register(MemoryConnector.builder("memory").build()).build()) {
            assertEquals("No source handles s3:// locations; register a source of the type s3",
                    assertThrows(SqlException.class, () -> other.rows("SELECT * FROM 's3://lake/t.parquet'"))
                            .reason());
            assertEquals("No source handles s3:// locations; register a source of the type s3",
                    assertThrows(SqlException.class, () -> other.update("COPY (SELECT 1 AS a) TO"
                            + " 's3://lake/a.parquet'")).reason());
        }
    }
}
