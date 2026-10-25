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
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of a common table, written once and then read by any number of
 * readers, each from the start; see {@link CommonTableOperator}. The
 * batches stay in memory while the budget has room for them. Once it has
 * none, all of them go to a file of the directory to spill to, and the rest
 * are appended to it; without such a directory, the budget refuses with its
 * message. Batches are not changed once made, so the readers of the batches
 * in memory share them without a copy. Closing gives the budget back and
 * deletes the file.
 */
final class CommonTableBuffer implements AutoCloseable {

    private final String label;
    private final Schema schema;
    private final MemoryBudget budget;
    private final SpillDirectory spill;
    private final QueryContext context;
    private final List<Batch> batches = new ArrayList<>();
    private long reserved;
    private long rows;
    private SpillFile file;
    private boolean finished;

    /**
     * @param label   the label of the operator of the common table, e.g. {@code CommonTable#0}
     * @param schema  the columns of the rows
     * @param budget  the budget the batches in memory take
     * @param spill   the directory to spill to, or {@code null} if the rows stay in memory
     * @param context the query, which counts what is spilled and may be canceled
     */
    CommonTableBuffer(String label, Schema schema, MemoryBudget budget, SpillDirectory spill, QueryContext context) {
        this.label = label;
        this.schema = schema;
        this.budget = budget;
        this.spill = spill;
        this.context = context;
    }

    /** Appends a batch; an empty one is skipped. */
    void add(Batch b) {
        if (finished) {
            throw new IllegalStateException("The rows of " + label + " are complete");
        }
        if (b.rowCount() == 0) {
            return;
        }
        rows += b.rowCount();
        if (file != null) {
            file.append(b);
            return;
        }
        long bytes = Batches.estimateBytes(b);
        if (spill == null) {
            budget.reserve(bytes, label);
        } else if (!budget.tryReserve(bytes)) {
            file = spill.create(label, "rows", schema, context);
            for (Batch kept : batches) {
                file.append(kept);
            }
            file.append(b);
            batches.clear();
            budget.release(reserved);
            reserved = 0;
            return;
        }
        reserved += bytes;
        batches.add(b);
    }

    /** Ends the appending; from then on, readers may read. */
    void finish() {
        finished = true;
        if (file != null) {
            file.finish();
        }
    }

    /** Returns the rows appended. */
    long rows() {
        return rows;
    }

    /** Tells whether the rows went to a file. */
    boolean spilled() {
        return file != null;
    }

    /** Opens a reader of all rows from the start; closing the buffer closes it. */
    synchronized Reader reader() {
        if (!finished) {
            throw new IllegalStateException("The rows of " + label + " are read before they are complete");
        }
        return file != null ? new Reader(null, file.reader()) : new Reader(batches, null);
    }

    /** Gives the budget back and deletes the file, if there is one. */
    @Override
    public synchronized void close() {
        batches.clear();
        budget.release(reserved);
        reserved = 0;
        if (file != null) {
            file.close();
            file = null;
        }
        finished = true;
    }

    /** Reads the rows of the buffer, from memory or from the file. */
    static final class Reader {
        private final List<Batch> batches;
        private final SpillFile.SpillReader spilled;
        private int next;

        private Reader(List<Batch> batches, SpillFile.SpillReader spilled) {
            this.batches = batches;
            this.spilled = spilled;
        }

        /** Returns the next batch, or {@code null} after the last one. */
        Batch next() {
            if (spilled != null) {
                return spilled.next();
            }
            return next < batches.size() ? batches.get(next++) : null;
        }
    }
}
