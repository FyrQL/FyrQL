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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.apache.iceberg.DataFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;

/**
 * The test tables come out as planned, both references read them, and the
 * Parquet connector, with its renamed parquet-java, reads their data files
 * next to the original parquet-java of Iceberg Java.
 */
class IcebergSmokeTest {

    private static IcebergTestTables tables;

    @BeforeAll
    static void write() throws IOException {
        tables = IcebergTestTables.shared();
    }

    @Test
    void icebergJavaReadsEveryTable() {
        for (String name : IcebergTestTables.NAMES) {
            assertEquals(IcebergTestTables.ROWS.get(name), tables.rows(name).size(), name);
        }
    }

    @Test
    void deletesAreApplied() {
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), ids("dv_v3"));
        assertEquals(List.of(0L, 1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), ids("eq_v2"));
        assertEquals(List.of(0L, 2L, 4L, 5L, 6L, 7L, 8L, 9L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L),
                ids("pos_v2"));
    }

    @Test
    void tagsAndBranchesPointToOtherSnapshots() {
        Table branch = tables.table("branch");
        assertEquals(3, tables.rows("branch", branch.refs().get("t1").snapshotId()).size());
        assertEquals(8, tables.rows("branch", branch.refs().get("b1").snapshotId()).size());
        List<Long> evo = new ArrayList<>();
        tables.table("evo").snapshots().forEach(s -> evo.add(s.snapshotId()));
        assertEquals(3, evo.size());
        assertEquals(2, tables.rows("evo", evo.get(0)).size());
        assertEquals(List.of("a", "f", "c", "d", "b"), tables.rows("evo", evo.get(0)).get(0).struct().fields()
                .stream().map(f -> f.name()).toList());
    }

    @Test
    void manifestsUseTheirCodecs() throws IOException {
        Map<String, String> codecs = Map.of("codec_snappy", "snappy", "codec_zstd", "zstandard",
                "codec_uncompressed", "null", "pos_v2", "deflate");
        for (var entry : codecs.entrySet()) {
            Table table = tables.table(entry.getKey());
            for (ManifestFile manifest : table.currentSnapshot().allManifests(table.io())) {
                byte[] bytes = Files.readAllBytes(Path.of(manifest.path()));
                String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
                assertTrue(head.contains("avro.codec"), manifest.path());
                int at = head.indexOf("avro.codec");
                assertTrue(head.substring(at, at + 30).contains(entry.getValue()), entry.getKey());
            }
        }
    }

    @Test
    void theParquetConnectorReadsTheDataFiles() throws IOException {
        Table table = tables.table("types");
        try (CloseableIterable<org.apache.iceberg.FileScanTask> tasks = table.newScan().planFiles()) {
            for (var task : tasks) {
                DataFile file = task.file();
                ParquetConnector parquet = ParquetConnector.builder("p").path("t", Path.of(file.location()))
                        .build();
                TableRef ref = new TableRef("p", TableRef.MAIN, "t");
                assertEquals(OptionalLong.of(3), parquet.catalog().rowCount(ref));
                assertEquals(17, parquet.catalog().schema(ref).size());
            }
        }
        // The original parquet-java of Iceberg Java is on the class path as well.
        assertEquals("org.apache.parquet.hadoop.ParquetFileReader",
                org.apache.parquet.hadoop.ParquetFileReader.class.getName());
    }

    @Test
    void duckDbReadsTheTablesTheSame() throws SQLException {
        Optional<DuckDbIceberg> opened = DuckDbIceberg.open();
        assumeTrue(opened.isPresent(), "DuckDB has no extension iceberg in its cache");
        try (DuckDbIceberg duckdb = opened.get()) {
            for (String name : IcebergTestTables.NAMES) {
                if (name.equals("codec_zstd")) {
                    continue;
                }
                assertEquals((long) IcebergTestTables.ROWS.get(name), duckdb.count(tables.metadata(name)), name);
            }
        }
    }

    private static List<Long> ids(String name) {
        return tables.rows(name).stream().map(r -> (Long) r.getField("id")).sorted().toList();
    }
}
