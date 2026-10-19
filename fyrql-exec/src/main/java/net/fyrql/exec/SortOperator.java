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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Materializes the input, sorts an index over it by the keys (stable, with
 * {@code NULL} first or last as each key says, see {@link RowOrder}) and
 * emits the rows in that order, batch by batch. On more than one thread,
 * the workers of a segment below keep its batches, see {@link Materialize},
 * and the index is sorted on several threads, see
 * {@link RowOrder#sort(Runnable, int)}; the order is the same.
 */
final class SortOperator extends AbstractOperator {

    private final PlanNode.Sort sort;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private final int threads;
    private List<Batch> output;
    private final AtomicLong reserved = new AtomicLong();

    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize) {
        this(label, sort, input, budget, batchSize, 1);
    }

    /**
     * @param threads the most threads that sort the rows
     */
    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize,
            int threads) {
        super(label, sort.schema());
        this.sort = sort;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
        this.threads = threads;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = sorted();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> sorted() {
        List<Batch> batches = Materialize.read(input, label(), budget, reserved);
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }
        Batch all = Batches.concat(schema(), batches);
        int[] rows = new RowOrder(all, sort.keys()).sort(this::checkCancelled, threads);
        return new ArrayList<>(Batches.split(all.select(rows), batchSize));
    }

    @Override
    public void close() {
        input.close();
        budget.release(reserved.getAndSet(0));
    }
}
