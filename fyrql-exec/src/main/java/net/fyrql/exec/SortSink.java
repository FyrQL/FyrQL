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
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the batches of a sort, those of one worker of a segment or all of
 * them, each reserved in the budget with the place of its first row among
 * all rows, see {@link HashAggregateOperator#place}. When a batch does not
 * fit and there is a directory to spill to, the batches kept and that one
 * are sorted together into a run that goes to disk with the place of each
 * row, see {@link ExternalSort}, and the budget they took is given back;
 * without such a directory, the budget fails as it would without spilling.
 */
final class SortSink implements ParallelSegment.Sink {

    /**
     * A batch kept.
     *
     * @param morsel the morsel of the batch
     * @param first  the place of its first row
     * @param batch  the batch
     */
    record Kept(int morsel, long first, Batch batch) {
    }

    private final String label;
    private final Schema schema;
    private final List<SortKey> keys;
    private final MemoryBudget budget;
    private final AtomicLong reserved;
    private final SpillDirectory spill;
    private final QueryContext context;
    private final int batchSize;
    private final int threads;
    private final List<Kept> kept = new ArrayList<>();
    private long keptBytes;
    private final List<ExternalSort.Run> runs = new ArrayList<>();
    private final List<SpillFile> files = new ArrayList<>();
    private int morsel = -1;
    private long row;

    /**
     * @param schema   the schema of the rows
     * @param reserved counts the bytes reserved in the budget, which the operator releases
     * @param spill    the directory to spill to, or {@code null} if the sort does not spill
     * @param threads  the most threads that sort a run
     */
    SortSink(String label, Schema schema, List<SortKey> keys, MemoryBudget budget, AtomicLong reserved,
            SpillDirectory spill, QueryContext context, int batchSize, int threads) {
        this.label = label;
        this.schema = schema;
        this.keys = keys;
        this.budget = budget;
        this.reserved = reserved;
        this.spill = spill;
        this.context = context;
        this.batchSize = batchSize;
        this.threads = threads;
    }

    @Override
    public void accept(int morsel, Batch b) {
        if (morsel != this.morsel) {
            this.morsel = morsel;
            row = 0;
        }
        Kept k = new Kept(morsel, HashAggregateOperator.place(morsel, row), b);
        row += b.rowCount();
        long bytes = Batches.estimateBytes(b);
        if (spill == null) {
            budget.reserve(bytes, label);
        } else if (!budget.tryReserve(bytes)) {
            kept.add(k);
            spillKept(threads);
            return;
        }
        reserved.addAndGet(bytes);
        keptBytes += bytes;
        kept.add(k);
    }

    /** Returns whether runs went to disk. */
    boolean spilled() {
        return !runs.isEmpty();
    }

    /** Returns the runs on disk. */
    List<ExternalSort.Run> runs() {
        return runs;
    }

    /**
     * Sorts the batches kept into a run on disk, if there are any, and
     * gives the budget they took back.
     *
     * @param threads the most threads that sort them
     */
    void spillKept(int threads) {
        if (kept.isEmpty()) {
            return;
        }
        List<Batch> parts = new ArrayList<>(kept.size());
        for (Kept k : kept) {
            parts.add(k.batch());
        }
        RowOrder order = new RowOrder(parts, keys);
        int[] sorted = order.sort(() -> context.check(label), threads);
        int n = sorted.length;
        int[] partOf = new int[n];
        int[] rowOf = new int[n];
        for (int i = 0; i < n; i++) {
            partOf[i] = order.part(sorted[i]);
            rowOf[i] = order.row(sorted[i]);
        }
        Schema runSchema = ExternalSort.runSchema(schema);
        SpillFile file = spill.create(label, "run", runSchema, context);
        files.add(file);
        long batchBytes = 0;
        int step = ExternalSort.runRows(keptBytes, n, budget, batchSize);
        for (int from = 0; from < n; from += step) {
            int to = Math.min(n, from + step);
            long[] places = new long[to - from];
            for (int i = from; i < to; i++) {
                places[i - from] = kept.get(partOf[i]).first() + rowOf[i];
            }
            Batch b = ExternalSort.withPlaces(runSchema, Batches.gather(schema, parts, partOf, rowOf, from, to),
                    places);
            batchBytes = Math.max(batchBytes, Batches.estimateBytes(b));
            file.append(b);
        }
        file.finish();
        runs.add(new ExternalSort.Run(file, batchBytes));
        kept.clear();
        budget.release(keptBytes);
        reserved.addAndGet(-keptBytes);
        keptBytes = 0;
    }

    /** Deletes the files of the runs. */
    void close() {
        for (SpillFile f : files) {
            f.close();
        }
        files.clear();
    }

    /**
     * Returns the batches kept by sinks, in the order one thread reads them:
     * by their morsels, each worker having taken its morsels in their order.
     */
    static List<Batch> inOrder(List<SortSink> sinks) {
        List<Kept> all = new ArrayList<>();
        for (SortSink s : sinks) {
            all.addAll(s.kept);
        }
        all.sort(Comparator.comparingInt(Kept::morsel));
        List<Batch> batches = new ArrayList<>(all.size());
        for (Kept k : all) {
            batches.add(k.batch());
        }
        return batches;
    }
}
