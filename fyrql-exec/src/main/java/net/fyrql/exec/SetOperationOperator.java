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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.SetOperationKind;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code EXCEPT} and {@code INTERSECT}: counts the rows of the right input
 * per key, then passes the rows of the left input that the counts admit.
 * Without {@code ALL} every key appears at most once; with {@code ALL} the
 * multiset difference or intersection is formed. Rows come out in the order
 * of the left input, which an intersection with no rows on the right does
 * not read.
 */
final class SetOperationOperator extends AbstractOperator {

    private static final long BYTES_PER_KEY = 96;

    private final PlanNode.SetOperation operation;
    private final Operator left;
    private final Operator right;
    private final MemoryBudget budget;
    private final int batchSize;
    private List<Batch> output;
    private long reserved;

    SetOperationOperator(String label, PlanNode.SetOperation operation, Operator left, Operator right,
            MemoryBudget budget, int batchSize) {
        super(label, operation.schema());
        this.operation = operation;
        this.left = left;
        this.right = right;
        this.budget = budget;
        this.batchSize = batchSize;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = computeAll();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> computeAll() {
        Map<RowKey, long[]> rightCounts = new LinkedHashMap<>();
        for (Batch b = right.next(); b != null; b = right.next()) {
            for (int row = 0; row < b.rowCount(); row++) {
                RowKey key = RowKey.of(b, row);
                long[] count = rightCounts.get(key);
                if (count == null) {
                    reserve();
                    count = new long[1];
                    rightCounts.put(key, count);
                }
                count[0]++;
            }
        }
        boolean except = operation.kind() == SetOperationKind.EXCEPT;
        boolean all = operation.all();
        if (!except && rightCounts.isEmpty()) {
            // Nothing to intersect with: as in DuckDB, the left input is not read.
            return new ArrayList<>();
        }
        // How often a key of the left input has been emitted, or seen for a set operation.
        Map<RowKey, long[]> leftCounts = new LinkedHashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (Batch b = left.next(); b != null; b = left.next()) {
            for (int row = 0; row < b.rowCount(); row++) {
                RowKey key = RowKey.of(b, row);
                long[] seen = leftCounts.get(key);
                if (seen == null) {
                    reserve();
                    seen = new long[1];
                    leftCounts.put(key, seen);
                }
                long inRight = rightCounts.containsKey(key) ? rightCounts.get(key)[0] : 0;
                boolean emit;
                if (except) {
                    // Without ALL: once, if absent on the right; with ALL: the surplus over the right count.
                    emit = all ? seen[0] >= inRight : seen[0] == 0 && inRight == 0;
                } else {
                    emit = all ? seen[0] < inRight : seen[0] == 0 && inRight > 0;
                }
                seen[0]++;
                if (emit) {
                    rows.add(b.row(row));
                }
            }
        }
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Batches.split(Batch.ofRows(schema(), rows), batchSize));
    }

    private void reserve() {
        budget.reserve(BYTES_PER_KEY, label());
        reserved += BYTES_PER_KEY;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        budget.release(reserved);
        reserved = 0;
    }
}
