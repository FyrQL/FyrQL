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

import net.fyrql.FyrQL;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.exec.Executor;
import net.fyrql.exec.QueryException;

import java.util.Map;

/**
 * Tells the budget errors that a query may end with from those it must not:
 * a join with keys spills its build side when it does not fit, so that it
 * fails only after spilling, when a partition whose rows all have one key
 * does not fit; a join without keys, and other operators, may fail at once.
 */
final class BudgetFailures {

    private BudgetFailures() {
    }

    /**
     * Tells whether an error is that a join with keys ran out of the budget without spilling. The error names
     * the operator by its place in the plan the engine ran, after optimizing the plan of the query.
     */
    static boolean unexpected(FyrQL engine, PlanNode plan, RuntimeException e) {
        if (!(e instanceof QueryException q) || q.getMessage() == null || !q.getMessage().startsWith("Memory budget")
                || q.getMessage().contains(" after spilling ") || q.operator() == null
                || !q.operator().startsWith("Join#")) {
            return false;
        }
        Map<PlanNode, Integer> ids = Plans.ids(engine.optimize(plan));
        for (PlanNode node : ids.keySet()) {
            if (node instanceof PlanNode.Join j && Executor.label(j, ids).equals(q.operator())) {
                return !j.leftKeys().isEmpty();
            }
        }
        return false;
    }
}
