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
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.compiled.InterpretedKeys;
import net.fyrql.exec.compiled.JoinTable;
import net.fyrql.exec.compiled.KeyFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads the build side of a join into a {@link JoinTable}, each batch
 * hashed where it is read: by the workers of a segment, which collects the
 * batches in the order of its morsels, see {@link ParallelSegment#collect},
 * or else on the thread of the query. The table is the one that hashing
 * the concatenated batches would give, since every key function hashes
 * alike; each reader switches to compiled key functions once
 * {@link Codegen#due} says so for the rows it has hashed. The memory of
 * each batch, and then of the table, is reserved under the label of the
 * join.
 */
final class JoinBuild {

    /** A batch of the build side and the hashes of its keys, {@code null} without keys. */
    private record Part(Batch batch, int[] hashes) {
    }

    private JoinBuild() {
    }

    /**
     * Reads the right input of a join into its table.
     *
     * @param right    the operator of the right input
     * @param label    the label of the join
     * @param reserved counts the bytes reserved in the budget, which the caller releases
     * @param context  the context of the query, which counts the compiled key functions
     */
    static JoinTable read(Operator right, PlanNode.Join join, String label, MemoryBudget budget, Codegen codegen,
            AtomicLong reserved, QueryContext context) {
        int[] keys = join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        List<DataType> types = new ArrayList<>();
        for (int k : join.leftKeys()) {
            types.add(join.left().schema().type(k));
        }
        List<Part> parts;
        if (right instanceof ParallelSegment segment) {
            parts = segment.collect(() -> new Hasher(keys, types, label, budget, codegen, reserved, context));
        } else {
            Hasher hasher = new Hasher(keys, types, label, budget, codegen, reserved, context);
            parts = new ArrayList<>();
            for (Batch b = right.next(); b != null; b = right.next()) {
                parts.add(hasher.apply(b));
            }
        }
        List<Batch> batches = new ArrayList<>(parts.size());
        List<int[]> hashes = new ArrayList<>(parts.size());
        for (Part p : parts) {
            batches.add(p.batch());
            hashes.add(p.hashes());
        }
        return table(batches, keys.length > 0, label, budget, reserved,
                () -> new JoinTable(join.right().schema(), batches, hashes, keys));
    }

    /**
     * Builds a table over batches whose memory is reserved already. Before,
     * it reserves what the table holds besides them, and their copy into one
     * batch if there are several; after, it gives back the batches that the
     * copy replaces.
     *
     * @param keyed tells whether the join has keys
     */
    static JoinTable table(List<Batch> batches, boolean keyed, String label, MemoryBudget budget,
            AtomicLong reserved, Supplier<JoinTable> build) {
        int rows = 0;
        long copy = 0;
        for (Batch b : batches) {
            rows += b.rowCount();
            copy += batches.size() > 1 ? Batches.estimateBytes(b) : 0;
        }
        long bytes = copy + JoinTable.indexBytes(rows, keyed);
        budget.reserve(bytes, label);
        reserved.addAndGet(bytes);
        JoinTable table = build.get();
        budget.release(copy);
        reserved.addAndGet(-copy);
        return table;
    }

    /** Reserves the memory of the batches of one reader and hashes their keys. */
    private static final class Hasher implements Function<Batch, Part> {
        private final int[] keys;
        private final List<DataType> types;
        private final String label;
        private final MemoryBudget budget;
        private final Codegen codegen;
        private final AtomicLong reserved;
        private final QueryContext context;
        private KeyFunctions functions = InterpretedKeys.INSTANCE;
        private boolean asked;
        private long rows;

        Hasher(int[] keys, List<DataType> types, String label, MemoryBudget budget, Codegen codegen,
                AtomicLong reserved, QueryContext context) {
            this.keys = keys;
            this.types = types;
            this.label = label;
            this.budget = budget;
            this.codegen = codegen;
            this.reserved = reserved;
            this.context = context;
        }

        @Override
        public Part apply(Batch b) {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, label);
            reserved.addAndGet(bytes);
            if (keys.length == 0) {
                return new Part(b, null);
            }
            if (!asked && codegen.due(rows)) {
                asked = true;
                KeyFunctions compiled = codegen.keys(types, context);
                if (compiled != null) {
                    functions = compiled;
                }
            }
            rows += b.rowCount();
            int[] hashes = new int[b.rowCount()];
            functions.hash(b, keys, hashes);
            return new Part(b, hashes);
        }
    }
}
