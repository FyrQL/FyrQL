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
import net.fyrql.exec.CodegenMode;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same results with and without pushdown: the corpus runs in an engine
 * whose memory source takes over every filter and limit and reads only the
 * fields of struct columns in use, and in one that negotiates nothing, and
 * both must give the rows of DuckDB.
 */
class PushdownTest {

    private static FyrQL on;
    private static FyrQL off;
    private static DuckDbReference duck;

    @BeforeAll
    static void setUp() {
        MemoryConnector.Builder tables = CorpusTables.builder()
                .pushdown(Capabilities.filtersAndLimit(e -> true).withFields(true));
        // A fixed number of threads, for the marks of the plans.
        on = FyrQL.builder().register(tables.build()).pushdown(true).threads(4).build();
        off = FyrQL.builder().register(tables.build()).pushdown(false).threads(4).build();
        duck = new DuckDbReference();
        CorpusTables.load(on, duck);
    }

    @AfterAll
    static void tearDown() {
        duck.close();
        off.close();
        on.close();
    }

    @Test
    void theCorpusGivesTheSameRowsWithAndWithoutPushdown() throws SQLException {
        List<String> failures = new ArrayList<>();
        List<String> queries = new ArrayList<>(SqlCorpusTest.QUERIES);
        queries.addAll(ParserMeaningTest.QUERIES);
        int pushed = 0;
        for (String sql : queries) {
            PlanNode plan = on.plan(sql);
            Schema schema = plan.schema();
            String explain = on.explain(plan);
            if (explain.contains("[pushed:")) {
                pushed++;
            }
            boolean ordered = Plans.ordered(plan);
            List<Object[]> withPushdown = on.rows(plan);
            ResultComparison.Outcome modes = ResultComparison.compare(schema, off.rows(plan), withPushdown, ordered);
            if (!modes.same()) {
                failures.add(sql + "\n  with and without pushdown differ:\n" + modes.report() + "\n" + explain);
                continue;
            }
            ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema), withPushdown,
                    ordered);
            if (!oracle.same()) {
                failures.add(sql + "\n" + oracle.report() + "\n" + explain);
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + queries.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
        assertTrue(pushed > 20, "queries with pushed parts: " + pushed);
        assertTrue(on.pushdown());
        assertTrue(!off.pushdown());
    }

    @Test
    void theExplainShowsWhatTheSourceTakesOver() {
        String explain = on.explain("SELECT b FROM t WHERE a > 2 AND k = 10 ORDER BY b LIMIT 3");
        assertEquals("""
                Limit 3
                  Sort #0
                    Project #1 AS b [parallel]
                      Scan memory.main.t (a INTEGER, b VARCHAR, k INTEGER) [pushed: WHERE ((#0 > CAST(2 AS INTEGER)) AND (#2 = CAST(10 AS INTEGER)))]
                """, explain);
        assertEquals("""
                Project #0 AS b, #1 AS k [parallel]
                  Scan memory.main.t (b VARCHAR, k INTEGER) [pushed: WHERE (#1 = CAST(10 AS INTEGER)) LIMIT 2]
                """, on.explain("SELECT b, k FROM t WHERE k = 10 LIMIT 2"));
        assertEquals("""
                Limit 2
                  Project #0 AS b [parallel]
                    Filter (#1 = CAST(10 AS INTEGER)) [codegen]
                      Scan memory.main.t (b VARCHAR, k INTEGER)
                """, off.explain("SELECT b FROM t WHERE k = 10 LIMIT 2"));
    }

