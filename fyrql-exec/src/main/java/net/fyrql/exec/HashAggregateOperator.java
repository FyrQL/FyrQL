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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.KeyFunctions;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Groups the input by its key columns and accumulates the aggregates per
 * group; groups come out in the order of their first row. Sums of integers
 * are exact 128-bit values, sums of decimals keep their scale, and
 * {@code avg} is a double. An aggregate with an {@code ORDER BY} keeps its
 * values with their sort keys and accumulates them in that order at the
 * end; the sort is stable.
 * <p>
 * The groups live in a {@link GroupTable}, whose keys are hashed and
 * compared by {@link InterpretedKeys}, or, once {@link Codegen#due} says so,
 * by compiled key functions. A key of a group comes out as its first row
 * has it, as in DuckDB, e.g. {@code -0.0} for a group of {@code -0.0} and
 * {@code 0.0} in this order.
 * <p>
 * Each aggregate keeps an {@link AggregateState} over all groups: one
 * without boxing where {@link AggregateState#of} has one, otherwise a
 * {@link ValueAccumulator} per group. The results are computed group by group,
 * so that the first group whose result fails is the one that fails the
 * query. The budget takes the bytes of the new groups of a batch at once.
 */
final class HashAggregateOperator extends AbstractOperator {

    private static final long BYTES_PER_GROUP = 128;

    private final PlanNode.Aggregate aggregate;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private final Schema inputSchema;
    private final Codegen codegen;
    private final boolean unboxed;
    private List<Batch> output;
    private long reserved;

    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen) {
        this(label, aggregate, input, budget, batchSize, codegen, true);
    }

    /** With {@code unboxed} false, every aggregate keeps accumulator objects, which the tests compare against. */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, boolean unboxed) {
        super(label, aggregate.schema());
        this.aggregate = aggregate;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
        this.inputSchema = aggregate.input().schema();
        this.codegen = codegen;
        this.unboxed = unboxed;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = aggregateAll();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> aggregateAll() {
        List<Integer> groupColumns = aggregate.groupColumns();
        int keyCount = groupColumns.size();
        List<AggregateCall> calls = aggregate.aggregates();
        AggregateState[] states = new AggregateState[calls.size()];
        for (int i = 0; i < states.length; i++) {
            AggregateState state = unboxed ? AggregateState.of(calls.get(i), inputSchema) : null;
            states[i] = state != null ? state : new Accumulators(calls.get(i));
        }
        GroupTable table = keyCount == 0 ? null : new GroupTable(groupColumns);
        // Without keys there is always the one group, even without rows.
        int groups = keyCount == 0 ? 1 : 0;
        int[] noKeys = new int[0];
        KeyFunctions keys = InterpretedKeys.INSTANCE;
        boolean keysAsked = false;
        long rowsSeen = 0;
        for (Batch b = input.next(); b != null; b = input.next()) {
            int n = b.rowCount();
            int[] groupOf;
            if (table == null) {
                if (noKeys.length < n) {
                    noKeys = new int[n];
                }
                groupOf = noKeys;
            } else {
                if (!keysAsked && codegen.due(rowsSeen)) {
                    keysAsked = true;
                    KeyFunctions compiled = codegen.keys(groupColumns.stream().map(inputSchema::type).toList());
                    if (compiled != null) {
                        keys = compiled;
                    }
                }
                rowsSeen += n;
                groupOf = table.groups(b, keys, stored -> reserve(Batches.estimateBytes(stored)));
                if (table.size() > groups) {
                    reserve(BYTES_PER_GROUP * (table.size() - groups));
                    groups = table.size();
                }
            }
            for (AggregateState state : states) {
                state.add(b, groupOf, n, groups);
            }
        }
        if (groups == 0) {
            return new ArrayList<>();
        }
        for (AggregateState state : states) {
            state.startResult(groups);
        }
        for (int g = 0; g < groups; g++) {
            if ((g & (CHECK_INTERVAL - 1)) == 0) {
                checkCancelled();
            }
            for (AggregateState state : states) {
                state.result(g);
            }
        }
        List<Vector> columns = new ArrayList<>(keyCount + states.length);
        for (int k = 0; k < keyCount; k++) {
            columns.add(table.keys(k));
        }
        for (AggregateState state : states) {
            columns.add(state.finishResult());
        }
        return new ArrayList<>(Batches.split(new Batch(schema(), groups, columns), batchSize));
    }

    private void reserve(long bytes) {
        budget.reserve(bytes, label());
        reserved += bytes;
    }

    /** The state of an aggregate as one accumulator object per group. */
    private final class Accumulators extends AggregateState {
        private final AggregateCall call;
        private final List<ValueAccumulator> groups = new ArrayList<>();
        private Vector.Builder out;

        Accumulators(AggregateCall call) {
            this.call = call;
        }

        private void grow(int n) {
            while (groups.size() < n) {
                groups.add(new ValueAccumulator(call, inputSchema, HashAggregateOperator.this::reserve));
            }
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int n) {
            grow(n);
            for (int row = 0; row < rows; row++) {
                groups.get(groupOf[row]).add(b, row);
            }
        }

        @Override
        void startResult(int n) {
            grow(n);
            out = Vectors.builder(call.type(inputSchema), n);
        }

        @Override
        void result(int group) {
            out.append(groups.get(group).result());
        }

        @Override
        Vector finishResult() {
            return out.build();
        }
    }

    /**
     * Converts a 128-bit sum to a double as DuckDB does: the low 64 bits as
     * an unsigned number plus the high 64 bits times 2^64, except that a
     * negative number above -2^64 becomes minus its magnitude less one,
     * minus one. Beyond 2^53 this rounds differently from
     * {@link BigInteger#doubleValue()} now and then.
     */
    static double toDouble(BigInteger value) {
        long lower = value.longValue();
        long upper = value.shiftRight(64).longValue();
        if (upper == -1) {
            return -unsignedToDouble(~lower) - 1;
        }
        return unsignedToDouble(lower) + upper * 0x1p64;
    }

    /** Converts an unsigned 64-bit number to the nearest double, ties to even. */
    private static double unsignedToDouble(long x) {
        if (x >= 0) {
            return x;
        }
        // Halve and keep the lowest bit as a sticky bit, so that the rounding stays exact.
        return ((x >>> 1) | (x & 1)) * 2.0;
    }

    @Override
    public void close() {
        input.close();
        budget.release(reserved);
        reserved = 0;
    }
}
