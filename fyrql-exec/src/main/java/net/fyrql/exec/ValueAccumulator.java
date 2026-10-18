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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * The value of one aggregate over the rows added to it, with boxed values:
 * sums of integers are exact 128-bit values, sums of decimals keep their
 * scale, and {@code avg} is a double. {@code DISTINCT} skips the values
 * seen before; an aggregate with an {@code ORDER BY} keeps its values with
 * their sort keys and accumulates them in that order at the end, with a
 * stable sort.
 */
final class ValueAccumulator {

    private static final long BYTES_PER_VALUE = 32;

    private final AggregateCall call;
    private final Schema input;
    private final LongConsumer reserve;
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

    /**
     * Creates an empty accumulator.
     *
     * @param call the aggregate
     * @param input the schema of the rows
     * @param reserve takes the bytes of each value the accumulator keeps
     */
    ValueAccumulator(AggregateCall call, Schema input, LongConsumer reserve) {
        this.call = call;
        this.input = input;
        this.reserve = reserve;
        this.function = call.function();
        this.type = function == AggregateFunction.COUNT_STAR ? null : input.type(call.column());
        this.resultType = call.type(input);
        this.seen = call.distinct() ? new HashSet<>() : null;
        this.ordered = call.orderBy().isEmpty() ? null : new ArrayList<>();
        if (type instanceof Decimal d) {
            decimalSum = BigDecimal.valueOf(0, d.scale());
        }
        bool = function == AggregateFunction.BOOL_AND;
    }

    /**
     * Tells whether accumulators of an aggregate {@link #merge} into one
     * another: not with {@code DISTINCT} or an {@code ORDER BY}, and not
     * for the lists of values.
     */
    static boolean mergeable(AggregateCall call) {
        return !call.distinct() && call.orderBy().isEmpty() && call.function() != AggregateFunction.STRING_AGG
                && call.function() != AggregateFunction.LIST;
    }

    /**
     * Empties an accumulator of a {@link #mergeable} aggregate, so that it
     * takes the rows of another frame as a new one would.
     */
    void reset() {
        count = 0;
        longSum = 0;
        bigSum = null;
        doubleSum = 0;
        if (type instanceof Decimal d) {
            decimalSum = BigDecimal.valueOf(0, d.scale());
        }
        extreme = null;
        bool = function == AggregateFunction.BOOL_AND;
    }

    /** Adds the value of a row. */
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
        reserve.accept(BYTES_PER_VALUE);
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
                    addLong(((Number) value).longValue());
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
                reserve.accept(BYTES_PER_VALUE);
                values.add(function == AggregateFunction.LIST ? value : Casts.format(value, type));
            }
            default -> {
            }
        }
    }

    private void addLong(long x) {
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
    }

    /**
     * Adds what another accumulator of the same aggregate holds, as if its
     * rows came after the rows of this one; the other stays as it is.
     *
     * @throws IllegalStateException if the aggregate is not {@link #mergeable}
     */
    void merge(ValueAccumulator other) {
        if (!mergeable(call)) {
            throw new IllegalStateException("No merge for " + call.name());
        }
        if (other.count == 0) {
            return;
        }
        switch (function) {
            case SUM, AVG -> {
                if (type instanceof Decimal) {
                    decimalSum = decimalSum.add(other.decimalSum);
                } else if (type.isIntegral()) {
                    if (other.bigSum != null) {
                        bigSum = (bigSum != null ? bigSum : BigInteger.valueOf(longSum)).add(other.bigSum);
                    } else {
                        addLong(other.longSum);
                    }
                } else {
                    doubleSum += other.doubleSum;
                }
            }
            case MIN, MAX -> {
                int c = extreme == null ? 0 : Order.compare(other.extreme, extreme, type);
                if (extreme == null || (function == AggregateFunction.MIN ? c < 0 : c > 0)) {
                    extreme = other.extreme;
                }
            }
            case FIRST, ANY_VALUE -> extreme = count == 0 ? other.extreme : extreme;
            case LAST -> extreme = other.extreme;
            case BOOL_AND -> bool &= other.bool;
            case BOOL_OR -> bool |= other.bool;
            default -> {
            }
        }
        count += other.count;
    }

    /** Returns the value of the aggregate; once for an ordered aggregate. */
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
                    yield HashAggregateOperator.toDouble(decimalSum.unscaledValue())
                            / (count * Math.pow(10, d.scale()));
                }
                if (type.isIntegral()) {
                    BigInteger sum = bigSum != null ? bigSum : BigInteger.valueOf(longSum);
                    if (type == DataType.HUGEINT) {
                        // DuckDB sums HUGEINTs in a HUGEINT, which overflows.
                        Values.boxChecked(sum, DataType.HUGEINT);
                    }
                    yield HashAggregateOperator.toDouble(sum) / count;
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
                    c = Order.compare(a, b, input.type(key.column()));
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
