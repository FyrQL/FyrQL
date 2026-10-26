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
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Merges sorted runs that a sort spilled to disk, see {@link SortSink}, and
 * emits their rows in order, batch by batch. The rows of a run carry their
 * place among all rows in a last column, see {@link #withPlaces}: rows with
 * equal keys come in the order of their places, so that the order is the
 * one the stable sort in memory gives. Each run being merged keeps one
 * batch in memory, its largest batch reserved in the budget, which is
 * small, see {@link #runRows}; if the runs do
 * not fit at once, or there are more than {@link #fanIn} of them, groups of
 * them are first merged into new runs on disk, as many at a time as fit, at
 * most {@code fanIn}, down to two. A merge of more than two runs takes at
 * most the room it is given, so that the reader of the rows keeps the rest
 * of the budget for itself.
 */
final class ExternalSort implements AutoCloseable {

    /** The most runs merged at once. */
    static final int FAN_IN = 64;

    /** {@link #FAN_IN}, which tests lower. */
    static volatile int fanIn = FAN_IN;

    /**
     * A sorted run on disk.
     *
     * @param file       the file of the run, its rows with their places
     * @param batchBytes the bytes of its largest batch, as the budget counts them
     */
    record Run(SpillFile file, long batchBytes) {
    }

    private final String label;
    private final Schema schema;
    private final List<SortKey> keys;
    private final MemoryBudget budget;
    private final SpillDirectory spill;
    private final QueryContext context;
    private final int batchSize;
    /** The most bytes the runs of a merge of more than two take in the budget. */
    private final long room;
    /** The runs not yet merged, and all files, which closing deletes. */
    private final Deque<Run> runs = new ArrayDeque<>();
    private final List<SpillFile> files = new ArrayList<>();
    private Merge merge;
    private long reserved;

    /**
     * Merges runs in as much of the budget as there is.
     *
     * @param schema the schema of the rows, without the places
     */
    ExternalSort(String label, Schema schema, List<SortKey> keys, MemoryBudget budget, SpillDirectory spill,
            QueryContext context, int batchSize) {
        this(label, schema, keys, budget, spill, context, batchSize, budget.limit());
    }

    /**
     * @param schema the schema of the rows, without the places
     * @param room   the most bytes the runs of a merge of more than two take in the budget
     */
    ExternalSort(String label, Schema schema, List<SortKey> keys, MemoryBudget budget, SpillDirectory spill,
            QueryContext context, int batchSize, long room) {
        this.label = label;
        this.schema = schema;
        this.keys = keys;
        this.budget = budget;
        this.spill = spill;
        this.context = context;
        this.batchSize = batchSize;
        this.room = room;
    }

    /** Returns the schema of a run: the columns of the rows, then their places. */
    static Schema runSchema(Schema schema) {
        return schema.concat(Schema.of(new Schema.Field("$place", DataType.BIGINT, false)));
    }

    /**
     * Returns the rows of a batch of a run: so few that {@value #FAN_IN}
     * runs of such batches take at most half of the budget, by the bytes of
     * the rows to write, but at least one and at most the batch size.
     *
     * @param bytes the bytes of the rows to write, as the budget counts them
     * @param rows  the rows to write
     */
    static int runRows(long bytes, long rows, MemoryBudget budget, int batchSize) {
        if (rows == 0 || bytes == 0) {
            return batchSize;
        }
        long perRow = Math.max(1, bytes / rows);
        long fit = budget.limit() / (2L * FAN_IN) / perRow;
        return (int) Math.max(1, Math.min(batchSize, fit));
    }

    /** Returns a batch of the rows with their places as a last column, see {@link #runSchema}. */
    static Batch withPlaces(Schema runSchema, Batch rows, long[] places) {
        List<Vector> columns = new ArrayList<>(rows.columns());
        columns.add(BigIntVector.of(places));
        return new Batch(runSchema, rows.rowCount(), columns);
    }

    /**
     * Takes the runs, which this sort deletes when it closes, and merges
     * them until one merge of what is left fits, which {@link #next()}
     * then reads.
     *
     * @throws QueryException if not even two runs, or the one left, fit in the budget
     */
    void start(List<Run> sorted) {
        for (Run r : sorted) {
            runs.add(r);
            files.add(r.file());
        }
        while (true) {
            int n = Math.min(Math.max(2, fanIn), runs.size());
            boolean fits = fits(n);
            while (!fits && n > 2) {
                n = Math.max(2, n / 2);
                fits = fits(n);
            }
            if (fits && n == runs.size()) {
                merge = new Merge(take(n));
                return;
            }
            if (!fits) {
                long needed = bytes(n);
                long written = 0;
                for (SpillFile f : files) {
                    written += f.bytes();
                }
                throw new QueryException(label, "Memory budget of " + budget.limit() + " bytes exceeded: "
                        + (budget.used() + needed) + " bytes needed after spilling " + written + " bytes");
            }
            List<Run> group = take(n);
            // The merged run is written in batches as small as those of the runs merged.
            long perRow = 1;
            for (Run r : group) {
                long rowsPerBatch = Math.max(1, r.file().rows() / Math.max(1, r.file().batches()));
                perRow = Math.max(perRow, r.batchBytes() / rowsPerBatch);
            }
            int rows = runRows(perRow, 1, budget, batchSize);
            SpillFile file = spill.create(label, "merged", runSchema(schema), context);
            files.add(file);
            long batchBytes = 0;
            Merge m = new Merge(group);
            try {
                for (Batch b = m.next(true, rows); b != null; b = m.next(true, rows)) {
                    batchBytes = Math.max(batchBytes, Batches.estimateBytes(b));
                    file.append(b);
                }
                file.finish();
            } finally {
                m.close();
                release();
            }
            for (Run r : group) {
                r.file().close();
                files.remove(r.file());
            }
            runs.add(new Run(file, batchBytes));
        }
    }

    /** Returns the next batch of the rows in order, without their places, or {@code null} after the last. */
    Batch next() {
        return merge == null ? null : merge.next(false, batchSize);
    }

    private long bytes(int n) {
        long bytes = 0;
        int i = 0;
        for (Run r : runs) {
            if (i++ == n) {
                break;
            }
            bytes += r.batchBytes();
        }
        return bytes;
    }

    /** Reserves the batches of the first runs, unless they are more than two and take more than the room. */
    private boolean fits(int n) {
        long bytes = bytes(n);
        return (n <= 2 || bytes <= room) && reserve(bytes);
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) {
            return false;
        }
        reserved += bytes;
        return true;
    }

    private void release() {
        budget.release(reserved);
        reserved = 0;
    }

    private List<Run> take(int n) {
        List<Run> taken = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            taken.add(runs.poll());
        }
        return taken;
    }

    /** Closes the merge and deletes the files of all runs; gives the budget back. */
    @Override
    public void close() {
        try {
            if (merge != null) {
                merge.close();
                merge = null;
            }
            for (SpillFile f : files) {
                f.close();
            }
            files.clear();
            runs.clear();
        } finally {
            release();
        }
    }

    /**
     * A merge of runs over a binary heap of the runs that have rows left,
     * by their next row: its keys, then its place.
     */
    private final class Merge {
        private final SpillFile.SpillReader[] readers;
        private final Batch[] current;
        private final Vector[][] keyColumns;
        private final BigIntVector[] places;
        private final int[] at;
        private final int[] heap;
        private int size;

        Merge(List<Run> runs) {
            int k = runs.size();
            readers = new SpillFile.SpillReader[k];
            current = new Batch[k];
            keyColumns = new Vector[k][];
            places = new BigIntVector[k];
            at = new int[k];
            heap = new int[k];
            for (int i = 0; i < k; i++) {
                readers[i] = runs.get(i).file().reader();
            }
            for (int i = 0; i < k; i++) {
                if (load(i)) {
                    heap[size] = i;
                    siftUp(size++);
                }
            }
        }

        /** Reads the next batch of a run; {@code false} at its end. */
        private boolean load(int i) {
            Batch b = readers[i].next();
            if (b == null) {
                current[i] = null;
                return false;
            }
            current[i] = b;
            keyColumns[i] = RowOrder.columns(b, keys);
            places[i] = (BigIntVector) b.column(b.columns().size() - 1);
            at[i] = 0;
            return true;
        }

        /** Returns the next batch of up to a number of rows in order, or {@code null} after the last. */
        Batch next(boolean withPlaces, int rows) {
            if (size == 0) {
                return null;
            }
            context.check(label);
            List<Batch> parts = new ArrayList<>();
            int[] partIndex = new int[current.length];
            Arrays.fill(partIndex, -1);
            int[] partOf = new int[rows];
            int[] rowOf = new int[rows];
            int n = 0;
            while (n < rows && size > 0) {
                int i = heap[0];
                if (partIndex[i] < 0) {
                    partIndex[i] = parts.size();
                    parts.add(current[i]);
                }
                partOf[n] = partIndex[i];
                rowOf[n++] = at[i]++;
                if (at[i] == current[i].rowCount()) {
                    // The batch stays among the parts of this output; the run goes on with its next one.
                    partIndex[i] = -1;
                    if (!load(i)) {
                        heap[0] = heap[--size];
                    }
                }
                siftDown(0);
            }
            return Batches.gather(withPlaces ? runSchema(schema) : schema, parts, partOf, rowOf, 0, n);
        }

        private boolean before(int x, int y) {
            int c = RowOrder.compare(keys, keyColumns[x], at[x], keyColumns[y], at[y]);
            return c < 0 || c == 0 && places[x].getLong(at[x]) < places[y].getLong(at[y]);
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) / 2;
                if (!before(heap[i], heap[parent])) {
                    return;
                }
                int t = heap[i];
                heap[i] = heap[parent];
                heap[parent] = t;
                i = parent;
            }
        }

        private void siftDown(int i) {
            while (true) {
                int least = i;
                for (int child = 2 * i + 1; child <= 2 * i + 2 && child < size; child++) {
                    if (before(heap[child], heap[least])) {
                        least = child;
                    }
                }
                if (least == i) {
                    return;
                }
                int t = heap[i];
                heap[i] = heap[least];
                heap[least] = t;
                i = least;
            }
        }

        void close() {
            for (SpillFile.SpillReader r : readers) {
                if (r != null) {
                    r.close();
                }
            }
        }
    }
}
