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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.exec.CodegenMode;
import net.fyrql.test.DuckDbReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TPC-H queries of {@link TpchQueries} at scale factor 1 with pushdown,
 * each once in the interpreter ({@code OFF}) and once with code generation
 * ({@code AUTO}), compared with DuckDB over the same files; the database
 * joins the customers and orders of Q3, Q5, Q7, Q8 and Q10, and keeps the
 * customers without orders of Q22. A scan of {@code lineitem} with two
 * aggregates runs first, as a reference for the cost of reading the largest
 * file. The times and the peaks of the heap of every query go into
 * {@code target/tpch-report.md}, with the plans of the engine with code
 * generation. Only in the profile {@code tpch}, which sets
 * {@code fyrql.tpch.sf} to 1 and gives the tests a heap of 8 GB:
 * <pre>
 * mvnw -o -B -pl fyrql-test -P tpch verify -Dtest=TpchScale1Test -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The files take about 1 GB in {@code target/tpch} and are written once.
 */
@EnabledIfSystemProperty(named = "fyrql.tpch.sf", matches = "1")
class TpchScale1Test {

    private static final double SCALE_FACTOR = 1;

    /** The memory DuckDB may take besides the heap, in the same process. */
    private static final String DUCKDB_MEMORY = "2GB";

    /** The reference: one scan of {@code lineitem} that reads two columns of every row. */
    private static final TpchQueries.Query SCAN = new TpchQueries.Query("scan",
            "SELECT sum(l_quantity), max(l_shipdate) FROM lineitem");

    /** One run of a query in one engine; the rows are {@code null} if it failed. */
    private record Timing(List<Object[]> rows, long millis, long peakHeap, String error) {

        long rowCount() {
            return rows == null ? -1 : rows.size();
        }

        /** This run with the difference from DuckDB as its error, if there is one. */
        Timing compared(PlanNode plan, List<Object[]> expected) {
            if (error != null) {
                return this;
            }
            return TpchQueries.compare(plan, expected, rows)
                    .map(d -> new Timing(rows, millis, peakHeap, "differs from DuckDB:\n" + d)).orElse(this);
        }
    }

    /** The outcome of one query in both engines. */
    private record Run(String query, Timing interpreted, Timing compiled, long duckMillis) {

        String error() {
            return interpreted.error() != null ? "interpreter: " + interpreted.error()
                    : compiled.error() != null ? "compiled: " + compiled.error() : null;
        }
    }

    @Test
    void theQueriesRunInBothModesAndGiveTheRowsOfDuckDb() throws IOException {
        long start = System.nanoTime();
        TpchEngine tpch = TpchEngine.load(SCALE_FACTOR, TpchTest.directory(SCALE_FACTOR));
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        List<TpchQueries.Query> queries = new ArrayList<>();
        queries.add(SCAN);
        queries.addAll(TpchQueries.ALL);
        List<Run> runs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        StringBuilder plans = new StringBuilder();
        try (FyrQL interpreter = tpch.open(true, CodegenMode.OFF);
                FyrQL compiler = tpch.open(true, CodegenMode.AUTO);
                DuckDbReference duck = new DuckDbReference()) {
            duck.execute("SET memory_limit = '" + DUCKDB_MEMORY + "'");
            tpch.views(duck);
            for (TpchQueries.Query query : queries) {
                PlanNode plan = interpreter.plan(query.sql());
                PlanNode compiledPlan = compiler.plan(query.sql());
                String explain = compiler.explain(compiledPlan);
                plans.append("### ").append(query.name()).append("\n\n```\n").append(explain).append("```\n\n");
                if (TpchTest.joinsInTheDatabase().contains(query) && !explain.contains("Remote db [")) {
                    failures.add(query.name() + ": the database does not join the customers and the orders");
                }
                Timing interpreted = time(interpreter, plan);
                Timing compiled = time(compiler, compiledPlan);
                start = System.nanoTime();
                List<Object[]> expected = duck.query(query.sql(), plan.schema());
                long duckMillis = (System.nanoTime() - start) / 1_000_000;
                runs.add(new Run(query.name(), interpreted.compared(plan, expected),
                        compiled.compared(compiledPlan, expected), duckMillis));
            }
        }
        Path report = Path.of("target", "tpch-report.md");
        Files.writeString(report, report(runs, loadMillis, plans.toString()));
        runs.stream().filter(r -> r.error() != null).forEach(r -> failures.add(r.query() + ": " + r.error()));
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\nsee " + report.toAbsolutePath());
    }

    private static Timing time(FyrQL engine, PlanNode plan) {
        System.gc();
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP).toList();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long start = System.nanoTime();
        try {
            List<Object[]> rows = engine.rows(plan);
            return new Timing(rows, (System.nanoTime() - start) / 1_000_000, peak(heap), null);
        } catch (RuntimeException | OutOfMemoryError e) {
            return new Timing(null, (System.nanoTime() - start) / 1_000_000, peak(heap), e.toString());
        }
    }

    /** The sum of the peaks of the heap pools since their reset. */
    private static long peak(List<MemoryPoolMXBean> heap) {
        return heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
    }

    private static String report(List<Run> runs, long loadMillis, String plans) {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder b = new StringBuilder("# TPC-H at scale factor 1\n\n");
        b.append(String.format(Locale.ROOT, "Java %s, max. heap %d MB, %d processors; loading the database took %.1f s"
                + " (the files are written once).%n%n", System.getProperty("java.version"),
                runtime.maxMemory() >> 20, runtime.availableProcessors(), loadMillis / 1000.0));
        b.append("FyrQL runs with pushdown, once in the interpreter (`OFF`) and once with code generation (`AUTO`); ")
                .append("the peak is the sum of the peaks of the heap pools, garbage included. The first row is a ")
                .append("scan of `lineitem` as a reference (`").append(SCAN.sql())
                .append("`), not part of the sums.\n\n");
        b.append("| Query | Rows | Interpreter (s) | Peak heap (MB) | Compiled (s) | Peak heap (MB) | DuckDB (s) "
                + "| Result |\n");
        b.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        long interpreted = 0;
        long compiled = 0;
        for (Run r : runs) {
            if (!r.query().equals(SCAN.name())) {
                interpreted += r.interpreted().millis();
                compiled += r.compiled().millis();
            }
            String error = r.error();
            b.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %d | %.1f | %d | %.1f | %s |%n", r.query(),
                    Math.max(r.interpreted().rowCount(), r.compiled().rowCount()),
                    r.interpreted().millis() / 1000.0, r.interpreted().peakHeap() >> 20,
                    r.compiled().millis() / 1000.0, r.compiled().peakHeap() >> 20, r.duckMillis() / 1000.0,
                    error == null ? "same as DuckDB" : error.lines().findFirst().orElse("")));
        }
        b.append(String.format(Locale.ROOT, "%nAll queries of FyrQL: %.1f s in the interpreter, %.1f s with code "
                + "generation.%n%n## Plans with code generation%n%n", interpreted / 1000.0, compiled / 1000.0));
        return b.append(plans).toString();
    }
}
