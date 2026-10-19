/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.exec.CodegenMode;
import net.fyrql.test.DuckDbReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The TPC-H queries of {@link TpchQueries} at scale factor 0.01, over
 * Parquet, CSV, JSON, a database and the memory connector: each gives the
 * rows of DuckDB over the same files, the same rows with and without
 * pushdown and on one thread, and a plan without cross products; with pushdown, the
 * database joins the customers and orders of Q3, Q5, Q7, Q8 and Q10 itself
 * and keeps the customers without orders of Q22. The semi join of Q18
 * filters the orders before they are joined with the customers. The scans
 * of {@code orders} and {@code customer} are read in ranges of their keys.
 * The files are
 * written into {@code target/tpch} once, or into the directory of the
 * system property {@code fyrql.tpch.dir}.
 */
class TpchTest {

    private static TpchEngine tpch;
    private static FyrQL engine;
    private static FyrQL off;
    private static FyrQL single;
    private static DuckDbReference duck;

    /** Returns the directory of the files of a scale factor. */
    static Path directory(double scaleFactor) {
        return Path.of(System.getProperty("fyrql.tpch.dir", "target/tpch")).resolve("sf" + scaleFactor);
    }

    @BeforeAll
    static void setUp() {
        tpch = TpchEngine.load(TpchEngine.SMALL, directory(TpchEngine.SMALL));
        engine = tpch.open(null, null);
        off = tpch.open(false, null);
        single = tpch.open(null, null, 1);
        duck = new DuckDbReference();
        tpch.views(duck);
    }

