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
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.compiled.JoinTable;
import net.fyrql.exec.compiled.KeyFunctions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A hash join: the right input is the build side, materialized into a
 * {@link JoinTable}; the left input is probed batch by batch, see
 * {@link JoinProbe}. Without keys a probe batch is probed in steps that
 * keep its pairs within {@link JoinTable#MAX_PAIRS}. {@code RIGHT} and
 * {@code FULL} joins emit the unmatched build rows after the probe side is
 * exhausted. With a directory to spill to and a limited budget, a join with
 * keys whose build side does not fit in half of the budget spills both
 * sides to disk and joins them partition by partition, see
 * {@link GraceHashJoin}. A build side that a segment reads is hashed by its
 * workers, see {@link JoinBuild}. In a copy of a chain of a segment, the
 * join probes the table the segment shares among its workers, which emits
 * the unmatched build rows itself.
 */
final class HashJoinOperator extends AbstractOperator {

    /** The error of a {@code SINGLE} join whose left row matches more than one right row. */
    static final String MORE_THAN_ONE_ROW = JoinTable.MORE_THAN_ONE_ROW;

    private final PlanNode.Join join;
    private final Operator left;
    private final Operator right;
    private final MemoryBudget budget;
    private final int[] leftKeys;
    private final int[] rightKeys;
    private final Codegen codegen;
    private final JoinProbe probe;
    private final int batchSize;
    private final boolean ordered;
    /** The directory to spill to, or {@code null}. */
    private final SpillDirectory spill;

    private boolean built;
    private JoinSide side;
    private JoinTable table;
    private Batch build;
    private GraceHashJoin grace;
    /** The steps of a probe batch that pairs with too many build rows at once, still to be probed. */
    private final ArrayDeque<Batch> steps = new ArrayDeque<>();
    private boolean probeDone;
    private boolean leftoversDone;
    private final AtomicLong reserved = new AtomicLong();

    /** A join that does not spill. */
    HashJoinOperator(String label, PlanNode.Join join, Operator left, Operator right, MemoryBudget budget,
            Codegen codegen) {
        this(label, join, left, right, budget, codegen, 0, true, null);
    }

    /**
     * @param ordered whether the output comes in the order of the join in memory once it spills
     * @param spill   the directory to spill to when the budget is exhausted, or {@code null} to fail then
     */
    HashJoinOperator(String label, PlanNode.Join join, Operator left, Operator right, MemoryBudget budget,
            Codegen codegen, int batchSize, boolean ordered, SpillDirectory spill) {
        super(label, join.schema());
        this.join = join;
        this.left = left;
        this.right = right;
        this.budget = budget;
        this.leftKeys = join.leftKeys().stream().mapToInt(Integer::intValue).toArray();
        this.rightKeys = join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        this.codegen = codegen;
        this.probe = new JoinProbe(join, this, codegen);
        this.batchSize = batchSize;
        this.ordered = ordered;
        this.spill = spill;
    }

    /** A join that probes a table built already, which emits no unmatched build rows. */
    HashJoinOperator(String label, PlanNode.Join join, Operator left, JoinTable table, Codegen codegen) {
        this(label, join, left, new JoinSide.Table(table), false, MemoryBudget.unlimited(), codegen, 0, true, null);
    }

    /**
     * A join over a build side read already, a table in memory or rows on
     * disk, which closing the join deletes; the owner of a table gives back
     * its memory.
     *
     * @param leftovers whether the join emits the unmatched build rows of a {@code RIGHT} or {@code FULL} join
     */
    HashJoinOperator(String label, PlanNode.Join join, Operator left, JoinSide side, boolean leftovers,
            MemoryBudget budget, Codegen codegen, int batchSize, boolean ordered, SpillDirectory spill) {
        this(label, join, left, null, budget, codegen, batchSize, ordered, spill);
        this.side = side;
        if (side instanceof JoinSide.Table t) {
            this.table = t.table();
            this.build = table.batch();
        }
        this.built = true;
        this.leftoversDone = !leftovers;
        start();
    }

    @Override
    Batch compute() {
        if (!built) {
            built = true;
            buildTable();
            start();
        }
        if (side instanceof JoinSide.Spilled spilled) {
            if (grace == null) {
                grace = new GraceHashJoin(this, join, probe, spilled, left, budget, spill, batchSize, ordered);
            }
            return grace.next();
        }
        while (!probeDone) {
            Batch b = steps.poll();
            if (b == null) {
                b = left.next();
                if (b == null) {
                    probeDone = true;
                    break;
                }
                if (leftKeys.length == 0 && b.rowCount() > JoinTable.stepRows(pairsPerRow())) {
                    steps.addAll(Batches.split(b, JoinTable.stepRows(pairsPerRow())));
                    b = steps.poll();
                }
            } else {
                checkCancelled();
            }
            Batch out = probe.probe(b, null, table);
            if (out != null) {
                return out;
            }
        }
        if (!leftoversDone) {
            leftoversDone = true;
            if (join.type() == JoinType.RIGHT || join.type() == JoinType.FULL) {
                return table.leftovers(join.left().schema(), schema());
            }
        }
        return null;
    }

    /** The pairs a probe row forms without keys: every build row, or one for a join that asks whether a row matches. */
    private long pairsPerRow() {
        return join.condition() == null && !join.type().keepsRight() ? Math.min(1, build.rowCount())
                : build.rowCount();
    }

    /** Ends the join before its probe side is read if it can have no output. */
    private void start() {
        JoinType type = join.type();
        if (side.rowCount() == 0 && (type == JoinType.INNER || type == JoinType.SEMI)) {
            // As DuckDB's hash join: without build rows these joins have no output, and the probe side is not
            // read. A cross product reads it, and DuckDB builds a right join on its left side.
            probeDone = true;
        }
    }

    private void buildTable() {
        if (JoinBuild.spills(join, budget, spill)) {
            side = JoinBuild.side(right, join, label(), budget, spill, codegen, reserved, context());
            probe.seen(side.rowCount());
            probe.keys();
            if (side instanceof JoinSide.Table t) {
                table = t.table();
                build = table.batch();
            }
            return;
        } else if (right instanceof ParallelSegment) {
            table = JoinBuild.read(right, join, label(), budget, codegen, reserved, context());
            probe.seen(table.rowCount());
            probe.keys();
        } else {
            List<Batch> batches = new ArrayList<>();
            for (Batch b = right.next(); b != null; b = right.next()) {
                long bytes = Batches.estimateBytes(b);
                budget.reserve(bytes, label());
                reserved.addAndGet(bytes);
                batches.add(b);
                probe.seen(b.rowCount());
            }
            KeyFunctions keys = probe.keys();
            table = JoinBuild.table(batches, rightKeys.length > 0, label(), budget, reserved,
                    () -> new JoinTable(join.right().schema(), batches, rightKeys, keys));
        }
        side = new JoinSide.Table(table);
        build = table.batch();
    }

    @Override
    public void close() {
        left.close();
        if (right != null) {
            right.close();
        }
        if (grace != null) {
            grace.close();
        } else if (side != null) {
            side.close();
        }
        budget.release(reserved.getAndSet(0));
    }
}
