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

package net.fyrql;

import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryStatistics;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.BoundStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code EXPLAIN ANALYZE}, {@code EXPLAIN} as a statement, and what a result tells about its query. */
class ExplainAnalyzeTest {

    private static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.BIGINT),
            new Schema.Field("k", DataType.INTEGER), new Schema.Field("s", DataType.VARCHAR));
    private static final String QUERY = "SELECT k, count(*) AS c FROM t WHERE n % 2 = 0 GROUP BY k ORDER BY k";

    @TempDir
    Path temp;

    private static List<Object[]> numbers(int rows) {
        List<Object[]> list = new ArrayList<>();
        for (int n = 0; n < rows; n++) {
            list.add(new Object[] {(long) n, n % 7, "row " + n});
        }
        return list;
    }

    private static FyrQL.Builder builder(int threads) {
        return FyrQL.builder().threads(threads).register(MemoryConnector.builder("mem")
                .table("t", NUMBERS, numbers(10_000)).splitRows(1000).batchSize(256).build());
    }

    /** The lines of a text, without what comes after {@code --}. */
    private static List<String> plan(String text) {
        return Arrays.stream(text.split("\n")).map(l -> l.replaceAll(" -- .*", "")).toList();
    }

    private static String line(String text, String start) {
        return Arrays.stream(text.split("\n")).filter(l -> l.strip().startsWith(start)).findFirst()
                .orElseThrow(() -> new AssertionError("No line " + start + " in\n" + text));
    }

    @Test
    void eachNodeOfThePlanShowsWhatItsOperatorDid() {
        for (int threads : new int[] {1, 4}) {
            try (FyrQL engine = builder(threads).build()) {
                String text = engine.explainAnalyze(QUERY);
                List<String> lines = List.of(text.split("\n"));
                String expected = "Analyzed in [0-9.]+ (ns|us|ms|s): 7 rows, \\d+ threads?";
                assertTrue(lines.get(0).matches(expected), lines.get(0));
                assertEquals(plan(engine.explain(QUERY)), plan(text).subList(1, lines.size()),
                        "the plan is that of explain");
                for (String l : lines.subList(1, lines.size())) {
                    assertTrue(l.matches(".* -- \\d+ rows?, \\d+ batch(es)?, [0-9.]+ (ns|us|ms|s), \\d+ threads?.*"),
                            l);
                }
                assertTrue(line(text, "Sort").contains(" -- 7 rows, 1 batch, "), text);
                String scan = line(text, "Scan");
                assertTrue(scan.matches(".* -- \\d+ rows, \\d+ batches, .*; \\d+ splits?"), scan);
                if (threads > 1) {
                    assertTrue(text.contains("[parallel] -- "), text);
                }
            }
        }
    }

    @Test
    void explainIsAStatementWithAColumnPlan() {
        try (FyrQL engine = builder(1).build()) {
            try (QueryResult result = engine.sql("EXPLAIN " + QUERY)) {
                assertEquals(BoundStatement.PLAN, result.schema());
                assertEquals(List.of(engine.explain(QUERY).split("\n")),
                        result.rows().stream().map(r -> (String) r[0]).toList());
            }
            List<Object[]> analyzed = engine.rows("explain analyze " + QUERY);
            assertTrue(((String) analyzed.get(0)[0]).startsWith("Analyzed in "), (String) analyzed.get(0)[0]);
            assertEquals("EXPLAIN returns rows, not a number of rows written; run it with sql",
                    assertThrows(SqlException.class, () -> engine.update("EXPLAIN ANALYZE " + QUERY)).reason());
            assertEquals("Only queries are supported, not EXPLAIN",
                    assertThrows(SqlException.class, () -> engine.plan("EXPLAIN " + QUERY)).reason());
            assertEquals(engine.explain(QUERY), engine.explain("EXPLAIN ANALYZE " + QUERY));
        }
    }

    @Test
    void explainAnalyzeOfAStatementThatWritesWrites() {
        try (FyrQL engine = builder(2).register(MemoryConnector.builder("out").build()).build()) {
            engine.update("CREATE TABLE out.u (n BIGINT, k INTEGER)");
            String insert = "INSERT INTO out.u SELECT n, k FROM t WHERE k = 3";
            List<Object[]> explained = engine.rows("EXPLAIN " + insert);
            assertEquals("Insert into out.main.u", explained.get(0)[0]);
            assertEquals(0L, engine.rows("SELECT count(*) FROM out.u").get(0)[0], "EXPLAIN writes nothing");

            String text = engine.explainAnalyze(insert);
            List<String> lines = List.of(text.split("\n"));
            assertTrue(lines.get(0).matches("Analyzed in .*: 1429 rows written, \\d+ threads?"), lines.get(0));
            assertEquals("Insert into out.main.u", lines.get(1));
            assertTrue(lines.get(2).startsWith("  ") && lines.get(2).contains(" -- 1429 rows"), text);
            assertEquals(1429L, engine.rows("SELECT count(*) FROM out.u").get(0)[0]);

            List<Object[]> rows = engine.rows("EXPLAIN ANALYZE INSERT INTO out.u VALUES (1, 2)");
            assertTrue(((String) rows.get(0)[0]).contains(": 1 row written"), (String) rows.get(0)[0]);
            assertEquals(1430L, engine.rows("SELECT count(*) FROM out.u").get(0)[0]);

            text = engine.explainAnalyze("DROP TABLE out.u");
            assertTrue(text.matches("Analyzed in .*: 0 rows written, 0 threads\nDrop table out.main.u\n"), text);
        }
    }

    @Test
    void aResultTellsWhatItsQueryDid() {
        try (FyrQL engine = builder(4).build()) {
            QueryStatistics statistics;
            long rows;
            try (QueryResult result = engine.sql(QUERY)) {
                rows = result.rows().size();
                statistics = result.statistics();
                assertNotNull(statistics.plan());
            }
            assertEquals(7, rows);
            String text = engine.explain(statistics);
            assertTrue(text.startsWith("Sort ") && text.contains(" -- 7 rows, 1 batch, "), text);
            assertEquals(plan(engine.explain(QUERY)), plan(text));
            assertTrue(statistics.threads() >= 1);
            assertTrue(statistics.elapsed().toNanos() > 0);
        }
    }

    @Test
    void theTimeoutOfTheContextEndsTheRun() {
        try (FyrQL engine = builder(2).build()) {
            BoundStatement slow = engine.statement("SELECT count(*) FROM t a, t b, t c WHERE a.n + b.n = c.n");
            QueryCancelledException e = assertThrows(QueryCancelledException.class,
                    () -> engine.explainAnalyze(slow, QueryContext.withTimeout(Duration.ofMillis(100))));
            assertEquals(QueryContext.Reason.TIMEOUT, e.reason());
        }
    }

    @Test
    void anOperatorThatSpillsShowsWhatItSpilled() {
        try (FyrQL engine = builder(2).memoryBudget(64 * 1024).spillDirectory(temp).build()) {
            String text = engine.explainAnalyze("SELECT n, s FROM t ORDER BY s DESC");
            assertTrue(text.split("\n")[0].matches(".*, spilled [0-9.]+ (bytes|KB|MB) in \\d+ parts?"), text);
            assertTrue(line(text, "Sort").matches(".*; spilled [0-9.]+ (bytes|KB|MB) in \\d+ parts?, read back .*"),
                    text);
        }
    }

    @Test
    void aPreparedExplainTakesTheParametersOfItsStatement() {
        try (FyrQL engine = builder(1).build()) {
            PreparedQuery prepared = engine.prepare("EXPLAIN ANALYZE SELECT s FROM t WHERE n = ?");
            assertEquals(BoundStatement.PLAN, prepared.schema());
            assertEquals(List.of(DataType.BIGINT), prepared.parameterTypes());
            assertFalse(prepared.writes());
            List<Object[]> rows = engine.rows(prepared, List.of(42L));
            assertTrue(((String) rows.get(0)[0]).matches("Analyzed in .*: 1 row, 1 thread"), (String) rows.get(0)[0]);
        }
    }
}
