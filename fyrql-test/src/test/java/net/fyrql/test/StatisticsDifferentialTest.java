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
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standard deviations and variances against DuckDB over a table of many
 * splits, which several threads aggregate: the doubles agree within the
 * tolerance of {@link ResultComparison#tolerance}, as the threads combine
 * the moments of their rows in another order than one thread adds them.
 */
class StatisticsDifferentialTest {

    private static final Schema DATA = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("g", DataType.INTEGER), new Schema.Field("i", DataType.BIGINT),
            new Schema.Field("m", DataType.decimal(12, 3)), new Schema.Field("x", DataType.DOUBLE),
            new Schema.Field("f", DataType.FLOAT));

    private static final List<String> QUERIES = List.of(
            "SELECT g, stddev(i), stddev_pop(m), var_samp(x), var_pop(f), variance(i) FILTER (WHERE id % 2 = 0)"
                    + " FROM data GROUP BY g",
            "SELECT stddev(i), var_pop(x), stddev_pop(f), variance(m), var_samp(id) FROM data",
            "SELECT g, var_samp(DISTINCT i % 1000), stddev_pop(DISTINCT f) FROM data GROUP BY g",
            "SELECT g % 7 AS h, stddev(x), var_pop(i) FROM data WHERE id % 1000 = 0 GROUP BY h",
            "SELECT id, stddev(x) OVER (PARTITION BY g ORDER BY id ROWS BETWEEN 20 PRECEDING AND 20 FOLLOWING),"
                    + " var_pop(i) OVER (PARTITION BY g ORDER BY id), variance(m) OVER (PARTITION BY g)"
                    + " FROM data WHERE g < 20 ORDER BY id",
            "SELECT g, corr(x, i), covar_pop(m, f), covar_samp(x, id), regr_slope(i, x), regr_intercept(f, m),"
                    + " regr_r2(x, f), regr_sxy(i, m), regr_avgx(x, i) FILTER (WHERE id % 3 = 0) FROM data GROUP BY g",
            // Keys without ties, as DuckDB takes either of equal keys on several threads.
            "SELECT corr(x, f), regr_count(i, x)::BIGINT, regr_syy(m, x), regr_sxx(id, f), arg_min(id, x),"
                    + " arg_max(i, x), min_by(m, id) FROM data",
            "SELECT g, arg_min(x, id), arg_max(m, x), regr_slope(DISTINCT x, i % 100) FROM data GROUP BY g",
            "SELECT id, corr(x, i) OVER (PARTITION BY g ORDER BY id ROWS BETWEEN 20 PRECEDING AND 20 FOLLOWING),"
                    + " arg_max(id, x) OVER (PARTITION BY g ORDER BY id) FROM data WHERE g < 20 ORDER BY id");

    private static FyrQL engine() {
        SplittableRandom random = new SplittableRandom(20261026);
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < 100_000; r++) {
            rows.add(new Object[] {r, random.nextInt(500),
                    random.nextInt(10) == 0 ? null : random.nextLong(-1_000_000_000L, 1_000_000_000L),
                    BigDecimal.valueOf(random.nextLong(-10_000_000, 10_000_000), 3),
                    random.nextInt(10) == 0 ? null : 1000 + random.nextDouble() * 10,
                    (float) random.nextGaussian()});
        }
        MemoryConnector memory = MemoryConnector.builder("mem").table("data", DATA, rows).splitRows(4096).build();
        return FyrQL.builder().register(memory).threads(8).build();
    }

    @Test
    void severalThreadsAgreeWithDuckDb() {
        try (Differential differential = new Differential(engine())) {
            FyrQL engine = differential.engine();
            assertTrue(engine.explain(QUERIES.get(0)).contains("[parallel]"), engine.explain(QUERIES.get(0)));
            for (String sql : QUERIES) {
                PlanNode plan = engine.plan(sql);
                assertTrue(ResultComparison.tolerance(plan) > 0, sql);
                assertFalse(differential.check(plan, 4).isEmpty(), sql);
            }
        }
    }

    /** Quantiles merge the values of the workers and give exactly what DuckDB gives. */
    @Test
    void quantilesOnSeveralThreadsAgreeWithDuckDb() {
        List<String> queries = List.of(
                "SELECT g, median(x), quantile_cont(i, [0.1, 0.9]), quantile_disc(m, 0.25),"
                        + " percentile_cont(0.75) WITHIN GROUP (ORDER BY f DESC) FROM data GROUP BY g",
                "SELECT median(i), median(m), quantile_disc(x, 0.5), quantile_cont(f, -0.3), mode(g % 7) FROM data",
                "SELECT g % 10 AS h, quantile_disc(id, [0.5, 0.99]), median(DISTINCT i % 1000) FROM data GROUP BY h",
                "SELECT id, median(x) OVER (PARTITION BY g ORDER BY id ROWS BETWEEN 20 PRECEDING AND 20 FOLLOWING),"
                        + " quantile_disc(i, 0.9) OVER (PARTITION BY g) FROM data WHERE g < 20 ORDER BY id");
        try (Differential differential = new Differential(engine())) {
            FyrQL engine = differential.engine();
            assertTrue(engine.explain(queries.get(0)).contains("[parallel]"), engine.explain(queries.get(0)));
            for (String sql : queries) {
                assertFalse(differential.check(engine.plan(sql)).isEmpty(), sql);
            }
        }
    }

    /** Equal values deviate by exactly nothing however threads split them, so that comparisons do not flip. */
    @Test
    void equalValuesGiveExactlyZeroOnSeveralThreads() {
        String select = "stddev(5.86), var_pop(CAST(0.1 AS DOUBLE)), stddev_pop(x * 0 + 2.5),"
                + " var_samp(CAST(1 AS FLOAT) / 3)";
        try (FyrQL engine = engine()) {
            for (String sql : List.of("SELECT " + select + " FROM data", "SELECT " + select + " FROM data GROUP BY g")) {
                assertTrue(engine.explain(sql).contains("[parallel]"), engine.explain(sql));
                for (int run = 0; run < 5; run++) {
                    for (Object[] row : engine.rows(sql)) {
                        assertEquals(List.of(0.0, 0.0, 0.0, 0.0), Arrays.asList(row), sql);
                    }
                }
            }
        }
    }
}
