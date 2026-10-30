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

import net.fyrql.exec.QueryCancelledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code WITH RECURSIVE} over many rounds, over rounds that do not fit in the budget, and without end. */
class RecursiveCommonTableTest {

    @TempDir
    Path temp;

    @Test
    void aHundredThousandRoundsRun() {
        try (FyrQL engine = FyrQL.builder().build()) {
            List<Object[]> rows = engine.rows("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r"
                    + " WHERE n < 100000) SELECT count(*), sum(n) FROM r");
            assertEquals(List.of(100_000L, BigInteger.valueOf(5_000_050_000L)), Arrays.asList(rows.get(0)));
        }
    }

    @Test
    void roundsThatDoNotFitInTheBudgetSpill() throws Exception {
        // Rounds of 100,000 rows, each read by the next round, in a budget of 1 MiB.
        String sql = "WITH RECURSIVE r(n, s) AS (SELECT range, 'row ' || range FROM range(100000) UNION ALL"
                + " SELECT n + 100000, s || '+' FROM r WHERE n < 300000) SELECT count(*), sum(n), max(length(s))"
                + " FROM r";
        List<Object> expected = List.of(400_000L, BigInteger.valueOf(79_999_800_000L), 12L);
        Path spill = temp.resolve("spill");
        try (FyrQL engine = FyrQL.builder().memoryBudget(1 << 20).spillDirectory(spill).build()) {
            assertEquals(expected, Arrays.asList(engine.rows(sql).get(0)));
            assertTrue(Files.isDirectory(spill), "the rounds went to disk");
            try (Stream<Path> files = Files.walk(spill)) {
                assertEquals(List.of(), files.filter(Files::isRegularFile).toList(),
                        "the files of the rounds are deleted");
            }
        }
        try (FyrQL engine = FyrQL.builder().memoryBudget(1 << 20).spill(false).build()) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows(sql));
            assertTrue(e.getMessage().startsWith("Memory budget of 1048576 bytes exceeded"), e.getMessage());
        }
    }

    @Test
    void theRowsSeenByUnionTakeTheBudget() {
        try (FyrQL engine = FyrQL.builder().memoryBudget(1 << 20).spill(false).build()) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("WITH RECURSIVE r(n) AS"
                    + " (SELECT 1 UNION SELECT n + 1 FROM r WHERE n < 100000) SELECT count(*) FROM r"));
            assertTrue(e.getMessage().startsWith("Memory budget of 1048576 bytes exceeded"), e.getMessage());
        }
    }

    @Test
    void explainShowsTheRecursionAndAnalyzeCountsTheRowsOfAllRounds() {
        String sql = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 10) SELECT * FROM r";
        try (FyrQL engine = FyrQL.builder().build()) {
            String plan = engine.explain(sql);
            assertTrue(plan.contains("RecursiveUnion r #") && plan.contains(" ALL"), plan);
            assertTrue(plan.contains("WorkingTableScan r #") && plan.contains("(n INTEGER)"), plan);
            String analyzed = engine.explainAnalyze(sql);
            String recursion = analyzed.lines().filter(l -> l.contains("RecursiveUnion")).findFirst().orElseThrow();
            assertTrue(recursion.contains("-- 10 rows,"), analyzed);
            String scan = analyzed.lines().filter(l -> l.contains("WorkingTableScan")).findFirst().orElseThrow();
            // Each of the ten rounds reads the one row of the round before.
            assertTrue(scan.contains("-- 10 rows,"), analyzed);
        }
    }

    @Test
    void aRecursionWithoutEndEndsWithTheTimeout() {
        try (FyrQL engine = FyrQL.builder().queryTimeout(Duration.ofMillis(300)).build()) {
            assertThrows(QueryCancelledException.class, () -> engine.rows("WITH RECURSIVE r(n) AS (SELECT 1"
                    + " UNION ALL SELECT count(*) FROM r) SELECT count(*) FROM r"));
            assertEquals(5, engine.rows("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r)"
                    + " SELECT * FROM r LIMIT 5").size(), "a limit ends a recursion without end");
        }
    }
}
