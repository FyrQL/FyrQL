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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.exec.CodegenMode;
import net.fyrql.test.DuckDbReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The times of the TPC-H queries of {@link TpchQueries} in FyrQL and in
 * DuckDB over the same sources: {@code lineitem} as Parquet,
 * {@code partsupp} as CSV, {@code part} and {@code supplier} as JSON lines,
 * {@code orders} and {@code customer} in the same PostgreSQL database, which
 * DuckDB reads through its extension {@code postgres}, and {@code nation}
 * and {@code region} in memory. FyrQL runs with pushdown and code
 * generation; both engines run on one thread and on as many as FyrQL takes
 * by default. A scan of {@code lineitem} runs first, as a reference for
 * the cost of reading the largest file.
 * <p>
 * Each run is timed from the text of the query to the last row as an
 * array of Java values, after a collection of the heap that is not timed.
 * Every query runs in each of the four engines once to warm up and then
 * {@link #RUNS} times in a row, so that no engine runs between the runs of
 * another; the engines take turns in the order FyrQL on one thread, DuckDB
 * on one thread, FyrQL on all threads, DuckDB on all threads, and in the
 * opposite order for every other query, so that a change of the clock
 * speed hits all four alike. {@code target/tpch-compare-report.md} has the
 * median and the fastest of the timed runs. The four engines must give the
 * same number of rows; that the rows are those of DuckDB checks
 * {@link TpchScale1Test}.
 * Only in the profile {@code tpch-compare}, which sets
 * {@code fyrql.tpch.compare} to the scale factor, 1 by default, and gives
 * the tests a heap of 8 GB:
 * <pre>
 * mvnw -o -B -pl fyrql-test -P tpch-compare verify -Dtest=TpchComparisonTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * {@code -Dfyrql.tpch.compare.sf=0.01} runs it at a small scale factor.
 * DuckDB installs its extension {@code postgres} from the network on first
 * use.
 */
@EnabledIfSystemProperty(named = "fyrql.tpch.compare", matches = "[0-9.]+")
class TpchComparisonTest {

    /** How often each query runs in each engine after the run to warm up, by default 5. */
    private static final int RUNS = Integer.getInteger("fyrql.tpch.compare.runs", 5);

    /** The memory each instance of DuckDB may take besides the heap, in the same process. */
    private static final String DUCKDB_MEMORY = "4GB";

    /**
     * The timed runs of a query in the four engines, in the order of the
     * columns of the report, and their number of rows; the error, if a run
     * failed or the numbers of rows differ.
     */
    private record Run(String query, long rows, long[][] millis, String error) {

        long median(int engine) {
            long[] sorted = millis[engine].clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        }

        long fastest(int engine) {
            return Arrays.stream(millis[engine]).min().orElse(0);
        }
    }

    @Test
    void fyrqlAndDuckDbReadTheSameSources() throws IOException {
        double scaleFactor = Double.parseDouble(System.getProperty("fyrql.tpch.compare"));
        long start = System.nanoTime();
        TpchEngine tpch = TpchEngine.load(scaleFactor, TpchTest.directory(scaleFactor));
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        List<TpchQueries.Query> queries = new ArrayList<>();
        queries.add(TpchQueries.SCAN);
        queries.addAll(TpchQueries.ALL);
        List<Run> runs = new ArrayList<>();
        int threads;
        String duckVersion;
        try (FyrQL fyrqlOnOne = tpch.open(true, CodegenMode.AUTO, 1);
                FyrQL fyrql = tpch.open(true, CodegenMode.AUTO);
                DuckDbReference duckOnOne = new DuckDbReference();
                DuckDbReference duck = new DuckDbReference()) {
            threads = fyrql.threads();
            duckOnOne.execute("SET threads = 1");
            duck.execute("SET threads = " + threads);
            for (DuckDbReference d : List.of(duckOnOne, duck)) {
                d.execute("SET memory_limit = '" + DUCKDB_MEMORY + "'");
                tpch.postgresViews(d);
            }
            duckVersion = String.valueOf(rows(duck, "SELECT version()").get(0)[0]);
            List<Function<String, List<Object[]>>> engines = List.of(fyrqlOnOne::rows, sql -> rows(duckOnOne, sql),
                    fyrql::rows, sql -> rows(duck, sql));
            for (int q = 0; q < queries.size(); q++) {
                runs.add(run(queries.get(q), engines, q % 2 == 1));
            }
        }
        Path report = Path.of("target", "tpch-compare-report.md");
        Files.writeString(report, report(runs, scaleFactor, threads, duckVersion, loadMillis));
        List<String> failures = runs.stream().filter(r -> r.error() != null).map(r -> r.query() + ": " + r.error())
                .toList();
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\nsee " + report.toAbsolutePath());
    }

    /**
     * Runs a query in each engine once to warm up and then {@link #RUNS}
     * times in a row, the engines in their order or in the opposite one.
     */
    private static Run run(TpchQueries.Query query, List<Function<String, List<Object[]>>> engines,
            boolean reversed) {
        long[][] millis = new long[engines.size()][RUNS];
        long rows = -1;
        for (int i = 0; i < engines.size(); i++) {
            int e = reversed ? engines.size() - 1 - i : i;
            for (int r = -1; r < RUNS; r++) {
                System.gc();
                long start = System.nanoTime();
                List<Object[]> result;
                try {
                    result = engines.get(e).apply(query.sql());
                } catch (RuntimeException | OutOfMemoryError x) {
                    return new Run(query.name(), rows, millis, "the engine of column " + (e + 1) + " fails: " + x);
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                if (rows < 0) {
                    rows = result.size();
                } else if (rows != result.size()) {
                    return new Run(query.name(), rows, millis, "the engine of column " + (e + 1) + " returns "
                            + result.size() + " rows, another " + rows);
                }
                if (r >= 0) {
                    millis[e][r] = elapsed;
                }
            }
        }
        return new Run(query.name(), rows, millis, null);
    }

    /** Runs a query in DuckDB and returns its rows as the objects of its driver. */
    private static List<Object[]> rows(DuckDbReference duck, String sql) {
        List<Object[]> rows = new ArrayList<>();
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int c = 0; c < n; c++) {
                    row[c] = rs.getObject(c + 1);
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB fails: " + sql, e);
        }
        return rows;
    }

    private static String report(List<Run> runs, double scaleFactor, int threads, String duckVersion,
            long loadMillis) {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder b = new StringBuilder("# TPC-H over the same sources in FyrQL and DuckDB\n\n");
        b.append(String.format(Locale.ROOT, "Scale factor %s; Java %s, max. heap %d MB, %d processors; DuckDB %s"
                + " with a memory limit of %s; loading the database took %.1f s (the files are written once).%n%n",
                scaleFactor, System.getProperty("java.version"), runtime.maxMemory() >> 20,
                runtime.availableProcessors(), duckVersion, DUCKDB_MEMORY, loadMillis / 1000.0));
        b.append("Both engines read `lineitem` as Parquet, `partsupp` as CSV, `part` and `supplier` as JSON lines, ")
                .append("`orders` and `customer` from the same PostgreSQL database, DuckDB through its extension ")
                .append("`postgres`, and `nation` and `region` from memory. FyrQL runs with pushdown and code ")
                .append("generation. Each run is timed from the text of the query to the last row; every query ")
                .append("runs in each engine once to warm up and then ").append(RUNS).append(" times in a row, the ")
                .append("engines in the order of the columns and in the opposite order for every other query. The ")
                .append("times are the medians, the fastest run in parentheses. The first row ")
                .append("is a scan of `lineitem` as a reference (`").append(TpchQueries.SCAN.sql())
                .append("`), not part of the sums.\n\n");
        String[] columns = {"FyrQL, 1 thread", "DuckDB, 1 thread", "FyrQL, " + threads + " threads",
                "DuckDB, " + threads + " threads"};
        b.append("| Query | Rows | ").append(String.join(" (s) | ", columns)).append(" (s) | Result |\n");
        b.append("| --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
        long[] sums = new long[columns.length];
        for (Run r : runs) {
            b.append("| ").append(r.query()).append(" | ").append(r.rows());
            for (int e = 0; e < columns.length; e++) {
                if (r.error() == null) {
                    b.append(String.format(Locale.ROOT, " | %.2f (%.2f)", r.median(e) / 1000.0,
                            r.fastest(e) / 1000.0));
                    if (!r.query().equals(TpchQueries.SCAN.name())) {
                        sums[e] += r.median(e);
                    }
                } else {
                    b.append(" | -");
                }
            }
            b.append(" | ").append(r.error() == null ? "same number of rows" : r.error()).append(" |\n");
        }
        b.append(String.format(Locale.ROOT, "%nThe medians of the queries, summed: FyrQL %.1f s and DuckDB %.1f s on"
                + " one thread (%.1f times as long); FyrQL %.1f s and DuckDB %.1f s on %d threads (%.1f times as"
                + " long).%n", sums[0] / 1000.0, sums[1] / 1000.0, ratio(sums[0], sums[1]), sums[2] / 1000.0,
                sums[3] / 1000.0, threads, ratio(sums[2], sums[3])));
        return b.toString();
    }

    private static double ratio(long fyrql, long duck) {
        return fyrql / (double) Math.max(1, duck);
    }
}
