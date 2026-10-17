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
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Keys;
import net.fyrql.core.value.Order;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.KeyFunctions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * without boxing where {@link AggregateState#of} has one, otherwise an
 * accumulator object per group. The results are computed group by group,
 * so that the first group whose result fails is the one that fails the
 * query. The budget takes the bytes of the new groups of a batch at once.
 */
final class HashAggregateOperator extends AbstractOperator {

    private static final long BYTES_PER_GROUP = 128;
    private static final long BYTES_PER_VALUE = 32;

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
        private final List<Accumulator> groups = new ArrayList<>();
        private Vector.Builder out;

        Accumulators(AggregateCall call) {
            this.call = call;
        }

        private void grow(int n) {
            while (groups.size() < n) {
                groups.add(new Accumulator(call));
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

    private final class Accumulator {
        private final AggregateCall call;
        private final AggregateFunction function;
        private final DataType type;
        private final DataType resultType;
        private final Set<RowKey> seen;
        /** The values with their sort keys, for an ordered aggregate; {@code null} otherwise. */
        private final List<Object[]> ordered;
        private long count;
        private long longSum;
        private BigInteger bigSum;
        private double doubleSum;
        private BigDecimal decimalSum;
        private Object extreme;
        private boolean bool;
        private List<Object> values;

        Accumulator(AggregateCall call) {
            this.call = call;
            this.function = call.function();
            this.type = function == AggregateFunction.COUNT_STAR ? null : inputSchema.type(call.column());
            this.resultType = call.type(inputSchema);
            this.seen = call.distinct() ? new HashSet<>() : null;
            this.ordered = call.orderBy().isEmpty() ? null : new ArrayList<>();
            if (type instanceof Decimal d) {
                decimalSum = BigDecimal.valueOf(0, d.scale());
            }
            bool = function == AggregateFunction.BOOL_AND;
        }

        void add(Batch b, int row) {
            if (function == AggregateFunction.COUNT_STAR) {
                count++;
                return;
            }
            Vector v = b.column(call.column());
            if (v.isNull(row) && !function.keepsNulls()) {
                return;
            }
            Object value = v.get(row);
            if (seen != null && !seen.add(new RowKey(new Object[] {Keys.canonical(value)}))) {
                return;
            }
            if (ordered == null) {
                accept(value);
                return;
            }
            reserve(BYTES_PER_VALUE);
            Object[] entry = new Object[1 + call.orderBy().size()];
            entry[0] = value;
            for (int k = 0; k < call.orderBy().size(); k++) {
                entry[k + 1] = b.column(call.orderBy().get(k).column()).get(row);
            }
            ordered.add(entry);
        }

        /** Accumulates one value; {@code null} only for the functions that keep {@code NULL}s. */
        private void accept(Object value) {
            count++;
            switch (function) {
                case SUM, AVG -> {
                    if (type instanceof Decimal) {
                        decimalSum = decimalSum.add((BigDecimal) value);
                    } else if (type == DataType.HUGEINT) {
                        bigSum = (bigSum == null ? BigInteger.ZERO : bigSum).add((BigInteger) value);
                    } else if (type.isIntegral()) {
                        long x = ((Number) value).longValue();
                        if (bigSum != null) {
                            bigSum = bigSum.add(BigInteger.valueOf(x));
                        } else {
                            long sum = longSum + x;
                            if (((longSum ^ sum) & (x ^ sum)) < 0) {
                                bigSum = BigInteger.valueOf(longSum).add(BigInteger.valueOf(x));
                            } else {
                                longSum = sum;
                            }
                        }
                    } else {
                        doubleSum += ((Number) value).doubleValue();
                    }
                }
                case MIN -> extreme = extreme == null || Order.compare(value, extreme, type) < 0 ? value : extreme;
                case MAX -> extreme = extreme == null || Order.compare(value, extreme, type) > 0 ? value : extreme;
                case FIRST, ANY_VALUE -> extreme = count == 1 ? value : extreme;
                case LAST -> extreme = value;
                case BOOL_AND -> bool &= (Boolean) value;
                case BOOL_OR -> bool |= (Boolean) value;
                case STRING_AGG, LIST -> {
                    if (values == null) {
                        values = new ArrayList<>();
                    }
                    reserve(BYTES_PER_VALUE);
                    values.add(function == AggregateFunction.LIST ? value : Casts.format(value, type));
                }
                default -> {
                }
            }
        }

        Object result() {
            if (ordered != null) {
                ordered.sort(comparator());
                for (Object[] entry : ordered) {
                    accept(entry[0]);
                }
                ordered.clear();
            }
            return switch (function) {
                case COUNT_STAR, COUNT -> count;
                case SUM -> {
                    if (count == 0) {
                        yield null;
                    }
                    if (type instanceof Decimal) {
                        yield Values.toDecimal(decimalSum, (Decimal) resultType);
                    }
                    if (type.isIntegral()) {
                        yield Values.boxChecked(bigSum != null ? bigSum : BigInteger.valueOf(longSum), DataType.HUGEINT);
                    }
                    yield doubleSum;
                }
                case AVG -> {
                    if (count == 0) {
                        yield null;
                    }
                    // As DuckDB: the sum as a double, divided by the count and, for a decimal, its power of ten.
                    if (type instanceof Decimal d) {
                        yield toDouble(decimalSum.unscaledValue()) / (count * Math.pow(10, d.scale()));
                    }
                    if (type.isIntegral()) {
                        BigInteger sum = bigSum != null ? bigSum : BigInteger.valueOf(longSum);
                        if (type == DataType.HUGEINT) {
                            // DuckDB sums HUGEINTs in a HUGEINT, which overflows.
                            Values.boxChecked(sum, DataType.HUGEINT);
                        }
                        yield toDouble(sum) / count;
                    }
                    yield doubleSum / count;
                }
                case MIN, MAX, FIRST, LAST, ANY_VALUE -> extreme;
                case BOOL_AND, BOOL_OR -> count == 0 ? null : bool;
                case LIST -> values;
                case STRING_AGG -> values == null || call.separator() == null ? null
                        : String.join(call.separator(), values.stream().map(String.class::cast).toList());
            };
        }

        /** Orders the entries by their sort keys, with the direction and the place of {@code NULL} of each. */
        private Comparator<Object[]> comparator() {
            List<SortKey> keys = call.orderBy();
            return (x, y) -> {
                for (int k = 0; k < keys.size(); k++) {
                    SortKey key = keys.get(k);
                    Object a = x[k + 1];
                    Object b = y[k + 1];
                    int c;
                    if (a == null || b == null) {
                        c = a == null && b == null ? 0 : (a == null) == key.nullsFirst() ? -1 : 1;
                    } else {
                        c = Order.compare(a, b, inputSchema.type(key.column()));
                        if (!key.ascending()) {
                            c = -c;
                        }
                    }
                    if (c != 0) {
                        return c;
                    }
                }
                return 0;
            };
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
