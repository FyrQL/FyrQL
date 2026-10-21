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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.apache.iceberg.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.fyrql.connector.ConnectorFactory;
import net.fyrql.core.plan.TableRef;

/** Iceberg sources from the options of a configuration. */
class IcebergConnectorFactoryTest {

    private static IcebergTestTables tables;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
    }

    @Test
    void theFactoryIsFoundAndImplicit() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("iceberg")).findFirst().orElseThrow();
        assertTrue(factory.implicit());
        assertEquals(List.of(), factory.fileFormats());
        IcebergConnector empty = (IcebergConnector) factory.create("iceberg", Map.of());
        assertEquals(List.of(), empty.catalog().tables(TableRef.MAIN));
        assertEquals(List.of("iceberg_scan", "read_iceberg", "iceberg_snapshots"), empty.tableFunctions());
    }

    @Test
    void tablesWithTheirSnapshotsRelativeToTheDirectory() {
        Table branch = tables.table("branch");
        Path warehouse = tables.warehouse().resolve("db");
        IcebergConnector c = new IcebergConnectorFactory().create("lake", Map.of(
                "tables.orders", "branch",
                "tables.first.path", "branch",
                "tables.first.snapshot_from_id", String.valueOf(branch.refs().get("t1").snapshotId()),
                "tables.b1.path", "branch/",
                "tables.b1.ref", "b1",
                "tables.then.path", tables.metadata("branch").toString(),
                "tables.then.snapshot_from_timestamp", java.time.Instant.ofEpochMilli(branch.snapshot(
                        branch.refs().get("t1").snapshotId()).timestampMillis()).toString(),
                "batchSize", "3",
                "readAhead", "64KB"), warehouse);
        assertEquals(List.of("b1", "first", "orders", "then"), c.catalog().tables(TableRef.MAIN));
        assertEquals(6, IcebergRows.read(c, TableRef.of("lake", "orders")).size());
        assertEquals(3, IcebergRows.read(c, TableRef.of("lake", "first")).size());
        assertEquals(8, IcebergRows.read(c, TableRef.of("lake", "b1")).size());
        assertEquals(3, IcebergRows.read(c, TableRef.of("lake", "then")).size());
    }

    @Test
    void aDirectoryOfTables() {
        IcebergConnector c = new IcebergConnectorFactory().create("lake", Map.of("directory", "db"),
                tables.warehouse());
        assertEquals(IcebergTestTables.NAMES.stream().sorted().toList(), c.catalog().tables(TableRef.MAIN));
        assertEquals(17, IcebergRows.read(c, TableRef.of("lake", "pos_v2")).size());
    }

    @Test
    void invalidOptions() {
        IcebergConnectorFactory f = new IcebergConnectorFactory();
        Path dir = tables.warehouse();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> f.create("lake", Map.of("table", "x"), dir))
                .getMessage().contains("table"));
        assertThrows(IllegalArgumentException.class, () -> f.create("lake", Map.of("tables.t.path", "x",
                "tables.t.color", "red"), dir));
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class, () -> f.create("lake",
                Map.of("tables.t.path", "x", "tables.t.ref", "main", "tables.t.snapshot_from_id", "1"), dir));
        assertTrue(two.getMessage().contains("more than one of"), two.getMessage());
        assertThrows(IllegalArgumentException.class, () -> f.create("lake", Map.of("tables.t.path", "x",
                "tables.t.snapshot_from_id", "one"), dir));
        assertThrows(IllegalArgumentException.class, () -> f.create("lake", Map.of("tables.t.path", "x",
                "tables.t.snapshot_from_timestamp", "yesterday"), dir));
        assertThrows(IllegalArgumentException.class, () -> f.create("lake", Map.of("batchSize", "0"), dir));
    }
}
