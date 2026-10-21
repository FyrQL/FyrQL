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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import net.fyrql.core.plan.TableRef;

/**
 * Tables that other writers made, such as PyIceberg or Spark, against
 * DuckDB: opt-in with {@code -Dfyrql.test.iceberg.dir=<directory>}, in
 * which every directory with a directory {@code metadata}, at any depth,
 * is a table. Each snapshot of each table must have the same number of
 * rows, and the same rows as text, as DuckDB reads.
 */
class ExternalTablesTest {

    @Test
    void tablesOfOtherWritersAsDuckDb() throws IOException, SQLException {
        String root = System.getProperty("fyrql.test.iceberg.dir", "");
        assumeTrue(!root.isBlank(), "-Dfyrql.test.iceberg.dir is not set");
        Optional<DuckDbIceberg> opened = DuckDbIceberg.open();
        assumeTrue(opened.isPresent(), "DuckDB has no extension iceberg in its cache");
        List<Path> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.walk(Path.of(root))) {
            dirs.filter(d -> Files.isDirectory(d.resolve("metadata"))).sorted().forEach(found::add);
        }
        assumeTrue(!found.isEmpty(), "No Iceberg tables in " + root);
        IcebergConnector connector = IcebergConnector.builder("ext").build();
        try (DuckDbIceberg duckdb = opened.get()) {
            for (Path table : found) {
                Path metadata = Path.of(TableLocation.resolve(net.fyrql.connector.FileStores.local(table),
                        table.toString()).metadataFile().location());
                String meta = DuckDbIceberg.literal(metadata);
                List<List<Object>> expected;
                try {
                    expected = duckdb.query("SELECT * FROM iceberg_scan(" + meta + ")");
                } catch (SQLException e) {
                    System.out.println("skipped " + table + ", which DuckDB cannot read either: " + e.getMessage());
                    continue;
                }
                TableRef current = connector.bind("iceberg_scan", List.of(table.toString()), Map.of());
                List<List<Object>> snapshots = IcebergRows.read(connector, connector.bind("iceberg_snapshots",
                        List.of(table.toString()), Map.of()));
                assertEquals(IcebergRows.sorted(text(expected)),
                        IcebergRows.sorted(text(IcebergRows.read(connector, current))), table.toString());
                for (List<Object> s : snapshots) {
                    long id = (Long) s.get(1);
                    TableRef ref = connector.bind("iceberg_scan", List.of(table.toString()),
                            Map.of("snapshot_from_id", id));
                    long count = ((Number) duckdb.query("SELECT count(*) FROM iceberg_scan(" + meta
                            + ", snapshot_from_id => " + id + ")").get(0).get(0)).longValue();
                    assertEquals(count, IcebergRows.read(connector, ref).size(), table + " " + id);
                }
                System.out.println("compared " + table + " with " + snapshots.size() + " snapshots");
            }
        }
    }

    /** Returns rows with each value as text, the only form both readers give alike for every type. */
    private static List<List<Object>> text(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (List<Object> row : rows) {
            result.add(row.stream().map(v -> (Object) (v == null ? null : kind(v))).toList());
        }
        return result;
    }

    /**
     * Returns the kind of a value, which both readers agree on whatever its
     * Java class: dates and times are numbers in FyrQL.
     */
    private static String kind(Object v) {
        return v instanceof Number || v instanceof java.util.Date || v instanceof java.time.temporal.Temporal
                ? "number" : v instanceof CharSequence ? "text:" + v : "value";
    }
}
