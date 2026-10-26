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
package net.fyrql.connector;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStoreTest {

    @TempDir
    Path dir;

    private static List<String> names(List<FileObject> files) {
        return files.stream().map(FileObject::name).toList();
    }

    @Test
    void aLocationIsAFileADirectoryOrAPattern() throws IOException {
        Files.writeString(dir.resolve("b.parquet"), "b");
        Files.writeString(dir.resolve("a.PARQUET"), "a");
        Files.writeString(dir.resolve("c.csv"), "c");
        Files.createDirectory(dir.resolve("sub.parquet"));
        Files.writeString(dir.resolve("sub.parquet").resolve("d.parquet"), "d");
        LocalFileStore store = new LocalFileStore(Path.of(""));

        assertEquals(List.of("c.csv"), names(store.list(dir.resolve("c.csv").toString(), ".parquet")));
        assertEquals(List.of("a.PARQUET", "b.parquet"), names(store.list(dir.toString(), ".parquet")));
        assertEquals(List.of("a.PARQUET", "b.parquet", "c.csv"), names(store.list(dir.toString(), "")));
        assertEquals(List.of("a.PARQUET", "b.parquet", "c.csv"), names(store.list(dir + "/?.*", "")));
        assertEquals(List.of("b.parquet"), names(store.list(dir + "/*.parquet", "")));
        assertEquals(List.of(), store.list(dir.resolve("none.csv").toString(), ""));
        assertEquals(List.of(), store.list(dir.resolve("none") + "/*.csv", ""));
        assertEquals("A pattern is only supported in the name of a file: " + dir + "/*/x.csv",
                assertThrows(IllegalArgumentException.class, () -> store.list(dir + "/*/x.csv", "")).getMessage());

        FileObject b = store.list(dir.toString(), ".parquet").get(1);
        assertEquals(dir.resolve("b.parquet").toAbsolutePath().normalize().toString(), b.location());
        assertEquals(1, b.size());
        assertEquals(Optional.of(dir.resolve("b.parquet").toAbsolutePath().normalize()), b.path());
    }

    @Test
    void aTreeHasTheFilesAtAnyDepth() throws IOException {
        Path sales = dir.resolve("sales");
        Files.createDirectories(sales.resolve("year=2024").resolve("month=2"));
        Files.createDirectories(sales.resolve("year=2025").resolve("month=1"));
        Files.writeString(sales.resolve("year=2025").resolve("month=1").resolve("b.parquet"), "b");
        Files.writeString(sales.resolve("year=2024").resolve("month=2").resolve("a.parquet"), "a");
        Files.writeString(sales.resolve("year=2024").resolve("month=2").resolve("a.csv"), "c");
        Files.writeString(sales.resolve("top.parquet"), "t");
        LocalFileStore store = new LocalFileStore(dir);

        List<FileObject> all = store.listTree("sales", ".parquet");
        assertEquals(List.of("top.parquet", "a.parquet", "b.parquet"), names(all));
        assertEquals(sales.resolve("year=2024").resolve("month=2").resolve("a.parquet").toAbsolutePath().normalize()
                .toString(), all.get(1).location());
        assertEquals(List.of("top.parquet", "a.parquet", "b.parquet"),
                names(store.listTree("sales/**/*.parquet", "")));
        assertEquals(List.of("a.parquet", "b.parquet"), names(store.listTree("sales/*/*/*.parquet", "")));
        assertEquals(List.of("a.csv", "a.parquet"), names(store.listTree(sales + "/year=2024/**", "")));
        assertEquals(List.of("b.parquet"), names(store.listTree("sales/year=2025/month=?/*", "")));
        assertEquals(List.of("top.parquet"), names(store.listTree("sales/top.parquet", ".csv")));
        assertEquals(List.of(), store.listTree("none/**/*.csv", ""));
        assertEquals(List.of(), store.listTree("none", ""));
        assertEquals("The pattern ** must be a whole part of a location: sales/x**/*.csv",
                assertThrows(IllegalArgumentException.class, () -> store.listTree("sales/x**/*.csv", ""))
                        .getMessage());
    }

    @Test
    void aTreeDoesNotFollowLinksToDirectoriesBelowIt() throws IOException {
        Path outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("o.csv"), "o");
        Path tree = dir.resolve("tree");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("t.csv"), "t");
        try {
            Files.createSymbolicLink(tree.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("No symbolic links here: " + e);
        }
        LocalFileStore store = new LocalFileStore(dir);
        assertEquals(List.of("t.csv"), names(store.listTree("tree", ".csv")));
        assertEquals(List.of("t.csv"), names(store.listTree("tree/**/*.csv", "")));
        assertEquals(List.of("o.csv"), names(store.listTree("tree/link", ".csv")));
    }

    @Test
    void relativeLocationsAreResolvedAgainstTheDirectory() throws IOException {
        Files.writeString(dir.resolve("x.csv"), "0123456789");
        LocalFileStore store = new LocalFileStore(dir);
        FileObject x = store.object("x.csv");
        assertEquals(dir.resolve("x.csv").toAbsolutePath().normalize().toString(), x.location());
        assertEquals(x, store.object(dir.resolve("x.csv").toUri().toString()));
        assertEquals(List.of("x.csv"), names(store.list("*.csv", "")));
        assertEquals("missing.csv", assertThrows(NoSuchFileException.class, () -> store.object("missing.csv"))
                .getMessage());
        assertThrows(NoSuchFileException.class, () -> store.object("."));
    }

    @Test
    void aFileIsReadWholeOrFromAPosition() throws IOException {
        Files.writeString(dir.resolve("x.txt"), "0123456789");
        FileObject x = new LocalFileStore(dir).object("x.txt");
        try (InputStream in = x.open()) {
            assertEquals("0123456789", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
        ByteBuffer buffer = ByteBuffer.allocate(4);
        assertEquals(4, x.read(3, buffer));
        assertEquals("3456", new String(buffer.array(), StandardCharsets.US_ASCII));
        buffer.clear();
        assertEquals(2, x.read(8, buffer));
        assertEquals(2, buffer.position());
        buffer.clear();
        assertEquals(-1, x.read(10, buffer));
        assertEquals("x.txt", x.name());
    }

    @Test
    void theVersionChangesWithTheFile() throws IOException {
        Path file = dir.resolve("x.txt");
        Files.writeString(file, "one");
        LocalFileStore store = new LocalFileStore(dir);
        FileObject before = store.object("x.txt");
        assertEquals(before, store.object("x.txt"));
        Files.writeString(file, "three");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2000));
        FileObject after = store.object("x.txt");
        assertNotEquals(before.version(), after.version());
        assertEquals(5, after.size());
    }

    @Test
    void aFileAppearsWithTheCommitOnly() throws IOException {
        LocalFileStore store = new LocalFileStore(dir);
        try (FileOutput out = store.create("x.txt")) {
            assertEquals(dir.resolve("x.txt").toAbsolutePath().normalize().toString(), out.location());
            OutputStream stream = out.stream();
            assertSame(stream, out.stream());
            stream.write("hello".getBytes(StandardCharsets.US_ASCII));
            assertFalse(Files.exists(dir.resolve("x.txt")));
            out.commit();
        }
        assertEquals("hello", Files.readString(dir.resolve("x.txt")));

        try (FileOutput out = store.create("x.txt")) {
            out.stream().write("bye".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("hello", Files.readString(dir.resolve("x.txt")));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }

        try (FileOutput out = store.create("empty.txt")) {
            out.commit();
        }
        assertEquals(0, Files.size(dir.resolve("empty.txt")));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.create("no/x.txt")).getMessage()
                .endsWith(" does not exist"));
    }

    @Test
    void theSchemeOfALocationChoosesTheStore() throws IOException {
        assertEquals(Optional.of("s3"), FileStores.scheme("s3://bucket/key"));
        assertEquals(Optional.of("s3"), FileStores.scheme("S3://bucket/key"));
        assertEquals(Optional.of("file"), FileStores.scheme("file:///tmp/x"));
        assertEquals(Optional.empty(), FileStores.scheme("C:\\data\\x.csv"));
        assertEquals(Optional.empty(), FileStores.scheme("C:/data/x.csv"));
        assertEquals(Optional.empty(), FileStores.scheme("data/x.csv"));
        assertTrue(FileStores.isLocal("file:///tmp/x"));
        assertTrue(FileStores.isLocal("x.csv"));
        assertFalse(FileStores.isLocal("s3://b/k"));

        Files.writeString(dir.resolve("x.csv"), "x");
        FileStore fake = new FileStore() {
            @Override
            public String scheme() {
                return "mem";
            }

            @Override
            public List<FileObject> list(String location, String extension) {
                return List.of();
            }

            @Override
            public FileObject object(String location) throws IOException {
                throw new NoSuchFileException(location);
            }

            @Override
            public FileOutput create(String location) {
                throw new UnsupportedOperationException();
            }
        };
        FileStores stores = new FileStores(Map.of("MEM", fake), dir);
        assertEquals(List.of("mem"), stores.schemes());
        assertSame(fake, stores.store("mem://b/k"));
        assertSame(stores.local(), stores.store("x.csv"));
        assertEquals("x.csv", stores.object("x.csv").name());
        assertEquals("mem://b/k", assertThrows(NoSuchFileException.class, () -> stores.object("mem://b/k"))
                .getMessage());
        assertEquals("No source handles s3:// locations; register a source of the type s3",
                assertThrows(IllegalArgumentException.class, () -> stores.object("s3://b/k")).getMessage());
        assertEquals("No source handles s3:// locations; register a source of the type s3",
                assertThrows(IllegalArgumentException.class, () -> FileStores.local(dir).list("s3://b/", ""))
                        .getMessage());
    }
}
