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
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.compiled.CompiledFilter;
import net.fyrql.exec.compiled.InterpretedKeys;
import net.fyrql.exec.compiled.JoinTable;
import net.fyrql.exec.compiled.KeyFunctions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Probes the {@link JoinTable} of a join with batches of its probe side, for
 * every type of join: the table of the whole build side, or that of one of
 * its partitions, see {@link GraceHashJoin}. A key with {@code NULL} matches
 * nothing. Without keys every build row is a candidate. The candidates of a
 * probe batch that the condition of the join, if any, holds for are its
 * matches; matches of one probe row come out in build order. The keys are
 * hashed and compared by {@link InterpretedKeys}, and the condition is
 * evaluated by the {@link Evaluator}, or, once {@link Codegen#due} says so
 * for the rows seen, by compiled code, whose keys hash alike.
 */
final class JoinProbe {

    private final PlanNode.Join join;
    private final AbstractOperator owner;
    private final int[] leftKeys;
    private final int[] rightKeys;
    private final Codegen codegen;
    private KeyFunctions keys = InterpretedKeys.INSTANCE;
    private boolean keysAsked;
    private CompiledFilter condition;
    private boolean conditionAsked;
    private long rowsSeen;
    /** The rows of the whole build side and whether one has a {@code NULL} key, or -1 for those of the table. */
    private long buildRows = -1;
    private boolean buildHasNullKey;
    /** The probe row of each row of the last output. */
    private int[] probeRows;

    /**
     * @param owner the operator that probes, whose label, schema and query count
     */
    JoinProbe(PlanNode.Join join, AbstractOperator owner, Codegen codegen) {
        this.join = join;
        this.owner = owner;
        this.leftKeys = join.leftKeys().stream().mapToInt(Integer::intValue).toArray();
        this.rightKeys = join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        this.codegen = codegen;
    }

    /** Counts rows of the build side, which tell when to compile. */
    void seen(long rows) {
        rowsSeen += rows;
    }

    /**
     * Tells the probe that the tables it probes hold a part of the build
     * side, whose rows and {@code NULL} keys a {@code MARK} join needs.
     */
    void wholeBuildSide(long rows, boolean hasNullKey) {
        buildRows = rows;
        buildHasNullKey = hasNullKey;
    }

    /** Returns the key functions, compiled ones once it is time, which hash alike. */
    KeyFunctions keys() {
        if (!keysAsked && leftKeys.length > 0 && codegen.due(rowsSeen)) {
            keysAsked = true;
            List<DataType> types = new ArrayList<>();
            for (int k : leftKeys) {
                types.add(join.left().schema().type(k));
            }
            KeyFunctions compiled = codegen.keys(types, owner.context());
            if (compiled != null) {
                keys = compiled;
            }
        }
        return keys;
    }

    /** Returns the row of the probe batch of each row of the last output of {@link #probe}. */
    int[] probeRows() {
        return probeRows;
    }

    /** Returns the hashes of the keys of a probe batch; only with keys. */
    int[] hash(Batch probe) {
        int[] hashes = new int[probe.rowCount()];
        keys().hash(probe, leftKeys, hashes);
        return hashes;
    }

    /**
     * Probes a table with a batch of the probe side and returns the output,
     * or {@code null} if there is none. Rows of the table that match are
     * marked for {@code RIGHT} and {@code FULL} joins.
     *
     * @param hashes the hashes of the keys of the probe rows, or {@code null} to hash them here
     * @throws IllegalStateException if a probe row of a {@code SINGLE} join matches more than one build row
     */
    Batch probe(Batch probe, int[] hashes, JoinTable table) {
        int n = probe.rowCount();
        JoinType type = join.type();
        Batch build = table.batch();
        if (leftKeys.length > 0 && hashes == null) {
            hashes = hash(probe);
        }
        rowsSeen += n;
        // Without a condition, the first candidate decides for the joins that only ask whether a row matches.
        boolean firstOnly = join.condition() == null && !type.keepsRight();
        KeyFunctions functions = keys;
        Pairs pairs = new Pairs(n);
        for (int i = 0; i < n; i++) {
            if (leftKeys.length == 0) {
                // Each probe row pairs with every build row, which may take long without a batch.
                owner.checkCancelled();
                int count = firstOnly ? Math.min(1, build.rowCount()) : build.rowCount();
                for (int j = 0; j < count; j++) {
                    pairs.add(i, j);
                }
            } else if (!JoinTable.hasNullKey(probe, leftKeys, i)) {
                // Matches come out in build order, so collect the chain and reverse it.
                int first = pairs.size;
                int h = hashes[i];
                for (int j = table.first(h); j >= 0; j = table.next(j)) {
                    if (table.hash(j) == h && functions.equal(probe, i, leftKeys, build, j, rightKeys)) {
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
            pairs = pairs.select(matching(probe, build, pairs));
        }
        if (type == JoinType.RIGHT || type == JoinType.FULL) {
            for (int p = 0; p < pairs.size; p++) {
                table.markMatched(pairs.buildRows[p]);
            }
        }
        if (type == JoinType.INNER || type == JoinType.RIGHT || type == JoinType.CROSS) {
            if (pairs.size == 0) {
                return null;
            }
            probeRows = Arrays.copyOf(pairs.probeRows, pairs.size);
            Batch rightPart = build.select(Arrays.copyOf(pairs.buildRows, pairs.size));
            return combine(probe.select(probeRows), rightPart, pairs.size);
        }
        long rows = buildRows >= 0 ? buildRows : build.rowCount();
        boolean nullKey = buildRows >= 0 ? buildHasNullKey : table.hasNullKey();
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
                        throw new IllegalStateException(JoinTable.MORE_THAN_ONE_ROW);
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
                    markNulls[i] = matches == 0 && rows > 0
                            && (nullKey || JoinTable.hasNullKey(probe, leftKeys, i));
                    out.add(i, -1);
                }
                default -> throw new IllegalStateException("Join type " + type);
            }
        }
        if (out.size == 0) {
            return null;
        }
        probeRows = Arrays.copyOf(out.probeRows, out.size);
        Batch leftPart = probe.select(probeRows);
        if (type == JoinType.MARK) {
            List<Vector> columns = new ArrayList<>(leftPart.columns());
            columns.add(BooleanVector.wrap(marks, markNulls));
            return new Batch(join.schema(), n, columns);
        }
        if (!type.keepsRight()) {
            return new Batch(join.schema(), out.size, leftPart.columns());
        }
        Batch rightPart = Batches.selectOrNull(build, Arrays.copyOf(out.buildRows, out.size));
        return combine(leftPart, rightPart, out.size);
    }

    /** Returns the indexes of the candidate pairs for which the condition is true. */
    private int[] matching(Batch probe, Batch build, Pairs pairs) {
        if (!conditionAsked && codegen.due(rowsSeen)) {
            conditionAsked = true;
            condition = codegen.filter(join.condition(), join.pairSchema(), owner.context());
        }
        Batch leftPart = probe.select(Arrays.copyOf(pairs.probeRows, pairs.size));
        Batch rightPart = build.select(Arrays.copyOf(pairs.buildRows, pairs.size));
        List<Vector> columns = new ArrayList<>(leftPart.columns());
        columns.addAll(rightPart.columns());
        Batch both = new Batch(join.pairSchema(), pairs.size, columns);
        return condition != null ? condition.select(both) : Evaluator.select(join.condition(), both);
    }

    private Batch combine(Batch leftPart, Batch rightPart, int rows) {
        List<Vector> columns = new ArrayList<>(leftPart.columns());
        columns.addAll(rightPart.columns());
        return new Batch(join.schema(), rows, columns);
    }

    private static void reverse(int[] a, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
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
