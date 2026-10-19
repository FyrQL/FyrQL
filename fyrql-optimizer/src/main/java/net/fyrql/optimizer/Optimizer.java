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

package net.fyrql.optimizer;

import net.fyrql.connector.Connector;
import net.fyrql.core.plan.PlanNode;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Rewrites a bound plan into the plan the engine runs: the constant parts
 * of the expressions are evaluated once ({@link ConstantFolding}), the
 * conditions of filters move down to the scans and become join keys where
 * they compare the two inputs of a join ({@link PredicatePushdown}), inner
 * joins are put in the order with the smallest estimated results, each
 * building its smaller input, the tables of a source that runs their join
 * coming together first where that pays ({@link JoinOrdering}), every scan
 * reads only the columns in use and, where its connector can, only the
 * fields of
 * struct columns in use ({@link ColumnPruning}), a part of the plan that
 * joins tables of one source only goes to that source if it runs it as a
 * whole ({@link JoinPushdown}), and the connector of a scan takes over
 * what it can of the filter, aggregation, sort and limit above it
 * ({@link ScanPushdown}). The output of the plan keeps its columns and
 * types; the rows are the same with and without the rewrite.
 */
public final class Optimizer {

    /**
     * What the optimizer does.
     *
     * @param pushdown whether scans are negotiated with their connectors
     * @param pruneColumns whether scans are narrowed to the columns in use
     * @param foldConstants whether constant expressions are evaluated before the query runs
     * @param pushPredicates whether the conditions of filters move down the plan and into join keys
     * @param orderJoins whether inner joins are put in the order of the smallest estimated results
     * @param pushJoins whether the parts of the plan that join tables of one source are offered to that source,
     *        and the join order brings such tables together
     * @param pruneFields whether scans read only the fields of struct columns in use, where their connectors can;
     *        with {@code pruneColumns} only
     */
    public record Options(boolean pushdown, boolean pruneColumns, boolean foldConstants, boolean pushPredicates,
            boolean orderJoins, boolean pushJoins, boolean pruneFields) {

        /** Everything. */
        public static final Options DEFAULT = new Options(true, true, true, true, true, true, true);

        /**
         * Everything that does not involve the connectors, for tests that
         * compare the results with and without pushdown; the row counts of
         * the catalogs still order the joins.
         */
        public static final Options NO_PUSHDOWN = new Options(false, true, true, true, true, false, false);

        /** The system property that switches pushdown off: {@code on} (the default) or {@code off}. */
        public static final String PROPERTY = "fyrql.pushdown";

        /**
         * Returns the options of the system property {@value #PROPERTY}:
         * {@link #DEFAULT} if it is not set or {@code on}, {@link #NO_PUSHDOWN}
         * if it is {@code off}.
         *
         * @throws IllegalArgumentException if the property has another value
         */
        public static Options fromSystemProperty() {
            String value = System.getProperty(PROPERTY);
            if (value == null || value.isBlank()) {
                return DEFAULT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "on", "true" -> DEFAULT;
                case "off", "false" -> NO_PUSHDOWN;
                default -> throw new IllegalArgumentException("The system property " + PROPERTY
                        + " must be on or off: " + value);
            };
        }
    }

    private final Function<String, Connector> connectors;
    private final Options options;

    /**
     * @param connectors the connector of a source, by its name
     * @param options what to do
     */
    public Optimizer(Function<String, Connector> connectors, Options options) {
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.options = Objects.requireNonNull(options, "options");
    }

    public Options options() {
        return options;
    }

    /**
     * Returns the estimated rows of the nodes of a plan, from the row counts
     * that the catalogs know, as the join order is chosen; a table whose
     * catalog does not know its rows counts as 1,000 rows. Each call asks
     * the catalogs afresh.
     */
    public ToDoubleFunction<PlanNode> estimator() {
        return new Statistics(connectors)::rows;
    }

    /** Rewrites a plan; a plan that was rewritten before comes back unchanged. */
    public PlanNode optimize(PlanNode plan) {
        PlanNode result = plan;
        if (options.foldConstants()) {
            result = ConstantFolding.fold(result);
        }
        if (options.pushPredicates()) {
            result = PredicatePushdown.push(result);
        }
        if (options.orderJoins()) {
            result = JoinOrdering.order(result, new Statistics(connectors), options.pushJoins() ? connectors : null);
        }
        if (options.pruneColumns()) {
            result = ColumnPruning.prune(result, options.pruneFields() ? connectors : null);
        }
        if (options.pushJoins()) {
            result = JoinPushdown.push(result, connectors);
        }
        if (options.pushdown()) {
            result = ScanPushdown.push(result, connectors);
        }
        return result;
    }
}
