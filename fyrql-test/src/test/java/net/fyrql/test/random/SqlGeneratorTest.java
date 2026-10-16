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
            System.out.println(run.report() + "  " + engines.codegen());
            assertTrue(run.unexplained().isEmpty(), run.report());
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
