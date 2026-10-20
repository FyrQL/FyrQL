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

import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The objects of the fake as files: listings, reads in ranges and uploads at once or in parts. */
class S3FileStoreTest {

    private FakeS3Server server;
    private S3Connector connector;
    private FileStore store;

    @BeforeEach
    void start() {
        server = FakeS3Server.start("fyrql", "secret");
        connector = S3Connector.builder("s3").endpoint(server.endpoint()).credentials(S3Credentials.of("fyrql",
                "secret")).partSize(S3Connector.MIN_PART_SIZE).environment(Map.of(), null).build();
        store = connector.fileStore().orElseThrow();
        for (String key : List.of("events/2026-01.parquet", "events/2026-02.parquet", "events/notes.txt",
                "events/old/2025.parquet", "events/", "single.parquet")) {
            server.put("lake", key, key.getBytes(StandardCharsets.UTF_8));
        }
        server.pageSize(2);
    }

    @AfterEach
    void stop() {
        connector.close();
        server.close();
    }

    private static List<String> names(List<FileObject> files) {
        return files.stream().map(FileObject::location).toList();
    }

    @Test
    void aLocationIsAnObjectAPrefixOrAPattern() throws IOException {
        assertEquals("s3", store.scheme());
        assertEquals(List.of("s3://lake/single.parquet"), names(store.list("s3://lake/single.parquet", ".parquet")));
        List<String> parquet = List.of("s3://lake/events/2026-01.parquet", "s3://lake/events/2026-02.parquet");
        assertEquals(parquet, names(store.list("s3://lake/events/", ".parquet")));
        assertEquals(parquet, names(store.list("s3://lake/events", ".parquet")));
        assertEquals(parquet, names(store.list("s3://lake/events/2026-*", "")));
        assertEquals(List.of("s3://lake/events/2026-01.parquet", "s3://lake/events/2026-02.parquet",
                "s3://lake/events/notes.txt"), names(store.list("s3://lake/events/", "")));
        assertEquals(List.of("s3://lake/events/2026-02.parquet"), names(store.list("s3://lake/events/*2.p?rquet",
                "")));
        assertEquals(List.of(), store.list("s3://lake/none/*.parquet", ""));
        assertEquals(List.of(), store.list("s3://lake/none.parquet", ".parquet"));
        assertEquals("A pattern is only supported in the name of a file: s3://lake/*/x.parquet",
                assertThrows(IllegalArgumentException.class, () -> store.list("s3://lake/*/x.parquet", ""))
                        .getMessage());
        FileObject first = store.list("s3://lake/events/", ".parquet").get(0);
        assertEquals("2026-01.parquet", first.name());
        assertEquals(22, first.size());
        assertTrue(first.path().isEmpty());
        assertEquals(store.object("s3://lake/events/2026-01.parquet").version(), first.version());
        assertEquals("s3://lake/events/", assertThrows(NoSuchFileException.class,
                () -> store.object("s3://lake/events/")).getMessage());
        assertEquals("s3://lake/x", assertThrows(NoSuchFileException.class, () -> store.object("s3://lake/x"))
                .getMessage());
    }

    @Test
    void aFileIsReadWholeOrInRanges() throws IOException {
        FileObject file = store.object("s3://lake/single.parquet");
        try (InputStream in = file.open()) {
            assertEquals("single.parquet", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        ByteBuffer buffer = ByteBuffer.allocate(20);
        buffer.put((byte) '>');
        assertEquals(7, file.read(7, buffer));
        assertEquals(">parquet", new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
        assertEquals(-1, file.read(14, ByteBuffer.allocate(3)));
        server.clearRequests();
        assertEquals(0, file.read(3, ByteBuffer.allocate(0)));
        assertEquals(List.of(), server.requests());
    }

    @Test
    void aSmallFileIsWrittenAtOnceWithTheCommit() throws IOException {
        try (FileOutput out = store.create("s3://lake/out/a.csv")) {
            assertEquals("s3://lake/out/a.csv", out.location());
            out.stream().write("a,b\n".getBytes(StandardCharsets.UTF_8));
            out.stream().close();
            assertNull(server.get("lake", "out/a.csv"));
            out.commit();
        }
        assertEquals("a,b\n", new String(server.get("lake", "out/a.csv"), StandardCharsets.UTF_8));
        try (FileOutput out = store.create("s3://lake/out/b.csv")) {
            out.stream().write(1);
        }
        assertNull(server.get("lake", "out/b.csv"));
        assertEquals("Cannot write s3://lake/out/: it is not the location of an object",
                assertThrows(IllegalArgumentException.class, () -> store.create("s3://lake/out/")).getMessage());
    }

    @Test
    void aLargeFileIsUploadedInParts() throws IOException {
        byte[] data = new byte[12 * 1024 * 1024 + 17];
        new SplittableRandom(1).nextBytes(data);
        try (FileOutput out = store.create("s3://lake/big.bin")) {
            OutputStream stream = out.stream();
            for (int off = 0; off < data.length; off += 100_000) {
                stream.write(data, off, Math.min(100_000, data.length - off));
            }
            assertEquals(1, server.openUploads());
            out.commit();
        }
        assertArrayEquals(data, server.get("lake", "big.bin"));
        assertEquals(0, server.openUploads());
        assertEquals(3, server.requests().stream().filter(r -> r.startsWith("PUT /lake/big.bin?partNumber"))
                .count());

        try (FileOutput out = store.create("s3://lake/big2.bin")) {
            out.stream().write(data);
        }
        assertEquals(0, server.openUploads());
        assertNull(server.get("lake", "big2.bin"));
        FileObject big = store.object("s3://lake/big.bin");
        ByteBuffer tail = ByteBuffer.allocate(17);
        assertEquals(17, big.read(data.length - 17, tail));
        byte[] expected = new byte[17];
        System.arraycopy(data, data.length - 17, expected, 0, 17);
        assertArrayEquals(expected, tail.array());
    }

    @Test
    void theSourceHasNoTables() {
        assertEquals(List.of(), connector.catalog().tables("main"));
        assertEquals("The S3 source s3 has no tables; read its files with FROM 's3://bucket/key' or a table function"
                + " such as read_parquet", assertThrows(IllegalArgumentException.class,
                        () -> connector.plan(net.fyrql.core.plan.TableRef.of("s3", "t"), null)).getMessage());
    }
}
