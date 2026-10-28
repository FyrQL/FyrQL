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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RowChanges;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scripts of {@code UPDATE} and {@code DELETE} over copies of the tables of
 * the corpus give the same counts and the same rows in FyrQL as in DuckDB,
 * after every statement: once as whole statements that the memory source
 * runs, and once by key, through a source that changes rows by key only. A
 * statement that DuckDB refuses, marked with a leading {@code !}, fails in
 * FyrQL too and changes nothing.
 */
class ChangesAgreeWithDuckDbTest {

    /** The copies: {@code tt} of {@code t}, {@code uu} of {@code u}, {@code kt} of {@code t} keyed by {@code a}. */
    private static final List<String> TABLES = List.of("tt", "uu", "kt");

    private static final List<String> SETUP = List.of(
            "CREATE TABLE tt AS SELECT * FROM t",
            "CREATE TABLE uu AS SELECT * FROM u",
            "CREATE TABLE kt (a INTEGER PRIMARY KEY, b VARCHAR, c DOUBLE, d DATE, e TINYINT, k INTEGER)",
            "INSERT INTO kt SELECT * FROM t");

    /** A memory source whose writer changes rows by key only. */
    private record KeyedOnly(MemoryConnector inner) implements Connector {
        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public Catalog catalog() {
            return inner.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return inner.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return inner.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return inner.open(split);
        }

        @Override
        public Optional<Writer> writer() {
            Writer w = inner.writer().orElseThrow();
            return Optional.of(new Writer() {
                @Override
                public Sink create(TableRef table, Schema schema) {
                    return w.create(table, schema);
                }

                @Override
                public Sink create(TableRef table, Schema schema, List<String> primaryKey) {
                    return w.create(table, schema, primaryKey);
                }

                @Override
                public Sink append(TableRef table) {
                    return w.append(table);
                }

                @Override
                public void drop(TableRef table) {
                    w.drop(table);
                }

                @Override
                public RowChanges change(TableRef table, int[] keys, int[] updated) {
                    return w.change(table, keys, updated);
                }
            });
        }
    }

    static Stream<List<String>> scripts() {
        return Stream.of(
                List.of("UPDATE tt SET c = c * 2 WHERE b = 'x'",
                        "UPDATE tt SET b = upper(b), e = e + 1 WHERE d >= DATE '2024-01-01'",
                        "UPDATE tt SET k = NULL WHERE k = 20",
                        "UPDATE tt SET b = 'n' WHERE b IS NULL",
                        "UPDATE tt SET a = a + 100",
                        "UPDATE tt SET c = NULL",
                        "UPDATE tt SET d = d + 1 WHERE d IS NOT NULL",
                        "!UPDATE tt SET e = 1000"),
                List.of("UPDATE tt SET b = uu.f FROM uu WHERE tt.a = uu.a AND uu.f <> 's'",
                        "UPDATE tt SET k = (SELECT max(n) FROM uu WHERE uu.a = tt.a)",
                        "UPDATE tt SET e = 0 WHERE a IN (SELECT a FROM uu WHERE n > 5)",
                        "UPDATE tt SET b = 'q' WHERE EXISTS (SELECT 1 FROM uu WHERE uu.a = tt.a AND uu.f = 'q')",
                        "UPDATE tt AS x SET c = x.a / 2 WHERE x.k > 10",
                        "UPDATE uu SET n = n + 1, f = f || f WHERE f = 'p'",
                        "UPDATE uu SET n = tt.a FROM tt WHERE uu.a = tt.a + 1"),
                List.of("UPDATE kt SET a = a + 10",
                        "!UPDATE kt SET a = 11 WHERE a = 12",
                        "!UPDATE kt SET a = NULL WHERE a = 12",
                        "UPDATE kt SET b = 'k' FROM uu WHERE kt.a = uu.a + 10",
                        "UPDATE kt SET a = a - 10 WHERE a > 13",
                        "UPDATE kt SET e = e * 2 WHERE e IS NOT NULL"),
                List.of("INSERT INTO tt SELECT * FROM tt",
                        "UPDATE tt SET c = 0 WHERE a = 1",
                        "DELETE FROM tt WHERE a < 3",
                        "DELETE FROM tt USING uu WHERE tt.a = uu.a",
                        "DELETE FROM kt USING uu WHERE kt.a = uu.a",
                        "DELETE FROM tt WHERE b = 'y'"),
                List.of("MERGE INTO kt USING (SELECT a, f AS b FROM uu WHERE a IS NOT NULL AND f <> 's') s"
                                + " ON kt.a = s.a WHEN MATCHED THEN UPDATE SET b = s.b"
                                + " WHEN NOT MATCHED THEN INSERT (a, b) VALUES (s.a + 100, s.b)",
                        "MERGE INTO kt USING (VALUES (1, 'x'), (2, 'y'), (7, 'n')) AS v(a, b) ON kt.a = v.a"
                                + " WHEN MATCHED AND v.b = 'x' THEN DELETE WHEN MATCHED THEN DO NOTHING"
                                + " WHEN NOT MATCHED BY SOURCE AND kt.a > 100 THEN UPDATE SET e = 9"
                                + " WHEN NOT MATCHED BY TARGET THEN INSERT VALUES (v.a, v.b, NULL, NULL, NULL, NULL)",
                        "MERGE INTO kt USING (SELECT 3 AS a) s ON kt.a = s.a WHEN NOT MATCHED BY SOURCE THEN DELETE",
                        "!MERGE INTO kt USING (SELECT NULL::INTEGER AS a) s ON kt.a = s.a"
                                + " WHEN NOT MATCHED THEN INSERT (a) VALUES (s.a)",
                        "MERGE INTO kt USING (SELECT 3 AS a, 'd' AS b) s ON kt.a = s.a"
                                + " WHEN MATCHED THEN UPDATE SET a = 3, b = s.b"),
                List.of("MERGE INTO tt USING uu ON tt.a = uu.a WHEN MATCHED AND uu.f = 'p' THEN DELETE"
                                + " WHEN MATCHED THEN UPDATE SET c = 0",
                        "MERGE INTO tt USING (SELECT 5 AS lim) s ON tt.a < s.lim"
                                + " WHEN MATCHED THEN UPDATE SET e = e - 1",
                        "MERGE INTO tt USING (SELECT 6 AS a, 'm' AS b) s USING (a) WHEN MATCHED THEN UPDATE SET b = s.b"
                                + " WHEN NOT MATCHED THEN INSERT BY NAME",
                        "MERGE INTO tt USING (SELECT 60 AS a, 'nn' AS b) s USING (a)"
                                + " WHEN NOT MATCHED THEN INSERT BY NAME",
                        "MERGE INTO uu USING (SELECT 9 AS a, 'z' AS f, 90 AS n) s ON uu.a = s.a"
                                + " WHEN NOT MATCHED THEN INSERT",
                        "MERGE INTO uu USING (SELECT 1 AS a, 'pp' AS f, 1000 AS n) s ON uu.a = s.a"
                                + " WHEN MATCHED THEN UPDATE",
                        "!MERGE INTO uu AS x USING (SELECT 1 AS a) s ON x.a = s.a WHEN MATCHED THEN UPDATE",
                        "!MERGE INTO uu USING (SELECT 4 AS a) s ON uu.a = s.a WHEN NOT MATCHED THEN INSERT VALUES (1)",
                        "MERGE INTO uu USING tt ON uu.a = tt.a WHEN NOT MATCHED BY SOURCE THEN DELETE"
                                + " WHEN NOT MATCHED THEN INSERT (a, f) VALUES (tt.a, tt.b)"),
                List.of("DELETE FROM tt WHERE c IS NULL",
                        "DELETE FROM tt WHERE a IN (SELECT a FROM uu)",
                        "DELETE FROM kt WHERE NOT EXISTS (SELECT 1 FROM uu WHERE uu.a = kt.a)",
                        "DELETE FROM uu WHERE a IS NULL",
                        "DELETE FROM uu AS x WHERE x.n > 6",
                        "DELETE FROM tt",
                        "DELETE FROM tt"));
    }

