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
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Runs a {@link PlanNode.LateralJoin}: for each batch of the left input, it
 * runs the right input once per distinct combination of the left columns
 * the right one reads, with their values in place of its
 * {@link Expression.Outer}s, as literals, so that its compiled code comes
 * from the cache of the compiler; then it pairs each left row, in their
 * order, with the rows of its combination, and a left row of a
 * {@code LEFT} join without any with {@code NULL}s. The rows of the right
 * input of a batch take the budget until the batch is joined.
 */
final class LateralJoinOperator extends AbstractOperator {

    /** Runs the right input with the values of a left row in place and returns its rows. */
    interface Rights {
        List<Batch> rows(UnaryOperator<Expression> values);
    }

    private final PlanNode.LateralJoin node;
    private final Operator left;
    private final Rights rights;
    private final MemoryBudget budget;
    private final int batchSize;
    /** The left columns the right input reads, and for each left column its place among them. */
    private final List<Integer> slots = new ArrayList<>();
    private final int[] placeOf;
    private final Schema rightSchema;
    private final Deque<Batch> pending = new ArrayDeque<>();
    private long reserved;

    LateralJoinOperator(String label, PlanNode.LateralJoin node, Operator left, Rights rights, MemoryBudget budget,
            int batchSize) {
        super(label, node.schema());
        this.node = node;
        this.left = left;
        this.rights = rights;
        this.budget = budget;
        this.batchSize = batchSize;
        this.rightSchema = node.right().schema();
        BitSet used = Plans.outerSlots(node.right(), node.id());
        this.placeOf = new int[node.left().schema().size()];
        Arrays.fill(placeOf, -1);
        for (int s = used.nextSetBit(0); s >= 0; s = used.nextSetBit(s + 1)) {
            placeOf[s] = slots.size();
            slots.add(s);
        }
    }

    @Override
    Batch compute() {
        while (pending.isEmpty()) {
            Batch b = left.next();
            if (b == null) {
                return null;
            }
            join(b);
        }
        return pending.poll();
    }

    /** Joins a batch of left rows, whose output batches wait in {@link #pending}. */
    private void join(Batch b) {
        int rows = b.rowCount();
        if (rows == 0) {
            return;
        }
        // The distinct combinations of the values the right input reads, in the order of their first rows.
        Map<RowKey, Integer> combinations = new LinkedHashMap<>();
        List<Integer> firstRows = new ArrayList<>();
        int[] combinationOf = new int[rows];
        for (int r = 0; r < rows; r++) {
            Integer c = combinations.putIfAbsent(RowKey.of(b, slots, r), combinations.size());
            if (c == null) {
                c = combinations.size() - 1;
                firstRows.add(r);
            }
            combinationOf[r] = c;
        }
        List<Batch> all = new ArrayList<>();
        int[] offsets = new int[combinations.size()];
        int[] counts = new int[combinations.size()];
        int offset = 0;
        try {
            for (int c = 0; c < firstRows.size(); c++) {
                Object[] values = new Object[slots.size()];
                for (int k = 0; k < values.length; k++) {
                    values[k] = b.column(slots.get(k)).get(firstRows.get(c));
                }
                List<Batch> batches = rights.rows(e -> Expressions.transform(e, x -> x instanceof Expression.Outer o
                        && o.id() == node.id() ? new Expression.Literal(o.type(), values[placeOf[o.slot()]]) : x));
                offsets[c] = offset;
                for (Batch r : batches) {
                    long bytes = Batches.estimateBytes(r);
                    budget.reserve(bytes, label());
                    reserved += bytes;
                    all.add(r);
                    offset += r.rowCount();
                }
                counts[c] = offset - offsets[c];
            }
            // One row of NULLs at the end, for the left rows of a LEFT join without a match.
            List<Vector> nulls = new ArrayList<>();
            for (int i = 0; i < rightSchema.size(); i++) {
                nulls.add(Vectors.nulls(rightSchema.type(i), 1));
            }
            all.add(new Batch(rightSchema, 1, nulls));
            Batch right = Batches.concat(rightSchema, all);
            int nullRow = offset;
            boolean outer = node.type() == PlanNode.JoinType.LEFT;
            int[] leftRows = new int[batchSize];
            int[] rightRows = new int[batchSize];
            int n = 0;
            for (int r = 0; r < rows; r++) {
                int c = combinationOf[r];
                if (counts[c] == 0 && !outer) {
                    continue;
                }
                int matches = Math.max(1, counts[c]);
                for (int j = 0; j < matches; j++) {
                    leftRows[n] = r;
                    rightRows[n] = counts[c] == 0 ? nullRow : offsets[c] + j;
                    if (++n == batchSize) {
                        emit(b, right, leftRows, rightRows, n);
                        n = 0;
                    }
                }
            }
            if (n > 0) {
                emit(b, right, leftRows, rightRows, n);
            }
        } finally {
            budget.release(reserved);
            reserved = 0;
        }
    }

    private void emit(Batch leftBatch, Batch right, int[] leftRows, int[] rightRows, int n) {
        Batch l = leftBatch.select(Arrays.copyOf(leftRows, n));
        Batch r = right.select(Arrays.copyOf(rightRows, n));
        List<Vector> vectors = new ArrayList<>(l.columns());
        vectors.addAll(r.columns());
        pending.add(new Batch(schema(), n, vectors));
    }

    @Override
    public void close() {
        pending.clear();
        budget.release(reserved);
        reserved = 0;
        left.close();
    }
}
