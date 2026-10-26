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

package net.fyrql.connector;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.util.List;
import java.util.Objects;

/**
 * What a connector will do for a scan: the schema of the batches it
 * delivers and the parts of the request it has taken over. Everything it
 * did not take over, the engine evaluates on the batches. What it took
 * over, it evaluates with exactly the semantics of the engine.
 *
 * @param table the table
 * @param output the columns of the delivered batches, in order: the
 *        columns it reads, the first {@link ScanRequest#needed()} of them
 *        when it leaves out those only the filters read, or the groups and
 *        aggregates when the aggregation is accepted
 * @param columns the columns the connector reads: the requested ones, or,
 *        if it reads whole columns only and fields were requested, those
 *        of {@link ScanRequest#withWholeColumns()}
 * @param acceptedFilters the filters the connector evaluates, a subset of
 *        the requested ones; the engine evaluates the rest
 * @param limitAccepted whether the connector delivers at most the requested
 *        number of rows
 * @param orderAccepted whether the batches come in the requested order
 * @param aggregationAccepted whether the connector groups and aggregates,
 *        so that the batches have the columns of the aggregation
 * @param state whatever the connector needs to remember for the scan
 */
public record ScanPlan(TableRef table, Schema output, List<ColumnPath> columns, List<Expression> acceptedFilters,
        boolean limitAccepted, boolean orderAccepted, boolean aggregationAccepted, Object state) {

    public ScanPlan {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(output, "output");
        columns = List.copyOf(columns);
        acceptedFilters = List.copyOf(acceptedFilters);
        if (!aggregationAccepted && output.size() > columns.size()) {
            throw new IllegalArgumentException(output.size() + " output columns for " + columns.size() + " paths");
        }
    }

    /**
     * A plan that reads the requested columns, whole columns instead of
     * fields inside them, and takes over nothing else.
     */
    public static ScanPlan columnsOnly(TableRef table, Schema tableSchema, ScanRequest request, Object state) {
        ScanRequest read = request.readsFields() ? request.withWholeColumns() : request;
        return new ScanPlan(table, read.output(tableSchema), read.columns(), List.of(), false, false, false, state);
    }

    /** Tells whether the batches leave out columns at the end that only the filters read. */
    public boolean leavesOutColumns() {
        return !aggregationAccepted && output.size() < columns.size();
    }

    /**
     * Returns the indexes of the columns in the table schema, for a connector
     * that reads whole columns only.
     *
     * @throws IllegalArgumentException if a path names a field inside a column
     */
    public List<Integer> wholeColumns() {
        return ColumnPath.indexes(columns);
    }
}
