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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Which locations a {@link FileAccessPolicy} allows, and how it resolves them. */
class FileAccessPolicyTest {

    @TempDir
    Path dir;

    @Test
    void theAccessIsParsedInAnyCase() {
        assertEquals(FileAccess.NONE, FileAccess.parse("none"));
        assertEquals(FileAccess.READ, FileAccess.parse(" READ "));
        assertEquals(FileAccess.READ_WRITE, FileAccess.parse("readwrite"));
        assertEquals("readWrite", FileAccess.READ_WRITE.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FileAccess.parse("all"));
        assertEquals("Unknown file access all; use none, read or readWrite", e.getMessage());
    }

    @Test
    void readWriteWithoutLocationsAllowsEverything() {
        assertSame(FileAccessPolicy.ALL, FileStores.local(dir).policy());
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ_WRITE, List.of(), dir);
        assertDoesNotThrow(() -> policy.checkRead("/anywhere/x.csv"));
        assertDoesNotThrow(() -> policy.checkWrite("s3://any/x.csv"));
        assertEquals("readWrite anywhere", policy.toString());
    }

    @Test
    void noneAllowsNothing() {
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.NONE, List.of(), dir);
        FileAccessException e = assertThrows(FileAccessException.class, () -> policy.checkRead("x.csv"));
        assertEquals("Reading x.csv is not allowed: the engine allows no access to files from SQL (fileAccess none)",
                e.getMessage());
        assertThrows(FileAccessException.class, () -> policy.checkWrite("s3://lake/x.csv"));
        assertEquals("none", policy.toString());
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> FileAccessPolicy.of(FileAccess.NONE, List.of("data"), dir));
        assertEquals("allowedLocations needs fileAccess read or readWrite, not none", wrong.getMessage());
    }

    @Test
    void readAllowsNoWriting() {
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ, List.of(), dir);
        assertDoesNotThrow(() -> policy.checkRead("/anywhere/x.csv"));
        FileAccessException e = assertThrows(FileAccessException.class, () -> policy.checkWrite("out.csv"));
        assertEquals("Writing out.csv is not allowed: the engine allows only reading files from SQL"
                + " (fileAccess read)", e.getMessage());
        assertEquals("read anywhere", policy.toString());
    }

    @Test
    void localLocationsAreResolvedAndNormalized() throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(dir.resolve("data2"));
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ_WRITE, List.of("data"), dir);
        assertEquals(List.of(data.toRealPath().toString()), policy.locations());
        assertDoesNotThrow(() -> policy.checkRead(data.resolve("x.csv").toString()));
        assertDoesNotThrow(() -> policy.checkRead("data/x.csv"));
        assertDoesNotThrow(() -> policy.checkWrite("data/new/deeper/x.csv"));
        assertDoesNotThrow(() -> policy.checkRead("data"));
        assertDoesNotThrow(() -> policy.checkRead(data.resolve("x.csv").toUri().toString()));
        assertDoesNotThrow(() -> policy.checkRead("data/other/../x.csv"));
        FileAccessException e = assertThrows(FileAccessException.class, () -> policy.checkRead("data/../x.csv"));
        assertEquals("Reading data/../x.csv is not allowed: it is not under the allowed locations "
                + data.toRealPath(), e.getMessage());
        assertThrows(FileAccessException.class, () -> policy.checkRead("data2/x.csv"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("x.csv"));
        assertThrows(FileAccessException.class, () -> policy.checkWrite("s3://lake/x.csv"));
        assertEquals("readWrite under " + data.toRealPath(), policy.toString());
    }

    @Test
    void theDirectoryBeforeAPatternCounts() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ, List.of("data"), dir);
        assertDoesNotThrow(() -> policy.checkRead("data/*.parquet"));
        assertDoesNotThrow(() -> policy.checkRead("data/sub/part-?.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("*.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("data/../*.parquet"));
    }

    @Test
    void symbolicLinksAreFollowed() throws IOException {
        Path data = Files.createDirectories(dir.resolve("data"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.csv"), "a\n1\n");
        Path link = data.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links cannot be created here: " + e);
        }
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ_WRITE, List.of("data"), dir);
        assertThrows(FileAccessException.class, () -> policy.checkRead("data/link/secret.csv"));
        assertThrows(FileAccessException.class, () -> policy.checkWrite("data/link/new.csv"));
        if (!System.getProperty("os.name").startsWith("Windows")) {
            // Windows removes ".." before it follows links, other systems after: there, this is outside/../x.csv.
            assertThrows(FileAccessException.class, () -> policy.checkWrite("data/link/../x.csv"));
        }
        FileAccessPolicy viaLink = FileAccessPolicy.of(FileAccess.READ, List.of("data/link"), dir);
        assertEquals(List.of(outside.toRealPath().toString()), viaLink.locations());
        assertDoesNotThrow(() -> viaLink.checkRead("outside/secret.csv"));
    }

    @Test
    void aShareIsDeniedWithoutAskingItsHost() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "shares are paths only on Windows");
        Files.createDirectories(dir.resolve("data"));
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ, List.of("data"), dir);
        long start = System.nanoTime();
        for (String share : List.of("\\\\fyrql-test.invalid\\share\\x.csv", "//fyrql-test.invalid/share/x.csv")) {
            FileAccessException e = assertThrows(FileAccessException.class, () -> policy.checkRead(share));
            assertTrue(e.getMessage().contains("is not under the allowed locations"), e.getMessage());
        }
        // Asking a host that does not exist takes seconds; a check that does not ask takes none.
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "the host was asked");
    }

    @Test
    void remoteLocationsArePrefixesWithABoundary() {
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ_WRITE, List.of("s3://lake", "S3://sales/2026/"),
                dir);
        assertEquals(List.of("s3://lake/", "s3://sales/2026/"), policy.locations());
        assertDoesNotThrow(() -> policy.checkRead("s3://lake/x.parquet"));
        assertDoesNotThrow(() -> policy.checkRead("s3://lake"));
        assertDoesNotThrow(() -> policy.checkRead("S3://lake/a/*.parquet"));
        assertDoesNotThrow(() -> policy.checkWrite("s3://sales/2026/q1.csv"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("s3://lakehouse/x.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("s3://sales/2025/q1.csv"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("s3://lake/../other/x.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("s3://lake/./x.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead("gs://lake/x.parquet"));
        assertThrows(FileAccessException.class, () -> policy.checkRead(dir.resolve("x.csv").toString()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FileAccessPolicy.of(FileAccess.READ, List.of("s3://lake/../x"), dir));
        assertEquals("An allowed location must not have the parts . or ..: s3://lake/../x", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FileAccessPolicy.of(FileAccess.READ, List.of(" "), dir));
    }

    @Test
    void theStoresCarryThePolicy() {
        FileAccessPolicy policy = FileAccessPolicy.of(FileAccess.READ, List.of(), dir);
        FileStores stores = new FileStores(Map.of(), dir, policy);
        assertSame(policy, stores.policy());
        assertTrue(stores.policy().access().reads());
        assertFalse(stores.policy().access().writes());
    }
}
