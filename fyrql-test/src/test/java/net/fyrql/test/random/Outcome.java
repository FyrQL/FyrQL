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

import net.fyrql.core.plan.PlanNode;

/**
 * The outcome of one query in both engines.
 *
 * @param kind whether and how the engines differ
 * @param sql the text DuckDB ran
 * @param fyrqlError FyrQL's error, {@code null} if it ran the query
 * @param duckError DuckDB's error, {@code null} if it ran the query
 * @param report the differing types or rows, empty if there are none
 * @param unoptimized for a query that fails in one engine only or whose
 *        rows differ, how the results relate with the optimizer of DuckDB
 *        switched off; {@code null} otherwise
 * @param bound for a plan whose results differ, how they relate when
 *        FyrQL binds the plan's SQL instead of running the plan itself;
 *        {@code null} otherwise
 * @param plan the plan FyrQL ran, {@code null} if it did not get one
 * @param optimized the plan after FyrQL's optimizer, whose nodes an error
 *        of FyrQL names; {@code null} if there is none
 */
record Outcome(Kind kind, String sql, String fyrqlError, String duckError, String report, Kind unoptimized,
        Kind bound, PlanNode plan, PlanNode optimized) {

    Outcome(Kind kind, String sql, String fyrqlError, String duckError, String report) {
        this(kind, sql, fyrqlError, duckError, report, null, null, null, null);
    }

    /** The same outcome, with the relation of the results without DuckDB's optimizer. */
    Outcome withUnoptimized(Kind kind) {
        return new Outcome(this.kind, sql, fyrqlError, duckError, report, kind, bound, plan, optimized);
    }

    /** The same outcome, with the relation of the results when FyrQL binds the SQL. */
    Outcome withBound(Kind kind) {
        return new Outcome(this.kind, sql, fyrqlError, duckError, report, unoptimized, kind, plan, optimized);
    }

    /** The same outcome, with the plan FyrQL ran and the plan after its optimizer. */
    Outcome withPlan(PlanNode fyrqlPlan, PlanNode optimizedPlan) {
        return new Outcome(kind, sql, fyrqlError, duckError, report, unoptimized, bound, fyrqlPlan, optimizedPlan);
    }

    /** How the results of the two engines relate. */
    enum Kind {
        /** Same types and rows. */
        SAME,
        /** Both fail; an error is the agreed result, e.g. an overflow. */
        BOTH_FAIL,
        /** FyrQL fails, DuckDB runs the query. */
        FYRQL_FAILS,
        /** FyrQL fails with an internal error, such as a {@code NullPointerException}, whatever DuckDB does. */
        FYRQL_CRASHES,
        /** DuckDB fails, FyrQL runs the query. */
        DUCKDB_FAILS,
        /** The types of the columns differ. */
        TYPES_DIFFER,
        /** The rows differ. */
        ROWS_DIFFER,
        /**
         * FyrQL's interpreter and its compiled code disagree, whatever DuckDB
         * does; never a known difference.
         */
        MODES_DIFFER,
        /**
         * FyrQL on one thread and on several threads disagree, whatever
         * DuckDB does; never a known difference.
         */
        THREADS_DIFFER,
        /**
         * FyrQL in memory and FyrQL with a small budget, spilling to disk,
         * disagree, whatever DuckDB does; never a known difference.
         */
        SPILL_DIFFERS;

        /** Tells whether the engines disagree. */
        boolean differs() {
            return this != SAME && this != BOTH_FAIL;
        }
    }

    /**
     * The kind and, for a failure in one engine, the start of its message
     * without positions and numbers: a smaller query shows the same
     * difference if it has the same signature.
     */
    String signature() {
        String message = switch (kind) {
            case FYRQL_FAILS, FYRQL_CRASHES -> fyrqlError;
            case DUCKDB_FAILS -> duckError;
            default -> "";
        };
        String plain = message.replaceAll(" at line \\d+, column \\d+", "").replaceAll("\\d+", "#");
        return kind + " " + plain.substring(0, Math.min(plain.length(), 80));
    }

    /** Describes the outcome for a report. */
    String describe() {
        StringBuilder sb = new StringBuilder(kind.name());
        if (fyrqlError != null) {
            sb.append("\n  FyrQL:  ").append(fyrqlError);
        }
        if (duckError != null) {
            sb.append("\n  DuckDB: ").append(duckError);
        }
        if (unoptimized != null) {
            sb.append("\n  without DuckDB's optimizer: ").append(unoptimized);
        }
        if (bound != null) {
            sb.append("\n  bound from SQL by FyrQL: ").append(bound);
        }
        if (!report.isEmpty()) {
            sb.append('\n').append(report.stripTrailing().indent(2).stripTrailing());
        }
        return sb.toString();
    }
}