    @AfterAll
    static void tearDown() {
        if (duck != null) {
            duck.close();
        }
        if (off != null) {
            off.close();
        }
        if (single != null) {
            single.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    static List<TpchQueries.Query> queries() {
        return TpchQueries.ALL;
    }

    @ParameterizedTest
    @MethodSource("queries")
    void theQueryGivesTheRowsOfDuckDbWithAndWithoutPushdownAndOnOneThread(TpchQueries.Query query) {
        PlanNode plan = engine.plan(query.sql());
        String explain = engine.explain(plan);
        assertFalse(explain.contains("Join CROSS"), explain);
        if (Plans.ordered(plan)) {
            assertNotNull(TpchQueries.outputOrder(plan), "the order is not one of output columns:\n" + explain);
        }
        List<Object[]> actual = engine.rows(plan);
        assertFalse(actual.isEmpty(), query.name() + " gives no rows");
        Optional<String> modes = TpchQueries.compare(plan, off.rows(plan), actual);
        if (modes.isPresent()) {
            fail(query.name() + " with and without pushdown differs:\n" + modes.get() + "\n" + explain);
        }
        Optional<String> threads = TpchQueries.compare(plan, single.rows(plan), actual);
        if (threads.isPresent()) {
            fail(query.name() + " on one thread and on " + engine.threads() + " differs:\n" + threads.get() + "\n"
                    + explain);
        }
        Optional<String> oracle = TpchQueries.compare(plan, duck.query(query.sql(), plan.schema()), actual);
        if (oracle.isPresent()) {
            fail(query.name() + " differs from DuckDB:\n" + oracle.get() + "\n" + explain);
        }
    }

    @Test
    void theFiltersAndProjectionsOfQ1AndQ6RunAsCompiledPipelines() {
        for (String name : List.of("q01", "q06")) {
            TpchQueries.Query q = queries().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            // lineitem has about 60,000 rows, above the threshold of AUTO.
            String explain = engine.explain(q.sql());
            assertTrue(explain.contains("[codegen pipeline]"), explain);
        }
    }

    @Test
    void theJoinsOfQ9RunInPipelines() {
        TpchQueries.Query q9 = queries().stream().filter(q -> q.name().equals("q09")).findFirst().orElseThrow();
        String explain = engine.explain(q9.sql());
        // lineitem probes partsupp joined with part and with supplier joined with nation, then the orders. The
        // chains of partsupp and supplier read tables below the threshold of AUTO.
        List<Integer> expected = engine.codegen() == CodegenMode.ALWAYS ? List.of(2, 2, 1) : List.of(2);
        assertEquals(expected, probes(explain), explain);
    }

    /**
     * The joins in each pipeline of a plan as {@code explain} prints it:
     * from the top of a pipeline down the first input of each node, the
     * left input of a join, as long as the nodes are marked.
     */
    private static List<Integer> probes(String explain) {
        // The mark of a segment comes after the marks of the code.
        List<String> lines = explain.lines().map(l -> l.endsWith(" [parallel]")
                ? l.substring(0, l.length() - " [parallel]".length()) : l).toList();
        List<Integer> pipelines = new ArrayList<>();
        for (int top = 0; top < lines.size(); top++) {
            if (!lines.get(top).endsWith(" [codegen pipeline]")) {
                continue;
            }
            int probes = 0;
            int at = top;
            while (true) {
                if (lines.get(at).strip().startsWith("Join ")) {
                    probes++;
                }
                int depth = lines.get(at).indexOf(lines.get(at).strip());
                if (at + 1 == lines.size() || lines.get(at + 1).indexOf(lines.get(at + 1).strip()) != depth + 2
                        || !lines.get(at + 1).endsWith(" [codegen]")) {
                    break;
                }
                at++;
            }
            pipelines.add(probes);
        }
        return pipelines;
    }

    @Test
    void theSemiJoinOfQ18FiltersTheOrdersBeforeTheyAreJoined() {
        TpchQueries.Query q18 = queries().stream().filter(q -> q.name().equals("q18")).findFirst().orElseThrow();
        PlanNode.Join semi = Plans.nodes(engine.optimize(engine.plan(q18.sql()))).stream()
                .filter(n -> n instanceof PlanNode.Join j && j.type() == PlanNode.JoinType.SEMI)
                .map(PlanNode.Join.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("orders"), Plans.scans(semi.left()).stream().map(s -> s.table().table()).toList());
    }

    static List<TpchQueries.Query> joinsInTheDatabase() {
        return queries().stream().filter(q -> List.of("q03", "q05", "q07", "q08", "q10", "q22").contains(q.name()))
                .toList();
    }

    @ParameterizedTest
    @MethodSource("joinsInTheDatabase")
    void theDatabaseJoinsTheCustomersAndOrders(TpchQueries.Query query) {
        assumeTrue(engine.pushdown(), "pushdown is off");
        String explain = engine.explain(query.sql());
        assertTrue(explain.contains("Remote db ["), explain);
        JdbcConnector db = (JdbcConnector) engine.connector("db");
        db.clearStatements();
        engine.rows(query.sql());
        // Q22 keeps the customers without orders with NOT EXISTS and reads their average balance on its own, in parts.
        boolean q22 = query.name().equals("q22");
        String join = q22 ? " WHERE NOT EXISTS (" : " INNER JOIN ";
        List<String> scans = db.statements().stream().filter(s -> !s.contains(join)).toList();
        assertEquals(1, db.statements().size() - scans.size(), db.statements().toString());
        assertEquals(q22, !scans.isEmpty(), db.statements().toString());
        assertTrue(scans.stream().allMatch(s -> s.contains(".\"customer\" t")), db.statements().toString());
    }

    @Test
    void theOrdersAreReadInRangesOfTheirKey() {
        JdbcConnector db = (JdbcConnector) engine.connector("db");
        db.clearStatements();
        String q13 = TpchQueries.ALL.stream().filter(q -> q.name().equals("q13")).findFirst().orElseThrow().sql();
        engine.rows(q13);
        List<String> sent = db.statements();
        // 15,000 orders in parts of 1,000 rows, at most eight and no more than threads read them; on one thread,
        // one statement.
        int parts = Math.min(JdbcConnector.MAX_SPLITS, engine.threads());
        assertEquals(parts > 1, sent.stream().anyMatch(
                s -> s.startsWith("SELECT min(t.\"o_orderkey\"), max(t.\"o_orderkey\")")), sent.toString());
        assertEquals(parts > 1 ? parts : 0, sent.stream().filter(s -> s.contains(".\"orders\" t WHERE ")).count(),
                sent.toString());
        assertTrue(engine.threads() == 1 || engine.explain(q13).contains("[parallel]"));
    }
}
