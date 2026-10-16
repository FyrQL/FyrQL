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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random plans, each checked twice against DuckDB, which runs the plan
 * rendered as SQL: FyrQL runs the plan itself, which tests the operators
 * and expressions, and FyrQL runs the plan it parses and binds from the
 * rendered text, which tests the renderer, the parser and the binder
 * together. A difference the {@link Ledger} does not explain fails the
 * test; the report shows it shrunk to a small plan.
 */
class SqlRoundTripTest {

    /** Rounded transcendental results may still differ in the last bits where rounding leaves them alone. */
    static final int ULPS = 4;

    @Test
    void randomPlansAgreeWithDuckDb() {
        try (TwoEngines engines = new TwoEngines(RandomRun.SEED)) {
            RandomRun run = new RandomRun("Random plans", engines.duckVersion());
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                PlanNode plan = new PlanGenerator(RandomRun.random(i)).plan(3);
                boolean ordered = Plans.ordered(plan);
                Outcome direct = engines.plan(plan, ordered, ULPS);
                run.record(i, "plan", direct, () -> shrink(engines, run, plan, direct, false));
                Outcome bound = engines.sql(engines.render(plan), ordered, ULPS);
                run.record(i, "bound from SQL", bound, () -> shrink(engines, run, plan, bound, true));
            }
            System.out.println(run.report() + "  " + engines.codegen());
            assertTrue(run.unexplained().isEmpty(), run.report());
        }
    }

    private static Outcome check(TwoEngines engines, PlanNode plan, boolean bound) {
        boolean ordered = Plans.ordered(plan);
        return bound ? engines.sql(engines.render(plan), ordered, ULPS) : engines.plan(plan, ordered, ULPS);
    }

    private static String shrink(TwoEngines engines, RandomRun run, PlanNode plan, Outcome outcome, boolean bound) {
        PlanNode small = Minimizer.minimize(plan, p -> Minimizer.candidates(p, engines.engine()::rows), p -> {
            Outcome o = check(engines, p, bound);
            return o.signature().equals(outcome.signature()) && run.ledger().explain(o) == null;
        }, 400);
        Outcome o = check(engines, small, bound);
        return "shrunk: " + o.describe() + "\nsql: " + o.sql() + "\nplan:\n" + engines.engine().explain(small);
    }
}
