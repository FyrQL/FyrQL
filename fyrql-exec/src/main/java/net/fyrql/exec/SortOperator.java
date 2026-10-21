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
import java.util.function.IntFunction;

/**
 * Materializes the input, sorts an index over it by the keys (stable, with
 * {@code NULL} first or last as each key says, see {@link RowOrder}) and
 * emits the rows in that order, batch by batch. On more than one thread,
 * the workers of a segment below keep its batches, see {@link SortSink},
 * and the index is sorted on several threads, see
 * {@link RowOrder#sort(Runnable, int)}; the order is the same.
 * <p>
 * With a directory to spill to, the input that does not fit in the budget
 * goes to disk as sorted runs, each worker writing its own, which are then
 * merged as the rows are read, see {@link ExternalSort}; the order is again
 * the same. Closing deletes the runs.
 */
final class SortOperator extends AbstractOperator {

    private final PlanNode.Sort sort;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private final int threads;
    private final SpillDirectory spill;
    private List<Batch> output;
    private ExternalSort external;
    private final List<SortSink> sinks = new ArrayList<>();
    private final AtomicLong reserved = new AtomicLong();

    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize) {
        this(label, sort, input, budget, batchSize, 1);
    }

    /**
     * @param threads the most threads that sort the rows
     */
    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize,
            int threads) {
        this(label, sort, input, budget, batchSize, threads, null);
    }

    /**
     * @param threads the most threads that sort the rows
     * @param spill   the directory to spill to when the budget is exhausted, or {@code null} to fail then
     */
    SortOperator(String label, PlanNode.Sort sort, Operator input, MemoryBudget budget, int batchSize,
            int threads, SpillDirectory spill) {
        super(label, sort.schema());
        this.sort = sort;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
        this.threads = threads;
        this.spill = spill;
    }

    @Override
    Batch compute() {
        if (output == null && external == null) {
            sort();
        }
        if (external != null) {
            return external.next();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private void sort() {
        List<SortSink> read = read();
        boolean spilled = false;
        for (SortSink s : read) {
            spilled |= s.spilled();
        }
        if (!spilled) {
            List<Batch> batches = SortSink.inOrder(read);
            if (batches.isEmpty()) {
                output = new ArrayList<>();
                return;
            }
            Batch all = Batches.concat(schema(), batches);
            int[] rows = new RowOrder(all, sort.keys()).sort(this::checkCancelled, threads);
            output = new ArrayList<>(Batches.split(all.select(rows), batchSize));
            return;
        }
        List<ExternalSort.Run> runs = new ArrayList<>();
        for (SortSink s : read) {
            s.spillKept(threads);
            runs.addAll(s.runs());
        }
        external = new ExternalSort(label(), schema(), sort.keys(), budget, spill, context(), batchSize);
        external.start(runs);
    }

    /** Reads the input into sinks: one per worker of a segment below, otherwise one. */
    private List<SortSink> read() {
        IntFunction<SortSink> sink = workers -> {
            SortSink s = new SortSink(label(), schema(), sort.keys(), budget, reserved, spill, context(),
                    batchSize, workers == 1 ? threads : 1);
            sinks.add(s);
            return s;
        };
        if (input instanceof ParallelSegment segment) {
            return segment.drain(sink);
        }
        SortSink s = sink.apply(1);
        for (Batch b = input.next(); b != null; b = input.next()) {
            s.accept(0, b);
        }
        return List.of(s);
    }

    @Override
    public void close() {
        try {
            input.close();
        } finally {
            try {
                if (external != null) {
                    external.close();
                }
                for (SortSink s : sinks) {
                    s.close();
                }
                sinks.clear();
            } finally {
                budget.release(reserved.getAndSet(0));
            }
        }
    }
}
