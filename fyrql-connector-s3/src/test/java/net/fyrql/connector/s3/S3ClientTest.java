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

package net.fyrql.connector.s3;

import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The operations of the client against the fake, signed and checked there. */
class S3ClientTest {

    private static final S3Credentials CREDENTIALS = S3Credentials.of("fyrql", "secret/key+1");

    private FakeS3Server server;
    private S3Client client;

    @BeforeEach
    void start() {
        server = FakeS3Server.start(CREDENTIALS.accessKey(), CREDENTIALS.secretKey());
        client = client(CREDENTIALS, 3);
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
    }

    private S3Client client(S3Credentials credentials, int retries) {
        return new S3Client("s3", URI.create(server.endpoint()), "eu-central-1", true, credentials,
                Duration.ofSeconds(10), retries);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void objectsAreWrittenReadAndDeleted() throws IOException {
        client.createBucket("lake");
        S3Location a = S3Location.parse("s3://lake/dir/a b+c\u00E4.txt");
        client.put(a, bytes("0123456789xyz"), 10);
        assertArrayEquals(bytes("0123456789"), server.get("lake", "dir/a b+c\u00E4.txt"));

        S3Client.Head head = client.head(a);
        assertEquals(10, head.size());
        assertTrue(head.etag().startsWith("\""), head.etag());

        ByteBuffer buffer = ByteBuffer.allocate(4);
        client.read(a, head.etag(), 3, buffer);
        assertEquals("3456", new String(buffer.array(), StandardCharsets.UTF_8));
        try (InputStream in = client.get(a, head.etag())) {
            assertEquals("0123456789", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(server.requests().contains("GET /lake/dir/a%20b%2Bc%C3%A4.txt [bytes=3-6]"), server.requests()
                .toString());

        client.put(a, bytes("changed"), 7);
        S3Exception changed = assertThrows(S3Exception.class, () -> client.read(a, head.etag(), 0,
                ByteBuffer.allocate(2)));
        assertEquals(412, changed.status());
        assertEquals("The object s3://lake/dir/a b+c\u00E4.txt changed since it was looked up", changed.getMessage());
        assertThrows(S3Exception.class, () -> client.get(a, head.etag()));

        client.delete(a);
        assertNull(server.get("lake", "dir/a b+c\u00E4.txt"));
        assertEquals("s3://lake/dir/a b+c\u00E4.txt", assertThrows(NoSuchFileException.class, () -> client.head(a))
                .getMessage());
        assertThrows(NoSuchFileException.class, () -> client.get(a, null));
        client.deleteBucket("lake");
        assertEquals(List.of(), server.keys("lake"));
    }

    @Test
    void aListingIsReadPageByPage() throws IOException {
        for (String key : List.of("x/1.parquet", "x/2.parquet", "x/3.csv", "x/sub/4.parquet", "x/", "y/5.parquet",
                "x/6 \u00E4&<.parquet")) {
            server.put("lake", key, bytes(key));
        }
        server.pageSize(2);
        List<S3Client.Listed> all = client.list("lake", "x/", null);
        assertEquals(List.of("x/", "x/1.parquet", "x/2.parquet", "x/3.csv", "x/6 \u00E4&<.parquet", "x/sub/4.parquet"),
                all.stream().map(S3Client.Listed::key).toList());
        assertEquals(11, all.get(1).size());
        List<S3Client.Listed> direct = client.list("lake", "x/", "/");
        assertEquals(List.of("x/", "x/1.parquet", "x/2.parquet", "x/3.csv", "x/6 \u00E4&<.parquet"),
                direct.stream().map(S3Client.Listed::key).toList());
        assertTrue(server.requests().stream().filter(r -> r.startsWith("GET /lake?")).count() > 3);
        assertEquals(List.of(), client.list("lake", "none/", "/"));
        S3Exception e = assertThrows(S3Exception.class, () -> client.list("none", "", "/"));
        assertEquals(404, e.status());
        assertEquals("NoSuchBucket", e.code());
        assertEquals("S3 refused GET s3://none/: 404 NoSuchBucket (The bucket does not exist)", e.getMessage());
    }

    @Test
    void anObjectIsWrittenInParts() throws IOException {
        server.createBucket("lake");
        S3Location a = S3Location.parse("s3://lake/big.bin");
        server.minPartSize(4);
        String id = client.createMultipart(a);
        List<String> etags = List.of(client.uploadPart(a, id, 1, bytes("abcd"), 4),
                client.uploadPart(a, id, 2, bytes("efgh"), 4), client.uploadPart(a, id, 3, bytes("ij"), 2));
        assertEquals(1, server.openUploads());
        assertNull(server.get("lake", "big.bin"));
        client.completeMultipart(a, id, etags);
        assertEquals(0, server.openUploads());
        assertArrayEquals(bytes("abcdefghij"), server.get("lake", "big.bin"));

        String other = client.createMultipart(a);
        client.uploadPart(a, other, 1, bytes("zz"), 2);
        client.abortMultipart(a, other);
        assertEquals(0, server.openUploads());
        assertArrayEquals(bytes("abcdefghij"), server.get("lake", "big.bin"));

        server.minPartSize(5);
        String small = client.createMultipart(a);
        List<String> parts = List.of(client.uploadPart(a, small, 1, bytes("abcd"), 4),
                client.uploadPart(a, small, 2, bytes("e"), 1));
        assertEquals("EntityTooSmall", assertThrows(S3Exception.class, () -> client.completeMultipart(a, small,
                parts)).code());
    }

    @Test
    void eachAttemptCountsAsARequestAndTheBodiesAsBytes() throws IOException {
        server.put("lake", "k", bytes("0123456789"));
        S3Location k = S3Location.parse("s3://lake/k");
        server.fail(2, 503);
        SourceCounters counters = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(counters)) {
            S3Client.Head head = client.head(k);
            client.read(k, head.etag(), 3, ByteBuffer.allocate(4));
            try (InputStream in = client.get(k, head.etag())) {
                assertEquals(10, in.readAllBytes().length);
            }
        }
        client.head(k);
        SourceStatistics read = counters.snapshot();
        assertEquals(3 + 1 + 1, read.requests());
        assertEquals(server.requests().size() - 1, read.requests());
        assertEquals(4 + 10, read.bytes());
        assertTrue(read.requestNanos() > 0);
    }

    @Test
    void failuresThatMayPassAreTriedAgain() throws IOException {
        server.put("lake", "k", bytes("v"));
        server.fail(2, 503);
        assertEquals(1, client.head(S3Location.parse("s3://lake/k")).size());
        assertEquals(3, server.requests().size());

        server.clearRequests();
        server.fail(4, 500);
        S3Exception e = assertThrows(S3Exception.class, () -> client.head(S3Location.parse("s3://lake/k")));
        assertEquals(500, e.status());
        assertEquals(4, server.requests().size());

        server.clearRequests();
        try (S3Client none = client(CREDENTIALS, 0)) {
            server.fail(1, 503);
            assertEquals(503, assertThrows(S3Exception.class, () -> none.head(S3Location.parse("s3://lake/k")))
                    .status());
        }
        assertEquals(1, server.requests().size());
    }

    @Test
    void aWrongSecretIsRefusedAtOnce() throws IOException {
        server.put("lake", "k", bytes("v"));
        try (S3Client wrong = client(S3Credentials.of("fyrql", "other"), 3)) {
            S3Exception e = assertThrows(S3Exception.class, () -> wrong.list("lake", "", "/"));
            assertEquals(403, e.status());
            assertEquals("SignatureDoesNotMatch", e.code());
            assertTrue(e.getMessage().startsWith("S3 refused GET s3://lake/: 403 SignatureDoesNotMatch"),
                    e.getMessage());
        }
        assertEquals(1, server.requests().size());
        try (S3Client anonymous = client(null, 3)) {
            assertEquals("AccessDenied", assertThrows(S3Exception.class,
                    () -> anonymous.list("lake", "", "/")).code());
        }
    }

    @Test
    void aSessionTokenIsSentAndSigned() throws IOException {
        server.put("lake", "k", bytes("v"));
        try (S3Client temporary = client(new S3Credentials("fyrql", "secret/key+1", "token/1"), 0)) {
            assertEquals(1, temporary.head(S3Location.parse("s3://lake/k")).size());
        }
    }
}
