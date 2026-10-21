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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;

/**
 * Every test table in every snapshot gives the rows Iceberg Java reads,
 * and the tables of simple types the rows DuckDB reads; time travel,
 * moved tables, what scans take over, and the errors.
 */
class IcebergConnectorTest {

    private static IcebergTestTables tables;
    private static IcebergConnector connector;

    @TempDir
    static Path dir;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
        connector = IcebergConnector.builder("ice").recordReads(true).batchSize(7).build();
    }

    private static TableRef scan(String location, Map<String, Object> named) {
        Map<String, Object> withZone = new HashMap<>(named);
        withZone.put("timezone", "UTC");
        return connector.bind("iceberg_scan", List.of(location), withZone);
    }

    private static TableRef scan(String name) {
        return scan(tables.location(name).toString(), Map.of());
    }

    @Test
    void everyTableInEverySnapshotAsIcebergJava() {
        int compared = 0;
        for (String name : IcebergTestTables.NAMES) {
            Table table = tables.table(name);
            assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name))),
                    IcebergRows.sorted(IcebergRows.read(connector, scan(name))), name);
            for (Snapshot s : table.snapshots()) {
                TableRef ref = scan(tables.location(name).toString(), Map.of("snapshot_from_id", s.snapshotId()));
                assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name, s.snapshotId()))),
                        IcebergRows.sorted(IcebergRows.read(connector, ref)), name + " " + s.snapshotId());
                compared++;
            }
        }
        assertEquals(24, compared);
    }

    @Test
    void theTypesOfTheColumns() {
        TableRef types = scan("types");
        assertEquals("(b BOOLEAN, i INTEGER, l BIGINT, f FLOAT, d DOUBLE, dec DECIMAL(10,2), dt DATE, tm TIME,"
                + " ts TIMESTAMP, tz TIMESTAMP WITH TIME ZONE, s VARCHAR, u UUID, fx BLOB, bin BLOB, li INTEGER[],"
                + " m MAP(VARCHAR, BIGINT), st STRUCT(x INTEGER, y VARCHAR))",
                columns(connector.catalog().schema(types)));
        TableRef evo = scan("evo");
        assertEquals("(a BIGINT, f DOUBLE, c2 VARCHAR, d DECIMAL(9,2), e VARCHAR)",
                columns(connector.catalog().schema(evo)));
        TableRef first = scan(tables.location("evo").toString(), Map.of("snapshot_from_id",
                tables.table("evo").snapshots().iterator().next().snapshotId()));
        assertEquals("(a INTEGER, f FLOAT, c VARCHAR, d DECIMAL(5,2), b VARCHAR)",
                columns(connector.catalog().schema(first)));
        assertEquals("(id BIGINT NOT NULL, cat VARCHAR, name VARCHAR)",
                columns(connector.catalog().schema(scan("pos_v2"))));
    }

    @Test
    void simpleTablesAsDuckDb() throws SQLException {
        Optional<DuckDbIceberg> opened = DuckDbIceberg.open();
        assumeTrue(opened.isPresent(), "DuckDB has no extension iceberg in its cache");
        try (DuckDbIceberg duckdb = opened.get()) {
            for (String name : IcebergTestTables.NAMES) {
                if (name.equals("types") || name.equals("codec_zstd")) {
                    continue;
                }
                String meta = DuckDbIceberg.literal(tables.metadata(name));
                assertEquals(IcebergRows.sorted(duck(duckdb.query("SELECT * FROM iceberg_scan(" + meta + ")"))),
                        IcebergRows.sorted(IcebergRows.read(connector, scan(name))), name);
                for (Snapshot s : tables.table(name).snapshots()) {
                    assertEquals(IcebergRows.sorted(duck(duckdb.query("SELECT * FROM iceberg_scan(" + meta
                            + ", snapshot_from_id => " + s.snapshotId() + ")"))),
                            IcebergRows.sorted(IcebergRows.read(connector, scan(tables.location(name).toString(),
                                    Map.of("snapshot_from_id", s.snapshotId())))), name + " " + s.snapshotId());
                }
            }
            List<List<Object>> snapshots = duckdb.query("SELECT sequence_number, snapshot_id, timestamp_ms,"
                    + " manifest_list FROM iceberg_snapshots(" + DuckDbIceberg.literal(tables.metadata("part")) + ")");
            assertEquals(IcebergRows.sorted(duck(snapshots)), IcebergRows.sorted(IcebergRows.read(connector,
                    connector.bind("iceberg_snapshots", List.of(tables.location("part").toString()), Map.of()))));
        }
    }

    /** Returns rows of DuckDB in the values of FyrQL. */
    private static List<List<Object>> duck(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (List<Object> row : rows) {
            result.add(row.stream().map(v -> switch (v) {
                case null -> null;
                case Timestamp t -> IcebergRows.fyrql(t.toLocalDateTime());
                case java.time.LocalDateTime t -> IcebergRows.fyrql(t);
                case java.math.BigInteger b -> b.longValueExact();
                default -> v;
            }).toList());
        }
        return result;
    }

    @Test
    void timeTravelByIdTimeAndRef() {
        String branch = tables.location("branch").toString();
        Table table = tables.table("branch");
        assertEquals(3, IcebergRows.read(connector, scan(branch, Map.of("ref", "t1"))).size());
        assertEquals(8, IcebergRows.read(connector, scan(branch, Map.of("ref", "b1"))).size());
        assertEquals(6, IcebergRows.read(connector, scan(branch, Map.of("ref", "main"))).size());
        Snapshot first = table.snapshot(table.refs().get("t1").snapshotId());
        long micros = first.timestampMillis() * 1000;
        assertEquals(3, IcebergRows.read(connector, scan(branch, Map.of("snapshot_from_timestamp", micros)))
                .size());
        String text = java.time.Instant.ofEpochMilli(first.timestampMillis()).atOffset(ZoneOffset.UTC)
                .toLocalDateTime().toString();
        assertEquals(3, IcebergRows.read(connector, scan(branch, Map.of("snapshot_from_timestamp", text))).size());
        assertEquals(3, IcebergRows.read(connector, scan(branch, Map.of("snapshot_from_timestamp", text + "+00:00")))
                .size());
        IllegalArgumentException early = assertThrows(IllegalArgumentException.class, () -> scan(branch,
                Map.of("snapshot_from_timestamp", micros - 1000)));
        assertTrue(early.getMessage().contains("has no snapshot at or before"), early.getMessage());
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class, () -> scan(branch,
                Map.of("ref", "t1", "snapshot_from_id", first.snapshotId())));
        assertTrue(both.getMessage().contains("Only one of"), both.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> scan(branch, Map.of("snapshot_from_id", 1L)))
                .getMessage().contains("has no snapshot 1"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> scan(branch, Map.of("ref", "x")))
                .getMessage().contains("has no branch or tag x"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> scan(branch,
                Map.of("version", 1L)));
        assertTrue(unknown.getMessage().contains("Invalid named parameter \"version\" for function iceberg_scan"),
                unknown.getMessage());
    }

    @Test
    void theSameCallGivesTheSameTable() {
        assertEquals(scan("eq_v2"), scan("eq_v2"));
        assertEquals(scan("eq_v2"), connector.bind("read_iceberg", List.of(tables.metadata("eq_v2").toString()),
                Map.of()));
        assertFalse(scan("eq_v2").equals(scan("dv_v3")));
    }

    @Test
    void movedTablesWithEveryKindOfDelete() throws IOException {
        for (String name : List.of("pos_v2", "eq_v2", "dv_v3", "part", "v1")) {
            Path copy = tables.copy(name, dir.resolve("moved-" + name));
            assertEquals(IcebergRows.sorted(IcebergRows.of(tables.rows(name))),
                    IcebergRows.sorted(IcebergRows.read(connector, scan(copy.toString(), Map.of()))), name);
        }
    }

    @Test
    void limitsAndCountsOnlyWithoutDeletes() {
        TableRef plain = scan("codec_snappy");
        ScanRequest count = new ScanRequest(List.of(), List.of(), OptionalLong.empty(), List.of(),
                Optional.of(new PlanNode.Aggregation(List.of(), List.of(new PlanNode.AggregateCall(
                        PlanNode.AggregateFunction.COUNT_STAR, -1, false, "count_star()", null, List.of())))));
        ScanPlan counted = connector.plan(plain, count);
        assertTrue(counted.aggregationAccepted());
        assertEquals(List.of(List.of(3L)), IcebergRows.read(connector, plain, count));
        assertEquals(OptionalLong.of(3), connector.catalog().rowCount(plain));
        TableRef deleted = scan("dv_v3");
        assertFalse(connector.plan(deleted, count).aggregationAccepted());
        assertEquals(OptionalLong.empty(), connector.catalog().rowCount(deleted));
        ScanRequest limit = new ScanRequest(ColumnPath.whole(List.of(0)), List.of(), OptionalLong.of(2), List.of(),
                Optional.empty());
        assertTrue(connector.plan(plain, limit).limitAccepted());
        assertEquals(2, IcebergRows.read(connector, plain, limit).size());
        assertFalse(connector.plan(deleted, limit).limitAccepted());
    }

    @Test
    void columnsInAnyOrderFieldsAndFiltersAsHints() {
        TableRef types = scan("types");
        ScanRequest some = new ScanRequest(List.of(new ColumnPath(16, List.of("y")), new ColumnPath(1, List.of()),
                new ColumnPath(10, List.of())), List.of(), OptionalLong.empty(), List.of(), Optional.empty());
        assertEquals(IcebergRows.sorted(List.of(java.util.Arrays.asList("y", 1, "a"),
                java.util.Arrays.asList(null, null, null), java.util.Arrays.asList("z", -2_000_000_000,
                        "\u00E4\u00F6\u00FC \u20AC"))), IcebergRows.sorted(IcebergRows.read(connector, types, some)));
        TableRef part = scan("part");
        Expression filter = new Expression.Comparison(Expression.ComparisonOp.GE,
                new Expression.Column(0, DataType.BIGINT), new Expression.Literal(DataType.BIGINT, 100L));
        ScanRequest filtered = new ScanRequest(ColumnPath.whole(List.of(0)), List.of(filter), OptionalLong.empty(),
                List.of(), Optional.empty());
        ScanPlan plan = connector.plan(part, filtered);
        assertEquals(List.of(), plan.acceptedFilters());
        assertTrue(IcebergRows.read(connector, part, filtered).size() >= 20);
    }

    @Test
    void readsAreRecorded() {
        connector.clearReads();
        assertEquals(9, IcebergRows.read(connector, scan("eq_v2")).size());
        List<IcebergConnector.Read> reads = connector.reads();
        assertEquals(2, reads.size());
        assertEquals(11, reads.stream().mapToLong(IcebergConnector.Read::rows).sum());
        assertEquals(List.of("id", "cat", "name"), reads.get(0).columns());
    }

    @Test
    void registeredTablesAndTheirSnapshots() {
        Table branch = tables.table("branch");
        IcebergConnector c = IcebergConnector.builder("lake")
                .table("orders", tables.location("branch").toString())
                .table("first", tables.metadata("branch").toString(), branch.refs().get("t1").snapshotId())
                .tableAtRef("b1", tables.location("branch").toString(), "b1")
                .tableAsOf("then", tables.location("branch").toString(),
                        java.time.Instant.ofEpochMilli(branch.snapshot(branch.refs().get("t1").snapshotId())
                                .timestampMillis()))
                .build();
        assertEquals(List.of("orders", "first", "b1", "then"), c.catalog().tables(TableRef.MAIN));
        assertEquals(6, IcebergRows.read(c, TableRef.of("lake", "orders")).size());
        assertEquals(3, IcebergRows.read(c, TableRef.of("lake", "first")).size());
        assertEquals(8, IcebergRows.read(c, TableRef.of("lake", "b1")).size());
        assertEquals(3, IcebergRows.read(c, TableRef.of("lake", "then")).size());
        assertThrows(IllegalArgumentException.class, () -> c.catalog().schema(TableRef.of("lake", "none")));
        IcebergConnector broken = IcebergConnector.builder("lake").table("t", dir.resolve("nothing").toString())
                .build();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> broken.catalog().schema(TableRef.of("lake", "t")));
        assertTrue(missing.getMessage().startsWith("The table t of the Iceberg source lake: No Iceberg table at "),
                missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> IcebergConnector.builder("x").table("#1", "y"));
    }

    @Test
    void theArgumentsOfTheTableFunctions() {
        assertEquals(List.of("iceberg_scan", "read_iceberg", "iceberg_snapshots"), connector.tableFunctions());
        assertThrows(IllegalArgumentException.class, () -> connector.bind("iceberg_scan", List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> connector.bind("iceberg_scan", List.of(1L), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> connector.bind("iceberg_snapshots",
                List.of(tables.location("part").toString()), Map.of("ref", "main")));
        assertThrows(IllegalArgumentException.class, () -> connector.bind("parquet_scan", List.of("x"), Map.of()));
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> scan(dir.resolve("none")
                .toString(), Map.of()));
        assertTrue(none.getMessage().startsWith("No Iceberg table at "), none.getMessage());
        TableRef history = connector.bind("iceberg_snapshots", List.of(tables.location("evo").toString()), Map.of());
        List<List<Object>> rows = IcebergRows.read(connector, history);
        assertEquals(3, rows.size());
        assertEquals(List.of(1L, 2L, 3L), rows.stream().map(r -> r.get(0)).toList());
    }

    private static String columns(net.fyrql.core.type.Schema s) {
        List<String> columns = new ArrayList<>();
        for (net.fyrql.core.type.Schema.Field f : s.fields()) {
            columns.add(f.name() + " " + f.type().sqlName() + (f.nullable() ? "" : " NOT NULL"));
        }
        return "(" + String.join(", ", columns) + ")";
    }
}
