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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the engine would like a scan to do, for the connector to accept in
 * part or in full. The filters, the order and the aggregation refer to the
 * columns of the scan output, that is, to positions in {@link #columns()},
 * not to the table schema.
 *
 * @param columns the columns to read, in the order of the output; a path
 *        may name a field inside a {@code STRUCT} column, which a
 *        connector that reads whole columns only answers with the request
 *        of {@link #withWholeColumns()}
 * @param filters conditions of type {@code BOOLEAN} over the output columns,
 *        all of which must hold; the connector may accept any subset
 * @param limit the number of rows the engine needs; a connector may accept
 *        it only when it accepts every filter and the order, if one is
 *        requested, and then delivers at most that many rows
 * @param order the order the engine wants, by position in the output; to
 *        accept as a whole or not at all
 * @param aggregation the grouping and the aggregates the engine computes
 *        over the output; to accept as a whole or not at all. The engine
 *        asks for it only without {@code limit} and {@code order}, and a
 *        connector may accept it only when it accepts all filters
 */
public record ScanRequest(List<ColumnPath> columns, List<Expression> filters, OptionalLong limit,
        List<PlanNode.SortKey> order, Optional<PlanNode.Aggregation> aggregation) {

    public ScanRequest {
        columns = List.copyOf(columns);
        filters = List.copyOf(filters);
        Objects.requireNonNull(limit, "limit");
        order = List.copyOf(order);
        Objects.requireNonNull(aggregation, "aggregation");
    }

    /** A request for whole columns only. */
    public static ScanRequest columns(List<Integer> columns) {
        return new ScanRequest(ColumnPath.whole(columns), List.of(), OptionalLong.empty(), List.of(),
                Optional.empty());
    }

    /** Returns the columns of the output before an aggregation, in the order of the request. */
    public Schema output(Schema table) {
        List<Schema.Field> fields = new ArrayList<>();
        for (ColumnPath p : columns) {
            fields.add(p.field(table));
        }
        return new Schema(fields);
    }

    /** Tells whether a path names a field inside a column. */
    public boolean readsFields() {
        for (ColumnPath p : columns) {
            if (!p.isWhole()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the request a connector answers that reads whole columns only:
     * the columns of the paths, each once, in the order of the paths, and
     * nothing else, since the filters, the order and the aggregation refer
     * to positions of the paths.
     */
    public ScanRequest withWholeColumns() {
        List<Integer> whole = new ArrayList<>();
        for (ColumnPath p : columns) {
            if (!whole.contains(p.column())) {
                whole.add(p.column());
            }
        }
        return columns(whole);
    }

    /**
     * Returns the indexes of the requested columns in the table schema, for a
     * connector that reads whole columns only.
     *
     * @throws IllegalArgumentException if a path names a field inside a column
     */
    public List<Integer> wholeColumns() {
        return ColumnPath.indexes(columns);
    }
}