    @Test
    void theSourceReadsOnlyTheFieldsOfAStructInUse() {
        String sql = "SELECT id, s.c.x FROM n WHERE s.a > 0 AND s.c.tags IS NOT NULL ORDER BY s.a DESC";
        assertEquals("""
                Project #0 AS id, #1 AS x
                  Sort #2 DESC
                    Project #0 AS id, #1 AS x, #2 AS "#order0" [parallel]
                      Scan memory.main.n (id INTEGER, s.c.x DOUBLE, s.a INTEGER, s.c.tags VARCHAR[]) [pushed: WHERE ((#2 > CAST(0 AS INTEGER)) AND (#3 IS NOT NULL))]
                """, on.explain(sql));
        assertEquals("""
                Project #0 AS id, #1 AS x
                  Sort #2 DESC
                    Project #0 AS id, struct_extract(struct_extract(#1, 'c'), 'x') AS x, struct_extract(#1, 'a') AS "#order0" [parallel]
                      Filter ((struct_extract(#1, 'a') > CAST(0 AS INTEGER)) AND (struct_extract(struct_extract(#1, 'c'), 'tags') IS NOT NULL))
                        Scan memory.main.n (id INTEGER, s STRUCT(a INTEGER, b VARCHAR, "name" VARCHAR, c STRUCT(x DOUBLE, tags VARCHAR[])))
                """, off.explain(sql));
    }

    @Test
    void theConditionsOfACommaJoinBecomeItsKeyAndFiltersOfTheTablesAndTheSmallerSideIsBuilt() {
        // The filter on k leaves fewer rows of t than the one on f leaves of u: t is the right input.
        String sql = "SELECT t.b, u.f FROM t, u WHERE t.a = u.a AND t.k = 10 AND u.f <> 'q'";
        // The small tables fuse the join and the nodes below it only when code is always generated.
        String pipeline = off.codegen() == CodegenMode.ALWAYS ? " [codegen pipeline]" : "";
        // The join is in the segment of the side it probes; the side it builds is a segment of its own.
        assertEquals("""
                Project #3 AS b, #1 AS f%s [parallel]
                  Join INNER ON l#0 = r#0 [codegen]
                    Scan memory.main.u (a INTEGER, f VARCHAR) [pushed: WHERE (#1 <> 'q')]
                    Scan memory.main.t (a INTEGER, b VARCHAR, k INTEGER) [pushed: WHERE (#2 = CAST(10 AS INTEGER))] [parallel]
                """.formatted(pipeline), on.explain(sql));
        assertEquals("""
                Project #3 AS b, #1 AS f%s [parallel]
                  Join INNER ON l#0 = r#0 [codegen]
                    Filter (#1 <> 'q') [codegen]
                      Scan memory.main.u (a INTEGER, f VARCHAR)
                    Filter (#2 = CAST(10 AS INTEGER)) [codegen] [parallel]
                      Scan memory.main.t (a INTEGER, b VARCHAR, k INTEGER)
                """.formatted(pipeline), off.explain(sql));
    }

    @Test
    void aSourceThatChangesItsMindWhenTheScanOpensIsRejected() {
        MemoryConnector memory = CorpusTables.builder().pushdown(Capabilities.filtersAndLimit(e -> true)).build();
        Connector fickle = new Connector() {
            private int calls;

            @Override
            public String name() {
                return memory.name();
            }

            @Override
            public Catalog catalog() {
                return memory.catalog();
            }

            @Override
            public ScanPlan plan(TableRef table, ScanRequest request) {
                // The first answer, to the optimizer, accepts the filters; the second, when the scan opens, does not.
                return ++calls % 2 == 1 ? memory.plan(table, request)
                        : ScanPlan.columnsOnly(table, memory.catalog().schema(table), request, request);
            }

            @Override
            public List<Split> splits(ScanPlan plan) {
                return memory.splits(plan);
            }

            @Override
            public BatchReader open(Split split) {
                return memory.open(split);
            }
        };
        try (FyrQL engine = FyrQL.builder().register(fickle).build()) {
            // The operator that opens the scan wraps the error of the engine.
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("SELECT a FROM t WHERE a > 2"));
            assertTrue(e.getMessage().contains("accepts 0 of 1 filters"), e.getMessage());
        }
    }
}
