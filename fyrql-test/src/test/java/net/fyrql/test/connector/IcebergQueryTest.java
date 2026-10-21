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
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.iceberg.Snapshot;
import org.junit.jupiter.api.Test;

import net.fyrql.FyrQL;
import net.fyrql.connector.iceberg.IcebergConnector;
import net.fyrql.connector.iceberg.IcebergTestTables;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;

/**
 * Queries over the Iceberg tables of the tests give the rows of DuckDB with
 * its extension {@code iceberg}: every type, every kind of delete, schema
 * evolution, time travel, filters, aggregations and joins with a table of
 * memory. Skipped when DuckDB has no extension {@code iceberg} in its
 * cache; DuckDB 1.5.6 crashes on manifests compressed with zstandard,
 * whose table is left out.
 */
class IcebergQueryTest {

    private static final List<String> TABLES = List.of("types", "part", "pos_v2", "eq_v2", "dv_v3", "evo", "v1",
            "codec_snappy", "codec_uncompressed", "branch");

    private static String scan(IcebergTestTables tables, String name) {
        return "iceberg_scan('" + tables.metadata(name).toString().replace('\\', '/') + "'";
    }

    @Test
    void queriesGiveTheRowsOfDuckDb() throws IOException {
        IcebergTestTables tables = IcebergTestTables.shared();
        List<String> queries = new ArrayList<>();
        for (String name : TABLES) {
            String t = scan(tables, name);
            queries.add("SELECT * FROM " + t + ")");
            queries.add("SELECT count(*) FROM " + t + ")");
            for (Snapshot s : tables.table(name).snapshots()) {
                queries.add("SELECT * FROM " + t + ", snapshot_from_id = " + s.snapshotId() + ")");
            }
        }
        String part = scan(tables, "part") + ")";
        queries.add("SELECT cat, count(*), sum(v), min(ts), max(name) FROM " + part + " GROUP BY cat");
        queries.add("SELECT * FROM " + part + " WHERE cat = 'a'");
        queries.add("SELECT id, ts FROM " + part + " WHERE ts >= TIMESTAMP '2024-01-03 00:00:00' ORDER BY id");
        queries.add("SELECT * FROM " + part + " WHERE id = 85");
        queries.add("SELECT * FROM " + part + " WHERE name IN ('beta1', 'gamma95') OR v > 58.5");
        queries.add("SELECT id, name FROM " + part + " ORDER BY id DESC LIMIT 7");
        queries.add("SELECT p.id, c.label FROM " + part + " p JOIN mem.cats c ON c.cat = p.cat WHERE p.id < 90"
                + " ORDER BY p.id");
        String evo = scan(tables, "evo") + ")";
        queries.add("SELECT a + 1, f * 2, c2, d, e FROM " + evo + " WHERE a > 1");
        queries.add("SELECT sum(a), max(d), count(e) FROM " + evo);
        String types = scan(tables, "types") + ")";
        queries.add("SELECT b, i, l, f, d, dec, dt, tm, ts, tz, s, u, fx, bin FROM " + types + " ORDER BY i");
        queries.add("SELECT li, m, st, st.y FROM " + types + " WHERE i IS NOT NULL ORDER BY i");
        queries.add("SELECT e.id, d.id FROM " + scan(tables, "eq_v2") + ") e LEFT JOIN " + scan(tables, "dv_v3")
                + ") d ON d.id = e.id ORDER BY e.id");
        List<String> failures = new ArrayList<>();
        MemoryConnector memory = MemoryConnector.builder("mem").table("cats", Schema.of(
                new Schema.Field("cat", DataType.VARCHAR), new Schema.Field("label", DataType.VARCHAR)),
                List.of(new Object[] {"a", "even"}, new Object[] {"b", "odd"})).build();
        try (DuckDbReference duckdb = new DuckDbReference(); FyrQL engine = FyrQL.builder().register(memory)
                .build()) {
            assumeTrue(loadIceberg(duckdb), "DuckDB has no extension iceberg in its cache");
            duckdb.execute("CREATE SCHEMA mem");
            duckdb.execute("CREATE TABLE mem.cats AS SELECT * FROM (VALUES ('a', 'even'), ('b', 'odd'))"
                    + " v(cat, label)");
            assertTrue(engine.sources().contains("iceberg"), engine.sources().toString());
            for (String sql : queries) {
                try {
                    PlanNode plan = engine.plan(sql);
                    Schema schema = plan.schema();
                    ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                            engine.rows(plan), Plans.ordered(plan));
                    if (!outcome.same()) {
                        failures.add(sql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + queries.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    @Test
    void aFilterOnThePartitionReadsOnlyItsFiles() throws IOException {
        IcebergTestTables tables = IcebergTestTables.shared();
        IcebergConnector ice = IcebergConnector.builder("lake").recordReads(true)
                .table("part", tables.location("part").toString()).build();
        try (FyrQL engine = FyrQL.builder().register(ice).build()) {
            List<Object[]> rows = engine.rows("SELECT count(*) FROM lake.part WHERE cat = 'a' AND id > 100");
            assertEquals(9L, rows.get(0)[0]);
            assertTrue(!ice.reads().isEmpty());
            // Without pushdown the scan gets no filters to leave files out by.
            assumeTrue(engine.pushdown(), "pushdown is off");
            for (IcebergConnector.Read r : ice.reads()) {
                assertTrue(r.file().replace('\\', '/').contains("/cat=a/"), r.file());
            }
            String explain = engine.explain("SELECT * FROM lake.part WHERE cat = 'a'");
            assertTrue(explain.contains("lake.main.part"), explain);
        }
    }

    /** Loads the extension iceberg of DuckDB from its cache, never from the network. */
    static boolean loadIceberg(DuckDbReference duckdb) {
        try {
            duckdb.execute("SET autoinstall_known_extensions = false");
            duckdb.execute("LOAD iceberg");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
