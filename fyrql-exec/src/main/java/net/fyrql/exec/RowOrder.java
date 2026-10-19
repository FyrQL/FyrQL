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

import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The order of the rows of a batch by sort keys, each with its direction
 * and the place of {@code NULL}. Rows whose keys are all equal are equal,
 * and so are two {@code NULL}s; without keys, all rows are equal.
 */
final class RowOrder {

    /** The rows from which a sort runs on several threads, and the fewest rows of a run. */
    static final int PARALLEL_ROWS = 16_384;
    private static final int MIN_RUN = 4096;

    private final Batch batch;
    private final List<SortKey> keys;
    private final Vector[] columns;

    RowOrder(Batch batch, List<SortKey> keys) {
        this.batch = batch;
        this.keys = keys;
        this.columns = new Vector[keys.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = batch.column(keys.get(i).column());
        }
    }

    /** Compares two rows of the batch. */
    int compare(int a, int b) {
        for (int i = 0; i < columns.length; i++) {
            SortKey k = keys.get(i);
            Vector v = columns[i];
            boolean nullA = v.isNull(a);
            boolean nullB = v.isNull(b);
            int c;
            if (nullA || nullB) {
                c = nullA == nullB ? 0 : nullA == k.nullsFirst() ? -1 : 1;
            } else {
                c = Vectors.compare(v, a, v, b);
                c = k.ascending() ? c : -c;
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Returns the rows of the batch in this order; equal rows keep their
     * order.
     *
     * @param check runs every {@link AbstractOperator#CHECK_INTERVAL}
     *        comparisons, to stop a canceled query
     */
    int[] sort(Runnable check) {
        return sort(check, 1);
    }

    /**
     * Returns the rows of the batch in this order, as {@link #sort(Runnable)}
     * does, on up to a number of threads: from {@value #PARALLEL_ROWS} rows
     * on, runs of rows are sorted each on its own, then merged in pairs,
     * each merge cut into pieces of about equal size, the rows of the left
     * run first among equal ones. Any stable sort gives the same order.
     *
     * @param check runs every {@link AbstractOperator#CHECK_INTERVAL}
     *        comparisons of each thread, to stop a canceled query
     */
    int[] sort(Runnable check, int threads) {
        int n = batch.rowCount();
        int runs = Math.min(threads, n / MIN_RUN);
        if (n < PARALLEL_ROWS || runs < 2) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, new Counting(check)::compare);
            int[] rows = new int[order.length];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = order[i];
            }
            return rows;
        }
        int[] bounds = new int[runs + 1];
        for (int r = 0; r <= runs; r++) {
            bounds[r] = (int) ((long) n * r / runs);
        }
        int[][] buffers = {new int[n], new int[n]};
        Workers.run(runs, threads, r -> {
            Integer[] order = new Integer[bounds[r + 1] - bounds[r]];
            for (int i = 0; i < order.length; i++) {
                order[i] = bounds[r] + i;
            }
            Arrays.sort(order, new Counting(check)::compare);
            for (int i = 0; i < order.length; i++) {
                buffers[0][bounds[r] + i] = order[i];
            }
        });
        int piece = (n + threads - 1) / threads;
        int from = 0;
        for (int width = 1; width < runs; width *= 2) {
            int[] in = buffers[from];
            int[] out = buffers[1 - from];
            // Each task merges a piece of the output of a pair of runs: its runs, then the piece within them.
            List<int[]> tasks = new ArrayList<>();
            for (int r = 0; r < runs; r += 2 * width) {
                int lo = bounds[r];
                int mid = bounds[Math.min(r + width, runs)];
                int hi = bounds[Math.min(r + 2 * width, runs)];
                for (int k = 0; k < hi - lo; k += piece) {
                    tasks.add(new int[] {lo, mid, hi, k, Math.min(hi - lo, k + piece)});
                }
            }
            Workers.run(tasks.size(), threads, t -> {
                int[] task = tasks.get(t);
                merge(in, task[0], task[1], task[2], task[3], task[4], out, new Counting(check));
            });
            from = 1 - from;
        }
        return buffers[from];
    }

    /**
     * Compares rows on one thread, counting the comparisons to run a check
     * every {@link AbstractOperator#CHECK_INTERVAL} of them.
     */
    private final class Counting {
        private final Runnable check;
        private int comparisons;

        Counting(Runnable check) {
            this.check = check;
        }

        int compare(int a, int b) {
            if ((++comparisons & (AbstractOperator.CHECK_INTERVAL - 1)) == 0) {
                check.run();
            }
            return RowOrder.this.compare(a, b);
        }
    }

    /**
     * Writes the rows {@code [from, to)} of the merge of the sorted runs
     * {@code [lo, mid)} and {@code [mid, hi)} of an array to the same
     * places of another one, those of the left run first among equal rows.
     */
    private static void merge(int[] in, int lo, int mid, int hi, int from, int to, int[] out, Counting order) {
        int i = lo + leftCount(in, lo, mid, hi, from, order);
        int j = lo + (mid - lo) + from - (i - lo);
        int iEnd = lo + leftCount(in, lo, mid, hi, to, order);
        int jEnd = mid + to - (iEnd - lo);
        for (int k = lo + from; k < lo + to; k++) {
            if (j >= jEnd || i < iEnd && order.compare(in[i], in[j]) <= 0) {
                out[k] = in[i++];
            } else {
                out[k] = in[j++];
            }
        }
    }

    /**
     * Returns how many of the first {@code k} rows of the merge of the
     * sorted runs {@code [lo, mid)} and {@code [mid, hi)} come from the left
     * one: the least count {@code i} for which the next row of the left run
     * does not come before the last of the {@code k - i} rows of the right.
     */
    private static int leftCount(int[] in, int lo, int mid, int hi, int k, Counting order) {
        int left = mid - lo;
        int right = hi - mid;
        int a = Math.max(0, k - right);
        int b = Math.min(k, left);
        while (a < b) {
            int i = (a + b) >>> 1;
            int j = k - i;
            if (j > 0 && order.compare(in[lo + i], in[mid + j - 1]) <= 0) {
                a = i + 1;
            } else {
                b = i;
            }
        }
        return a;
    }
}
