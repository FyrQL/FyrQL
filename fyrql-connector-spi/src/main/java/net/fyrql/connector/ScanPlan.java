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
 *        columns it reads, or the groups and aggregates when the
 *        aggregation is accepted
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
        if (!aggregationAccepted && output.size() != columns.size()) {
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
