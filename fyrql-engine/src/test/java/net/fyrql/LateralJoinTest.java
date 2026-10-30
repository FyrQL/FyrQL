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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a lateral join runs: as a join where it can, otherwise once per distinct left row. */
class LateralJoinTest {

    private static List<List<Object>> sorted(List<Object[]> rows) {
        return rows.stream().map(Arrays::asList).sorted((x, y) -> Arrays.toString(x.toArray())
                .compareTo(Arrays.toString(y.toArray()))).toList();
    }

    @Test
    void filtersOverTheLeftRowBecomeAHashJoin() {
        try (FyrQL engine = FyrQL.builder().build()) {
            String plan = engine.explain("SELECT a.i, b.j FROM range(100) a(i), LATERAL (SELECT j FROM range(50) b(j)"
                    + " WHERE b.j = a.i % 50) b");
            assertFalse(plan.contains("LateralJoin"), plan);
            assertTrue(plan.contains("Join INNER ON l#"), plan);
        }
    }

    @Test
    void theTopRowsOfEachGroupAreThoseOfAWindow() {
        String lateral = "SELECT a.i, b.j FROM range(2000) a(i), LATERAL (SELECT j FROM range(100) b(j)"
                + " WHERE b.j % 7 = a.i % 7 ORDER BY j DESC LIMIT 2) b";
        String window = "SELECT i, j FROM (SELECT a.i, b.j, row_number() OVER (PARTITION BY a.i ORDER BY b.j DESC) AS"
                + " n FROM range(2000) a(i), range(100) b(j) WHERE b.j % 7 = a.i % 7) WHERE n <= 2";
        try (FyrQL engine = FyrQL.builder().build()) {
            assertTrue(engine.explain(lateral).contains("LateralJoin INNER"), engine.explain(lateral));
            List<List<Object>> expected = sorted(engine.rows(window));
            assertEquals(4000, expected.size());
            assertEquals(expected, sorted(engine.rows(lateral)));
        }
    }

    @Test
    void explainAnalyzeCountsTheRowsOfAllRunsOfTheRightInput() {
        String sql = "SELECT a.i, b.c FROM range(10) a(i), LATERAL (SELECT count(*) AS c FROM range(a.i) r) b";
        try (FyrQL engine = FyrQL.builder().build()) {
            String analyzed = engine.explainAnalyze(sql);
            String join = analyzed.lines().filter(l -> l.contains("LateralJoin")).findFirst().orElseThrow();
            assertTrue(join.contains("-- 10 rows,"), analyzed);
            // The aggregate runs once per left row, ten times in all.
            String aggregate = analyzed.lines().filter(l -> l.contains("Aggregate")).findFirst().orElseThrow();
            assertTrue(aggregate.contains("-- 10 rows,"), analyzed);
            assertEquals(List.of(0L, 0L), Arrays.asList(engine.rows(sql + " ORDER BY a.i").get(0)));
        }
    }

    @Test
    void equalLeftRowsRunTheRightInputOnce() {
        String sql = "SELECT a.k, b.c FROM (SELECT i % 3 AS k FROM range(9) r(i)) a, LATERAL (SELECT count(*) AS c"
                + " FROM range(a.k) r) b";
        try (FyrQL engine = FyrQL.builder().build()) {
            String analyzed = engine.explainAnalyze(sql);
            String aggregate = analyzed.lines().filter(l -> l.contains("Aggregate")).findFirst().orElseThrow();
            assertTrue(aggregate.contains("-- 3 rows,"), analyzed);
            assertEquals(9, engine.rows(sql).size());
        }
    }
}
