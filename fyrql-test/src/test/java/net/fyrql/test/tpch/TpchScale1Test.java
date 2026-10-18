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
 * in the interpreter ({@code OFF}) and with code generation ({@code AUTO}),
 * each on one thread and on as many as an engine takes by default,
 * compared with DuckDB over the same files; the database joins the
 * customers and orders of Q3, Q5, Q7, Q8 and Q10, and keeps the customers
 * without orders of Q22. A scan of {@code lineitem} with two aggregates
 * runs first, as a reference for the cost of reading the largest file.
 * Every query runs {@link #RUNS} times in each engine and in DuckDB, one
 * after the other; the times in {@code target/tpch-report.md} are those of
 * the fastest run, so that the code a query generates is measured warm,
 * and the peaks of the heap the highest, with the plans of the engine with
 * code generation on all threads. Only in the profile {@code tpch}, which
 * sets {@code fyrql.tpch.sf} to 1 and gives the tests a heap of 8 GB:
 * <pre>
 * mvnw -o -B -pl fyrql-test -P tpch verify -Dtest=TpchScale1Test -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The files take about 1 GB in {@code target/tpch} and are written once.
 */
@EnabledIfSystemProperty(named = "fyrql.tpch.sf", matches = "1")
class TpchScale1Test {

    private static final double SCALE_FACTOR = 1;

    /** How often each query runs in each engine, by default 3. */
    private static final int RUNS = Integer.getInteger("fyrql.tpch.runs", 3);

    /** The memory DuckDB may take besides the heap, in the same process. */
    private static final String DUCKDB_MEMORY = "2GB";

    /** The reference: one scan of {@code lineitem} that reads two columns of every row. */
    private static final TpchQueries.Query SCAN = new TpchQueries.Query("scan",
            "SELECT sum(l_quantity), max(l_shipdate) FROM lineitem");

    /** The engines in the order of the columns of the report. */
    private static final String[] ENGINES = {"interpreter on one thread", "interpreter on all threads",
            "compiled on one thread", "compiled on all threads"};

    /**
     * The runs of a query in one engine: the rows of the first, the time of
     * the fastest, the highest peak of the heap, and the first error; the
     * rows are {@code null} if the first run failed.
     */
    private record Timing(List<Object[]> rows, long millis, long peakHeap, String error) {

        long rowCount() {
            return rows == null ? -1 : rows.size();
        }

        /** These runs and one more, whose rows must be those of the first. */
        Timing and(PlanNode plan, Timing next) {
            String error = next.error() != null ? next.error()
                    : TpchQueries.compare(plan, rows, next.rows()).map(d -> "a run differs from the first:\n" + d)
                            .orElse(null);
            return new Timing(rows, Math.min(millis, next.millis()), Math.max(peakHeap, next.peakHeap()), error);
        }

        /** These runs with the difference from DuckDB as their error, if there is one. */
        Timing compared(PlanNode plan, List<Object[]> expected) {
            if (error != null) {
                return this;
            }
            return TpchQueries.compare(plan, expected, rows)
                    .map(d -> new Timing(rows, millis, peakHeap, "differs from DuckDB:\n" + d)).orElse(this);
        }
    }

    /** The outcome of one query in the engines of {@link #ENGINES}, and the fastest run of DuckDB. */
    private record Run(String query, Timing[] timings, long duckMillis) {

        String error() {
            for (int e = 0; e < timings.length; e++) {
                if (timings[e].error() != null) {
                    return ENGINES[e] + ": " + timings[e].error();
                }
            }
            return null;
        }

        long rowCount() {
            long rows = -1;
            for (Timing t : timings) {
                rows = Math.max(rows, t.rowCount());
            }
            return rows;
        }
    }

    @Test
    void theQueriesRunInBothModesOnOneAndOnAllThreadsAndGiveTheRowsOfDuckDb() throws IOException {
        long start = System.nanoTime();
        TpchEngine tpch = TpchEngine.load(SCALE_FACTOR, TpchTest.directory(SCALE_FACTOR));
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        List<TpchQueries.Query> queries = new ArrayList<>();
        queries.add(SCAN);
        queries.addAll(TpchQueries.ALL);
        List<Run> runs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        StringBuilder plans = new StringBuilder();
        int threads;
        try (FyrQL interpreterOnOne = tpch.open(true, CodegenMode.OFF, 1);
                FyrQL interpreter = tpch.open(true, CodegenMode.OFF);
                FyrQL compilerOnOne = tpch.open(true, CodegenMode.AUTO, 1);
                FyrQL compiler = tpch.open(true, CodegenMode.AUTO);
                DuckDbReference duck = new DuckDbReference()) {
            threads = compiler.threads();
            FyrQL[] engines = {interpreterOnOne, interpreter, compilerOnOne, compiler};
            duck.execute("SET memory_limit = '" + DUCKDB_MEMORY + "'");
            tpch.views(duck);
            for (TpchQueries.Query query : queries) {
                PlanNode[] plan = new PlanNode[engines.length];
                for (int e = 0; e < engines.length; e++) {
                    plan[e] = engines[e].plan(query.sql());
                }
                String explain = compiler.explain(plan[3]);
                plans.append("### ").append(query.name()).append("\n\n```\n").append(explain).append("```\n\n");
                if (TpchTest.joinsInTheDatabase().contains(query) && !explain.contains("Remote db [")) {
                    failures.add(query.name() + ": the database does not join the customers and the orders");
                }
                Timing[] timings = new Timing[engines.length];
                List<Object[]> expected = null;
                long duckMillis = Long.MAX_VALUE;
                for (int r = 0; r < RUNS; r++) {
                    for (int e = 0; e < engines.length; e++) {
                        if (timings[e] == null) {
                            timings[e] = time(engines[e], plan[e]);
                        } else if (timings[e].error() == null) {
                            timings[e] = timings[e].and(plan[e], time(engines[e], plan[e]));
                        }
                    }
                    start = System.nanoTime();
                    List<Object[]> rows = duck.query(query.sql(), plan[0].schema());
                    duckMillis = Math.min(duckMillis, (System.nanoTime() - start) / 1_000_000);
                    expected = expected == null ? rows : expected;
                }
                for (int e = 0; e < engines.length; e++) {
                    timings[e] = timings[e].compared(plan[e], expected);
                }
                runs.add(new Run(query.name(), timings, duckMillis));
            }
        }
        Path report = Path.of("target", "tpch-report.md");
        Files.writeString(report, report(runs, threads, loadMillis, plans.toString()));
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

    private static String report(List<Run> runs, int threads, long loadMillis, String plans) {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder b = new StringBuilder("# TPC-H at scale factor 1\n\n");
        b.append(String.format(Locale.ROOT, "Java %s, max. heap %d MB, %d processors; loading the database took %.1f s"
                + " (the files are written once).%n%n", System.getProperty("java.version"),
                runtime.maxMemory() >> 20, runtime.availableProcessors(), loadMillis / 1000.0));
        b.append("FyrQL runs with pushdown, in the interpreter (`OFF`) and with code generation (`AUTO`), each on one ")
                .append("thread and on ").append(threads).append(". Every query runs ").append(RUNS)
                .append(" times in each engine and in DuckDB; the times are those of the fastest run, the speedup ")
                .append("is that of code generation on all threads over one, and the peaks of the heap with code ")
                .append("generation, on one thread and on all, are the highest of the runs, garbage included. The ")
                .append("first row is a scan of `lineitem` as a reference (`").append(SCAN.sql())
                .append("`), not part of the sums.\n\n");
        b.append("| Query | Rows | Interpreter, 1 thread (s) | Interpreter, ").append(threads)
                .append(" threads (s) | Compiled, 1 thread (s) | Compiled, ").append(threads)
                .append(" threads (s) | Speedup | Peak heap (MB) | DuckDB (s) | Result |\n");
        b.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        long[] sums = new long[ENGINES.length];
        long duck = 0;
        for (Run r : runs) {
            Timing[] t = r.timings();
            if (!r.query().equals(SCAN.name())) {
                for (int e = 0; e < sums.length; e++) {
                    sums[e] += t[e].millis();
                }
                duck += r.duckMillis();
            }
            String error = r.error();
            b.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %.2f | %.1f | %d / %d | %.2f | %s |%n",
                    r.query(), r.rowCount(), t[0].millis() / 1000.0, t[1].millis() / 1000.0,
                    t[2].millis() / 1000.0, t[3].millis() / 1000.0, speedup(t[2].millis(), t[3].millis()),
                    t[2].peakHeap() >> 20, t[3].peakHeap() >> 20, r.duckMillis() / 1000.0,
                    error == null ? "same as DuckDB" : error.lines().findFirst().orElse("")));
        }
        b.append(String.format(Locale.ROOT, "%nAll queries of FyrQL: %.1f s in the interpreter on one thread, %.1f s on"
                + " %d; %.1f s with code generation on one thread, %.1f s on %d (%.1f times as fast); DuckDB %.1f s."
                + "%n%n## Plans with code generation on all threads%n%n", sums[0] / 1000.0, sums[1] / 1000.0, threads,
                sums[2] / 1000.0, sums[3] / 1000.0, threads, speedup(sums[2], sums[3]), duck / 1000.0));
        return b.append(plans).toString();
    }

    private static double speedup(long one, long all) {
        return one / (double) Math.max(1, all);
    }
}
