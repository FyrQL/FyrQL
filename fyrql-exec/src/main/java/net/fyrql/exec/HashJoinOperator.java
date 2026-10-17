/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.spi.CompiledFilter;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.JoinTable;
import net.fyrql.exec.spi.KeyFunctions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A hash join: the right input is the build side, materialized into a
 * {@link JoinTable}; the left input is probed batch by batch.
 * A key with {@code NULL} matches nothing. Without keys every build row is
 * a candidate, and a probe batch is probed in steps that keep its pairs
 * within {@link JoinTable#MAX_PAIRS}. The candidates of a probe batch that
 * the condition of the join, if any, holds for are its matches. Matches of one probe row come
 * out in build order. {@code RIGHT} and {@code FULL} joins emit the
 * unmatched build rows after the probe side is exhausted. The keys are
 * hashed and compared by {@link InterpretedKeys}, and the condition is
 * evaluated by the {@link Evaluator}, or, once {@link Codegen#due} says so,
 * by compiled code, whose keys hash alike.
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
    private KeyFunctions keys = InterpretedKeys.INSTANCE;
    private boolean keysAsked;
    private CompiledFilter condition;
    private boolean conditionAsked;
    private long rowsSeen;

    private JoinTable table;
    private Batch build;
    /** The steps of a probe batch that pairs with too many build rows at once, still to be probed. */
    private final ArrayDeque<Batch> steps = new ArrayDeque<>();
    private boolean probeDone;
    private boolean leftoversDone;
    private long reserved;

    HashJoinOperator(String label, PlanNode.Join join, Operator left, Operator right, MemoryBudget budget,
            Codegen codegen) {
        super(label, join.schema());
        this.join = join;
        this.left = left;
        this.right = right;
        this.budget = budget;
        this.leftKeys = join.leftKeys().stream().mapToInt(Integer::intValue).toArray();
        this.rightKeys = join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        this.codegen = codegen;
    }

    /** Switches to compiled key functions when it is time, which keeps the hashes of the table valid. */
    private void compileKeys() {
        if (!keysAsked && leftKeys.length > 0 && codegen.due(rowsSeen)) {
            keysAsked = true;
            List<DataType> types = new ArrayList<>();
            for (int k : leftKeys) {
                types.add(join.left().schema().type(k));
            }
            KeyFunctions compiled = codegen.keys(types);
            if (compiled != null) {
                keys = compiled;
            }
        }
    }

    @Override
    Batch compute() {
        if (build == null) {
            buildTable();
            JoinType type = join.type();
            if (build.rowCount() == 0 && (type == JoinType.INNER || type == JoinType.SEMI)) {
                // As DuckDB's hash join: without build rows these joins have no output, and the probe side is not
                // read. A cross product reads it, and DuckDB builds a right join on its left side.
                probeDone = true;
            }
        }
        while (!probeDone) {
            Batch probe = steps.poll();
            if (probe == null) {
                probe = left.next();
                if (probe == null) {
                    probeDone = true;
                    break;
                }
                if (leftKeys.length == 0 && probe.rowCount() > JoinTable.stepRows(pairsPerRow())) {
                    steps.addAll(Batches.split(probe, JoinTable.stepRows(pairsPerRow())));
                    probe = steps.poll();
                }
            } else {
                checkCancelled();
            }
            Batch out = probe(probe);
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

    private void buildTable() {
        List<Batch> batches = new ArrayList<>();
        for (Batch b = right.next(); b != null; b = right.next()) {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, label());
            reserved += bytes;
            batches.add(b);
            rowsSeen += b.rowCount();
        }
        compileKeys();
        table = new JoinTable(join.right().schema(), batches, rightKeys, keys);
        build = table.batch();
    }

    private Batch probe(Batch probe) {
        int n = probe.rowCount();
        JoinType type = join.type();
        int[] hashes = null;
        if (leftKeys.length > 0) {
            compileKeys();
            hashes = new int[n];
            keys.hash(probe, leftKeys, hashes);
        }
        rowsSeen += n;
        // Without a condition, the first candidate decides for the joins that only ask whether a row matches.
        boolean firstOnly = join.condition() == null && !type.keepsRight();
        Pairs pairs = new Pairs(n);
        for (int i = 0; i < n; i++) {
            if (hashes == null) {
                // Each probe row pairs with every build row, which may take long without a batch.
                checkCancelled();
                int count = firstOnly ? Math.min(1, build.rowCount()) : build.rowCount();
                for (int j = 0; j < count; j++) {
                    pairs.add(i, j);
                }
            } else if (!JoinTable.hasNullKey(probe, leftKeys, i)) {
                // Matches come out in build order, so collect the chain and reverse it.
                int first = pairs.size;
                int h = hashes[i];
                for (int j = table.first(h); j >= 0; j = table.next(j)) {
                    if (table.hash(j) == h && keys.equal(probe, i, leftKeys, build, j, rightKeys)) {
                        pairs.add(i, j);
                        if (firstOnly) {
                            break;
                        }
                    }
                }
                reverse(pairs.buildRows, first, pairs.size);
            }
        }
        if (join.condition() != null && pairs.size > 0) {
            pairs = pairs.select(matching(probe, pairs));
        }
        for (int p = 0; p < pairs.size; p++) {
            table.markMatched(pairs.buildRows[p]);
        }
        if (type == JoinType.INNER || type == JoinType.RIGHT || type == JoinType.CROSS) {
            return pairs.size == 0 ? null : combine(probe, pairs);
        }
        Pairs out = new Pairs(n);
        boolean[] marks = type == JoinType.MARK ? new boolean[n] : null;
        boolean[] markNulls = type == JoinType.MARK ? new boolean[n] : null;
        int p = 0;
        for (int i = 0; i < n; i++) {
            int start = p;
            while (p < pairs.size && pairs.probeRows[p] == i) {
                p++;
            }
            int matches = p - start;
            switch (type) {
                case LEFT, FULL -> {
                    for (int q = start; q < p; q++) {
                        out.add(i, pairs.buildRows[q]);
                    }
                    if (matches == 0) {
                        out.add(i, -1);
                    }
                }
                case SINGLE -> {
                    if (matches > 1) {
                        throw new IllegalStateException(MORE_THAN_ONE_ROW);
                    }
                    out.add(i, matches == 1 ? pairs.buildRows[start] : -1);
                }
                case SEMI -> {
                    if (matches > 0) {
                        out.add(i, -1);
                    }
                }
                case ANTI -> {
                    if (matches == 0) {
                        out.add(i, -1);
                    }
                }
                case MARK -> {
                    marks[i] = matches > 0;
                    markNulls[i] = matches == 0 && build.rowCount() > 0
                            && (table.hasNullKey() || JoinTable.hasNullKey(probe, leftKeys, i));
                    out.add(i, -1);
                }
                default -> throw new IllegalStateException("Join type " + type);
            }
        }
        if (out.size == 0) {
            return null;
        }
        Batch leftPart = probe.select(Arrays.copyOf(out.probeRows, out.size));
        if (type == JoinType.MARK) {
            List<Vector> columns = new ArrayList<>(leftPart.columns());
            columns.add(BooleanVector.wrap(marks, markNulls));
            return new Batch(schema(), n, columns);
        }
        if (!type.keepsRight()) {
            return new Batch(schema(), out.size, leftPart.columns());
        }
        Batch rightPart = Batches.selectOrNull(build, Arrays.copyOf(out.buildRows, out.size));
        return combine(leftPart, rightPart, out.size);
    }

    /** Returns the indexes of the candidate pairs for which the condition is true. */
    private int[] matching(Batch probe, Pairs pairs) {
        if (!conditionAsked && codegen.due(rowsSeen)) {
            conditionAsked = true;
            condition = codegen.filter(join.condition(), join.pairSchema());
        }
        Batch leftPart = probe.select(Arrays.copyOf(pairs.probeRows, pairs.size));
        Batch rightPart = build.select(Arrays.copyOf(pairs.buildRows, pairs.size));
        List<Vector> columns = new ArrayList<>(leftPart.columns());
        columns.addAll(rightPart.columns());
        Batch both = new Batch(join.pairSchema(), pairs.size, columns);
        return condition != null ? condition.select(both) : Evaluator.select(join.condition(), both);
    }

    private Batch combine(Batch probe, Pairs pairs) {
        Batch leftPart = probe.select(Arrays.copyOf(pairs.probeRows, pairs.size));
        Batch rightPart = build.select(Arrays.copyOf(pairs.buildRows, pairs.size));
        return combine(leftPart, rightPart, pairs.size);
    }

    private Batch combine(Batch leftPart, Batch rightPart, int rows) {
        List<Vector> columns = new ArrayList<>(leftPart.columns());
        columns.addAll(rightPart.columns());
        return new Batch(schema(), rows, columns);
    }

    private static void reverse(int[] a, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    @Override
    public void close() {
        left.close();
        right.close();
        budget.release(reserved);
        reserved = 0;
    }

    /** Pairs of a probe row and a build row, in the order of the probe rows; -1 stands for no build row. */
    private static final class Pairs {
        int[] probeRows;
        int[] buildRows;
        int size;

        Pairs(int capacity) {
            probeRows = new int[Math.max(capacity, 16)];
            buildRows = new int[probeRows.length];
        }

        void add(int probeRow, int buildRow) {
            if (size == probeRows.length) {
                probeRows = Arrays.copyOf(probeRows, size * 2);
                buildRows = Arrays.copyOf(buildRows, size * 2);
            }
            probeRows[size] = probeRow;
            buildRows[size++] = buildRow;
        }

        Pairs select(int[] indexes) {
            Pairs result = new Pairs(indexes.length);
            for (int i : indexes) {
                result.add(probeRows[i], buildRows[i]);
            }
            return result;
        }
    }
}
