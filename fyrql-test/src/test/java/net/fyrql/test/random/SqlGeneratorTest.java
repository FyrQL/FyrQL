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

package net.fyrql.test.random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random SQL text, run as it is in DuckDB and parsed, bound and run in
 * FyrQL: names, aliases, stars, joins, subqueries, grouping and the other
 * forms of the {@link SqlGenerator}. A query that fails in both engines
 * agrees; a difference the {@link Ledger} does not explain fails the test,
 * and the report shows it shrunk to a small query.
 */
class SqlGeneratorTest {

    @Test
    void randomSqlAgreesWithDuckDb() {
        try (TwoEngines engines = new TwoEngines(RandomRun.SEED)) {
            RandomRun run = new RandomRun("Random SQL", engines.duckVersion());
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                SqlGenerator.Generated query = new SqlGenerator(RandomRun.random(i), engines.engine()).generate();
                Outcome outcome = engines.sql(query.sql(), query.ordered(), SqlRoundTripTest.ULPS);
                run.record(i, "sql", outcome, () -> shrink(engines, run, query, outcome));
            }
            System.out.println(run.report() + "  " + engines.codegen() + "  " + engines.spill());
            assertTrue(run.unexplained().isEmpty(), run.report());
            assertTrue(engines.spillStatistics().parts() > 0, "no query spilled: " + engines.spill());
        }
    }

    private static String shrink(TwoEngines engines, RandomRun run, SqlGenerator.Generated query, Outcome outcome) {
        SqlGenerator.Generated small = Minimizer.minimize(query, QueryShrinker::candidates, q -> {
            Outcome o = engines.sql(q.sql(), q.ordered(), SqlRoundTripTest.ULPS);
            return o.signature().equals(outcome.signature()) && run.ledger().explain(o) == null;
        }, 400);
        Outcome o = engines.sql(small.sql(), small.ordered(), SqlRoundTripTest.ULPS);
        String plan;
        try {
            plan = engines.engine().explain(small.sql());
        } catch (RuntimeException e) {
            plan = e.getMessage() + "\n";
        }
        return "shrunk: " + o.describe() + "\nsql: " + o.sql() + "\nplan:\n" + plan;
    }
}
