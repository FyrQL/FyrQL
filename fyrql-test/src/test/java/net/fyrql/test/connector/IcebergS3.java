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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.apache.iceberg.Snapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStore;
import net.fyrql.connector.iceberg.IcebergConnector;
import net.fyrql.connector.iceberg.IcebergRows;
import net.fyrql.connector.iceberg.IcebergTestTables;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.test.Rows;

/**
 * Iceberg tables in a bucket, uploaded from where Iceberg Java wrote them,
 * so that every path in their metadata names the local place: the
 * connector reads them where they are now, with every kind of delete and
 * in every snapshot, through the table function and as the tables of a
 * source.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IcebergS3 {

    static final List<String> TABLES = List.of("part", "pos_v2", "eq_v2", "dv_v3", "evo", "v1", "codec_zstd",
            "branch");

    IcebergTestTables tables;
    S3Connector s3;
    IcebergConnector lake;
    FyrQL engine;

    /** Returns the source of S3, with the store the class tests. */
    abstract S3Connector connect();

    /** Returns the bucket the tables go to, which exists once {@link #connect()} is done. */
    abstract String bucket();

    /** Removes what the tests left, after the engine is closed. */
    void cleanUp() throws IOException {
    }

    String location(String table) {
        return "s3://" + bucket() + "/warehouse/" + table;
    }

    @BeforeAll
    void start() throws IOException {
        tables = IcebergTestTables.shared();
        s3 = connect();
        FileStore store = s3.fileStore().orElseThrow();
        for (String name : TABLES) {
            Path root = tables.location(name);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    StringBuilder key = new StringBuilder(location(name));
                    for (Path part : root.relativize(f)) {
                        key.append('/').append(part);
                    }
                    FileOutput out = store.create(key.toString());
                    try (OutputStream stream = out.stream()) {
                        stream.write(Files.readAllBytes(f));
                    }
                    out.commit();
                }
            }
        }
        lake = IcebergConnector.builder("lake").recordReads(true).table("pos", location("pos_v2"))
                .table("dv", location("dv_v3")).build();
        engine = FyrQL.builder().register(s3).register(lake).build();
    }

    @AfterAll
    void stop() throws IOException {
        try {
            cleanUp();
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    /** The rows of a query in the form of vectors, as {@link IcebergRows} gives the values of Iceberg Java. */
    private List<List<Object>> rows(String sql) {
        List<List<Object>> rows = new ArrayList<>();
        try (QueryResult result = engine.sql(sql)) {
            for (Object[] r : Rows.internal(result.schema(), result.rows())) {
                rows.add(Arrays.asList(r));
            }
        }
        return rows;
    }

    @Test
    void everyTableInEverySnapshotAsIcebergJava() {
        for (String name : TABLES) {
            assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name))),
                    IcebergRows.sorted(rows("SELECT * FROM iceberg_scan('" + location(name) + "')")), name);
            for (Snapshot s : tables.table(name).snapshots()) {
                assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name, s.snapshotId()))),
                        IcebergRows.sorted(rows("SELECT * FROM iceberg_scan('" + location(name)
                                + "', snapshot_from_id = " + s.snapshotId() + ")")), name + " " + s.snapshotId());
            }
        }
    }

    @Test
    void theTablesOfASourceInTheBucket() {
        assertEquals(List.of(List.of(17L)), rows("SELECT count(*) FROM lake.pos"));
        assertEquals(List.of(List.of(36L)), rows("SELECT CAST(sum(id) AS BIGINT) FROM lake.dv"));
        String metadata = location("dv_v3") + "/metadata/" + tables.metadata("dv_v3").getFileName();
        assertEquals(List.of(List.of(8L)), rows("SELECT count(*) FROM read_iceberg('" + metadata + "')"));
        assertEquals(List.of(List.of(9L)), rows("SELECT count(*) FROM iceberg_scan('" + location("part")
                + "') WHERE cat = 'a' AND id > 100"));
        lake.clearReads();
        assertEquals(List.of(List.of(1L)), rows("SELECT count(*) FROM lake.pos WHERE cat = 'a' AND id = 9"));
        if (S3RoundTrip.pushdown()) {
            assertEquals(1, lake.reads().size(), "only the file of the partition a: " + lake.reads());
            assertTrue(lake.reads().get(0).file().contains("/cat=a/"), lake.reads().toString());
        }
    }
}
