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
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.List;

/**
 * Materializes the input, sorts an index over it by the keys (stable, with
 * {@code NULL} first or last as each key says, see {@link RowOrder}) and
 * emits the rows in that order, batch by batch.
 */
final class SortOperator extends AbstractOperator {

    private final PlanNode.Sort sort;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private List<Batch> output;
    private long reserved;

    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize) {
        super(label, sort.schema());
        this.sort = sort;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = sorted();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> sorted() {
        List<Batch> batches = new ArrayList<>();
        for (Batch b = input.next(); b != null; b = input.next()) {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, label());
            reserved += bytes;
            batches.add(b);
        }
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }
        Batch all = Batches.concat(schema(), batches);
        int[] rows = new RowOrder(all, sort.keys()).sort(this::checkCancelled);
        return new ArrayList<>(Batches.split(all.select(rows), batchSize));
    }

    @Override
    public void close() {
        input.close();
        budget.release(reserved);
        reserved = 0;
    }
}