    @ParameterizedTest
    @MethodSource("scripts")
    void wholeStatements(List<String> script) {
        run(script, false);
    }

    @ParameterizedTest
    @MethodSource("scripts")
    void byKey(List<String> script) {
        run(script, true);
    }

    private static void run(List<String> script, boolean keyed) {
        MemoryConnector mem = MemoryConnector.builder("mem").build();
        try (DuckDbReference duck = new DuckDbReference();
                FyrQL engine = FyrQL.builder().register(CorpusTables.builder().build())
                        .register(keyed ? new KeyedOnly(mem) : mem).defaultSource("mem").build()) {
            CorpusTables.load(engine, duck);
            for (String sql : SETUP) {
                duck.execute(sql);
                // The tables of the corpus are in the source memory, the copies in the default source.
                engine.update(sql.replaceAll(" FROM ([tu])$", " FROM memory.$1"));
            }
            for (String line : script) {
                // A statement that DuckDB refuses is marked with a leading !.
                boolean refused = line.startsWith("!");
                String sql = refused ? line.substring(1) : line;
                Long expected = duckCount(duck, sql);
                assertEquals(refused, expected == null, "DuckDB " + (refused ? "runs: " : "refuses: ") + sql);
                if (expected == null) {
                    List<List<String>> before = snapshot(engine);
                    try {
                        engine.update(sql);
                        fail("DuckDB refuses, FyrQL runs: " + sql);
                    } catch (RuntimeException e) {
                        // As DuckDB does.
                    }
                    assertEquals(before, snapshot(engine), "a refused statement changed something: " + sql);
                } else {
                    assertEquals(expected.longValue(), engine.update(sql), sql);
                }
                for (String table : TABLES) {
                    PlanNode.Scan scan = engine.scan(TableRef.of("mem", table));
                    List<Object[]> theirs = duck.query("SELECT * FROM " + table, scan.schema());
                    ResultComparison.Outcome outcome = ResultComparison.compare(scan.schema(), theirs,
                            engine.rows(scan), false);
                    assertTrue(outcome.same(), "after " + sql + ", table " + table + ":\n" + outcome.report());
                }
            }
        }
    }

    /** Runs a statement in DuckDB; its count, or {@code null} if DuckDB refuses it. */
    private static Long duckCount(DuckDbReference duck, String sql) {
        try (Statement s = duck.connection().createStatement()) {
            return (long) s.executeUpdate(sql);
        } catch (SQLException e) {
            return null;
        }
    }

    private static List<List<String>> snapshot(FyrQL engine) {
        List<List<String>> all = new ArrayList<>();
        for (String table : TABLES) {
            PlanNode.Scan scan = engine.scan(TableRef.of("mem", table));
            List<String> rows = new ArrayList<>(ResultComparison.canonical(scan.schema(), engine.rows(scan)));
            rows.sort(null);
            all.add(rows);
        }
        return all;
    }
}
