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

import java.util.List;
import java.util.function.IntToLongFunction;

/**
 * The partitions into which the operators that spill cut rows by the
 * hashes of their keys, and the places that keep the rows in the order of
 * one thread: an aggregation, see {@link HashAggregateOperator}, and a join,
 * see {@link JoinPartitions}. A partition that does not fit is cut into
 * {@value #PARTITIONS} smaller ones by more of the bits of the same hashes,
 * down to {@value #MAX_PARTITIONS} partitions.
 */
final class Partitions {

    /** The partitions into which rows are cut at first, and into which a partition is cut. */
    static final int PARTITIONS = 16;

    /** The most partitions into which rows that went to disk are cut. */
    static final int MAX_PARTITIONS = 65_536;

    /** The bits of a place for the row in its morsel, the highest morsel and the highest row it tells apart. */
    static final int ROW_BITS = 40;
    private static final long MAX_MORSEL = (1L << 23) - 1;
    private static final long MAX_ROW = (1L << ROW_BITS) - 1;

    private Partitions() {
    }

    /** Returns the partition of a hash: its bits mixed, the highest of them scaled to the partitions. */
    static int partition(int hash, int partitions) {
        int mixed = hash * 0x9E3779B9;
        return (int) (((mixed & 0xFFFFFFFFL) * partitions) >>> 32);
    }

    /** Returns the place of a row: its morsel and its place in the morsel, which order the rows as one thread. */
    static long place(int morsel, long row) {
        return Math.min(morsel, MAX_MORSEL) << ROW_BITS | Math.min(row, MAX_ROW);
    }

    /**
     * Merges runs of places, each in ascending order, and returns the
     * indexes of the places in the runs one after the other, in the order of
     * the places; of equal places, the one of the earlier run comes first.
     */
    static int[] mergeOrder(List<long[]> places) {
        long[][] runs = places.toArray(new long[0][]);
        int r = runs.length;
        int[] start = new int[r];
        int[] at = new int[r];
        int n = 0;
        for (int i = 0; i < r; i++) {
            start[i] = n;
            n += runs[i].length;
        }
        // A binary heap of the runs that have places left, by their next place.
        int[] heap = new int[r];
        int size = 0;
        for (int i = 0; i < r; i++) {
            if (runs[i].length > 0) {
                heap[size++] = i;
                siftUp(heap, size - 1, runs, at);
            }
        }
        int[] order = new int[n];
        for (int k = 0; k < n; k++) {
            int i = heap[0];
            order[k] = start[i] + at[i];
            at[i]++;
            if (at[i] == runs[i].length) {
                heap[0] = heap[--size];
            }
            siftDown(heap, size, runs, at);
        }
        return order;
    }

    private static boolean before(int x, int y, long[][] runs, int[] at) {
        long a = runs[x][at[x]];
        long b = runs[y][at[y]];
        return a < b || a == b && x < y;
    }

    private static void siftUp(int[] heap, int i, long[][] runs, int[] at) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (!before(heap[i], heap[parent], runs, at)) {
                return;
            }
            int t = heap[i];
            heap[i] = heap[parent];
            heap[parent] = t;
            i = parent;
        }
    }

    private static void siftDown(int[] heap, int size, long[][] runs, int[] at) {
        int i = 0;
        while (true) {
            int least = i;
            for (int child = 2 * i + 1; child <= 2 * i + 2 && child < size; child++) {
                if (before(heap[child], heap[least], runs, at)) {
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

    /**
     * Returns the one of some runs of places, each at a place of its own,
     * whose place comes first, and the place of the next of the others, or
     * {@link Long#MAX_VALUE} if there is no other: the first takes its
     * places before that one.
     */
    static long[] least(int count, IntToLongFunction placeOf) {
        int best = 0;
        long limit = Long.MAX_VALUE;
        for (int i = 1; i < count; i++) {
            long p = placeOf.applyAsLong(i);
            long b = placeOf.applyAsLong(best);
            if (p < b) {
                limit = b;
                best = i;
            } else {
                limit = Math.min(limit, p);
            }
        }
        return new long[] {best, limit};
    }
}
