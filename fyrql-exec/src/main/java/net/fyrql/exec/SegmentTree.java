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

import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * An aggregate over any range of a run of rows in logarithmic time, for
 * the frames of a window: each node accumulates {@link #FAN_OUT} nodes of
 * the level below, the nodes of the lowest level as many rows. A range
 * takes the rows and nodes at its edges, level by level, and the nodes
 * that cover its middle, and merges them from left to right, so that
 * {@code first} and {@code last} see the rows in their order. The nodes
 * are {@link ValueAccumulator}s of a {@link ValueAccumulator#mergeable}
 * aggregate. With a filter, the tree takes only the rows whose filter is
 * true.
 */
final class SegmentTree {

    /** The number of rows or nodes a node covers, as in DuckDB. */
    static final int FAN_OUT = 16;

    private final Batch batch;
    private final int begin;
    private final Vector filter;
    private final Supplier<ValueAccumulator> empty;
    /** The nodes by level, from the lowest one up to the level of one node. */
    private final List<ValueAccumulator[]> levels = new ArrayList<>();
    /**
     * The right edges of the range of {@link #aggregate}, three ints each:
     * the level, from and to; with them, a tree serves one thread at a time.
     */
    private final int[] right;

    /**
     * Builds the tree over the rows {@code [begin, end)} of a batch.
     *
     * @param filter the {@code BOOLEAN} column of the rows to take, or {@code null} for all
     * @param empty returns an empty accumulator of the aggregate
     */
    SegmentTree(Batch batch, int begin, int end, Vector filter, Supplier<ValueAccumulator> empty) {
        this.batch = batch;
        this.begin = begin;
        this.filter = filter;
        this.empty = empty;
        int below = end - begin;
        while (below > 1) {
            ValueAccumulator[] level = new ValueAccumulator[(below + FAN_OUT - 1) / FAN_OUT];
            for (int i = 0; i < level.length; i++) {
                ValueAccumulator node = empty.get();
                add(node, levels.size(), i * FAN_OUT, Math.min(below, (i + 1) * FAN_OUT));
                level[i] = node;
            }
            levels.add(level);
            below = level.length;
        }
        right = new int[3 * levels.size()];
    }

    /** Returns the number of nodes. */
    int nodes() {
        int n = 0;
        for (ValueAccumulator[] level : levels) {
            n += level.length;
        }
        return n;
    }

    /**
     * Adds the rows {@code [from, to)} of the batch, which lie within the
     * tree, to an accumulator, after what it has taken.
     */
    void aggregate(ValueAccumulator result, int from, int to) {
        int s = from - begin;
        int e = to - begin;
        // The right edges come after the middle, the lower ones last.
        int edges = 0;
        int level = 0;
        while (s < e) {
            int parentStart = (s + FAN_OUT - 1) / FAN_OUT;
            int parentEnd = e / FAN_OUT;
            if (level == levels.size() || parentStart >= parentEnd) {
                add(result, level, s, e);
                break;
            }
            add(result, level, s, parentStart * FAN_OUT);
            right[edges++] = level;
            right[edges++] = parentEnd * FAN_OUT;
            right[edges++] = e;
            s = parentStart;
            e = parentEnd;
            level++;
        }
        for (int i = edges - 3; i >= 0; i -= 3) {
            add(result, right[i], right[i + 1], right[i + 2]);
        }
    }

    /** Adds the items {@code [from, to)} of a level: rows at level 0, else nodes of the level below. */
    private void add(ValueAccumulator into, int level, int from, int to) {
        if (level == 0) {
            for (int i = from; i < to; i++) {
                if (takes(filter, begin + i)) {
                    into.add(batch, begin + i);
                }
            }
        } else {
            ValueAccumulator[] nodes = levels.get(level - 1);
            for (int i = from; i < to; i++) {
                into.merge(nodes[i]);
            }
        }
    }

    /** Tells whether a filter, if there is one, takes a row: only if it is true. */
    static boolean takes(Vector filter, int row) {
        return filter == null || !filter.isNull(row) && (Boolean) filter.get(row);
    }
}
