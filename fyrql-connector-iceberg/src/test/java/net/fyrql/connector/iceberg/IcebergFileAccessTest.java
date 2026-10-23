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

package net.fyrql.connector.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fyrql.connector.FileAccess;
import net.fyrql.connector.FileAccessException;
import net.fyrql.connector.FileAccessPolicy;
import net.fyrql.connector.FileStores;
import net.fyrql.core.plan.TableRef;

/**
 * A table of a call reads only files that the policy of the engine allows,
 * also the manifests, data and delete files its metadata names; a
 * registered table reads its files anyway.
 */
class IcebergFileAccessTest {

    private static IcebergTestTables tables;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
    }

    private static IcebergConnector connector(String... allowed) {
        IcebergConnector connector = IcebergConnector.builder("ice").table("registered",
                tables.location("dv_v3").toString()).build();
        connector.fileStores(new FileStores(Map.of(), Path.of(""), FileAccessPolicy.of(FileAccess.READ,
                List.of(allowed), Path.of(""))));
        return connector;
    }

    private static TableRef scan(IcebergConnector connector, String name) {
        return connector.bind("iceberg_scan", List.of(tables.metadata(name).toString()), Map.of("timezone", "UTC"));
    }

    @Test
    void theFilesTheMetadataNamesMustBeAllowed() {
        for (String name : List.of("part", "eq_v2", "pos_v2", "dv_v3")) {
            IcebergConnector connector = connector(tables.location(name).resolve("metadata").toString());
            TableRef table = scan(connector, name);
            FileAccessException e = assertThrows(FileAccessException.class, () -> IcebergRows.read(connector,
                    table), name);
            assertTrue(e.getMessage().startsWith("Reading "), e.getMessage());
            assertTrue(e.getMessage().contains("is not under the allowed locations"), e.getMessage());
        }
    }

    @Test
    void theTableIsReadUnderAnAllowedLocation() {
        for (String name : List.of("part", "eq_v2", "pos_v2", "dv_v3")) {
            IcebergConnector connector = connector(tables.location(name).toString());
            assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name))),
                    IcebergRows.sorted(IcebergRows.read(connector, scan(connector, name))), name);
        }
    }

    @Test
    void aVersionHintMustNameAFileOfItsDirectory(@TempDir Path dir) throws IOException {
        Path metadata = Files.createDirectories(dir.resolve("t/metadata"));
        Files.copy(tables.metadata("part"), dir.resolve("evil.metadata.json"));
        for (String hint : List.of("../../evil.metadata.json", "..\\..\\evil", "..")) {
            Files.writeString(metadata.resolve("version-hint.text"), hint);
            IcebergConnector connector = connector(dir.toString());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> connector.bind("iceberg_scan", List.of(dir.resolve("t").toString()), Map.of()), hint);
            assertTrue(e.getMessage().endsWith("version-hint.text must name a version or a file of its directory,"
                    + " not a path"), e.getMessage());
        }
    }

    @Test
    void aLinkToMetadataElsewhereIsNotFollowedForACall(@TempDir Path dir) throws IOException {
        Path metadata = Files.createDirectories(dir.resolve("t/metadata"));
        try {
            Files.createSymbolicLink(metadata.resolve("v1.metadata.json"), tables.metadata("part"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links cannot be created here: " + e);
        }
        IcebergConnector connector = connector(dir.toString());
        FileAccessException e = assertThrows(FileAccessException.class,
                () -> connector.bind("iceberg_scan", List.of(dir.resolve("t").toString()), Map.of()));
        assertTrue(e.getMessage().contains("v1.metadata.json is not allowed"), e.getMessage());
    }

    @Test
    void aRegisteredTableReadsItsFilesAnyway() {
        IcebergConnector connector = connector(tables.location("part").toString());
        assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows("dv_v3"))),
                IcebergRows.sorted(IcebergRows.read(connector, TableRef.of("ice", "registered"))));
    }
}
