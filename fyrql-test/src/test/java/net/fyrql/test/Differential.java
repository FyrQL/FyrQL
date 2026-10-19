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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a plan in FyrQL and in DuckDB and compares the results. The tables
 * the plan scans are copied into DuckDB from what FyrQL reads, so that both
 * engines see the same data and only the operators and expressions are
 * under test.
 */
public final class Differential implements AutoCloseable {

    private final FyrQL engine;
    private final DuckDbReference duck = new DuckDbReference();
    private final Set<TableRef> loaded = new HashSet<>();

    public Differential(FyrQL engine) {
        this.engine = engine;
    }

    public FyrQL engine() {
        return engine;
    }

    public DuckDbReference duck() {
        return duck;
    }

    /** Copies the tables of a plan into DuckDB, once each. */
    public void loadTables(PlanNode plan) {
        for (PlanNode.Scan scan : Plans.scans(plan)) {
            if (loaded.add(scan.table())) {
                PlanNode.Scan all = engine.scan(scan.table());
                duck.load(scan.table(), all.schema(), engine.batches(all));
            }
        }
    }

    /**
     * Runs the plan in both engines and fails the test if the results
     * differ; the order is compared only if the plan is sorted at the top.
     *
     * @return the rows of FyrQL
     */
    public List<Object[]> check(PlanNode plan) {
        return check(plan, 0);
    }

    /**
     * As {@link #check(PlanNode)}, but floating-point values may differ by
     * the given number of units in the last place, for plans with
     * transcendental functions whose math libraries differ.
     */
    public List<Object[]> check(PlanNode plan, int ulps) {
        loadTables(plan);
        String sql = duck.render(plan);
        List<Object[]> expected = null;
        RuntimeException duckError = null;
        try {
            expected = duck.query(sql, plan.schema());
        } catch (RuntimeException e) {
            duckError = e;
        }
        List<Object[]> actual = null;
        RuntimeException fyrError = null;
        try {
            actual = engine.rows(plan);
        } catch (RuntimeException e) {
            fyrError = e;
        }
        String context = "plan:\n" + Plans.print(plan) + "sql: " + sql;
        if (duckError != null || fyrError != null) {
            // Both engines fail: an error is the agreed result, e.g. an overflow.
            if (duckError != null && fyrError != null) {
                return List.of();
            }
            fail((duckError != null ? "DuckDB fails, FyrQL does not: " + duckError.getCause()
                    : "FyrQL fails, DuckDB does not: " + fyrError) + "\n" + context);
        }
        ResultComparison.Outcome outcome = ResultComparison.compare(plan.schema(), expected, actual,
                Plans.ordered(plan), ulps);
        if (!outcome.same()) {
            fail("FyrQL differs from DuckDB:\n" + outcome.report() + context);
        }
        return actual;
    }

    @Override
    public void close() {
        duck.close();
        engine.close();
    }
}
