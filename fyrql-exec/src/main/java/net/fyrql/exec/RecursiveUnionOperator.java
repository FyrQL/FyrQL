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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs a {@link PlanNode.RecursiveUnion}: it passes on the rows of the
 * anchor, then opens the step anew for each round, over the rows of the
 * round before, and passes on its rows, until a round gives none. The rows
 * of a round go to a {@link CommonTableBuffer}, which the
 * {@link WorkingTableScanOperator}s of the next round read, in the budget
 * and spilled like the rows of a common table; the buffer of a round is
 * closed when the next round ends. Without {@code ALL}, a set of the rows
 * seen so far, in the budget, keeps every row from coming out twice and
 * from going to the next round again. The rows come out as they come, so
 * that a limit above ends a recursion that would not end by itself; each
 * round asks whether the query is canceled.
 */
final class RecursiveUnionOperator extends AbstractOperator {

    private static final long BYTES_PER_KEY = 96;

    private final PlanNode.RecursiveUnion node;
    private final Operator anchor;
    private final Supplier<Operator> step;
    private final MemoryBudget budget;
    private final SpillDirectory spill;
    private final QueryContext context;
    private final Set<RowKey> seen;
    private long reserved;
    /** The rows of the round before, which the step reads; {@code null} until the anchor is done. */
    private CommonTableBuffer current;
    /** The rows of the running round. */
    private CommonTableBuffer next;
    /** The step of the running round, {@code null} while the anchor runs. */
    private Operator running;
    private boolean anchorDone;
    private boolean done;
    private long rounds;
    private boolean closed;

    /**
     * @param step opens the step of a round, whose scans read {@link #rowsOfRoundBefore}
     */
    RecursiveUnionOperator(String label, PlanNode.RecursiveUnion node, Operator anchor, Supplier<Operator> step,
            MemoryBudget budget, SpillDirectory spill, QueryContext context) {
        super(label, node.schema());
        this.node = node;
        this.anchor = anchor;
        this.step = step;
        this.budget = budget;
        this.spill = spill;
        this.context = context;
        this.seen = node.all() ? null : new HashSet<>();
        this.next = newBuffer();
    }

    private CommonTableBuffer newBuffer() {
        return new CommonTableBuffer(label(), node.schema(), budget, spill, context);
    }

    /** Returns the rows of the round before, which the scans of the working table of the running round read. */
    CommonTableBuffer rowsOfRoundBefore() {
        if (current == null) {
            throw new IllegalStateException("The working table of " + label() + " is read before its first round");
        }
        return current;
    }

    /** Returns the rounds of the step run so far. */
    long rounds() {
        return rounds;
    }

    @Override
    Batch compute() {
        while (!done) {
            Batch b = anchorDone ? running.next() : anchor.next();
            if (b == null) {
                endRound();
                continue;
            }
            b = unseen(b);
            if (b == null) {
                continue;
            }
            next.add(b);
            return b;
        }
        return null;
    }

    /** Ends the anchor or a round; the next round starts if this one gave rows. */
    private void endRound() {
        if (anchorDone) {
            running.close();
            running = null;
        } else {
            anchor.close();
            anchorDone = true;
        }
        next.finish();
        if (current != null) {
            current.close();
        }
        current = next;
        next = null;
        if (current.rows() == 0) {
            done = true;
            return;
        }
        context.check(label());
        next = newBuffer();
        rounds++;
        running = step.get();
    }

    /** The rows of a batch not seen before, or {@code null} if none; the batch as it is with {@code ALL}. */
    private Batch unseen(Batch b) {
        if (seen == null) {
            return b.rowCount() == 0 ? null : b;
        }
        int[] rows = new int[b.rowCount()];
        int n = 0;
        for (int r = 0; r < b.rowCount(); r++) {
            if (seen.add(RowKey.of(b, r))) {
                budget.reserve(BYTES_PER_KEY, label());
                reserved += BYTES_PER_KEY;
                rows[n++] = r;
            }
        }
        if (n == 0) {
            return null;
        }
        return n == b.rowCount() ? b : b.select(Arrays.copyOf(rows, n));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (running != null) {
                running.close();
            }
            if (!anchorDone) {
                anchor.close();
            }
        } finally {
            if (current != null) {
                current.close();
            }
            if (next != null) {
                next.close();
            }
            if (seen != null) {
                seen.clear();
            }
            budget.release(reserved);
            reserved = 0;
        }
    }
}
