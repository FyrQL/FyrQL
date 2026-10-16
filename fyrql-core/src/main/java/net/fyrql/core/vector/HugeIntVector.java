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

package net.fyrql.core.vector;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Values;

import java.math.BigInteger;
import java.util.Arrays;

/** A {@code HUGEINT} vector: a signed 128-bit integer per row as a high and a low {@code long}. */
public final class HugeIntVector implements Vector {

    private static final BigInteger LOW_MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final long[] high;
    private final long[] low;
    private final boolean[] nulls;

    HugeIntVector(long[] high, long[] low, boolean[] nulls) {
        this.high = high;
        this.low = low;
        this.nulls = nulls;
    }

    /**
     * Creates a vector over arrays, the high and the low halves of the values, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static HugeIntVector wrap(long[] high, long[] low, boolean[] nulls) {
        if (high.length != low.length) {
            throw new IllegalArgumentException("Halves of different lengths: " + high.length + " and " + low.length);
        }
        return new HugeIntVector(high, low, Nulls.wrap(nulls, high.length));
    }

    @Override
    public DataType type() {
        return DataType.HUGEINT;
    }

    @Override
    public int size() {
        return high.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    /** Returns the upper 64 bits of the value at a row, which must not be {@code NULL}. */
    public long getHigh(int row) {
        return high[row];
    }

    /** Returns the lower 64 bits of the value at a row, which must not be {@code NULL}. */
    public long getLow(int row) {
        return low[row];
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public BigInteger getBigInteger(int row) {
        return toBigInteger(high[row], low[row]);
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : getBigInteger(row);
    }

    @Override
    public HugeIntVector select(int[] rows) {
        long[] h = new long[rows.length];
        long[] l = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            h[i] = high[rows[i]];
            l[i] = low[rows[i]];
        }
        return new HugeIntVector(h, l, Nulls.select(nulls, rows));
    }

    /** Combines the two halves into a {@code BigInteger}. */
    public static BigInteger toBigInteger(long high, long low) {
        return BigInteger.valueOf(high).shiftLeft(64).add(BigInteger.valueOf(low).and(LOW_MASK));
    }

    /** Compares two values given as halves. */
    public static int compare(long highA, long lowA, long highB, long lowB) {
        int c = Long.compare(highA, highB);
        return c != 0 ? c : Long.compareUnsigned(lowA, lowB);
    }

    /** Builds a {@code HUGEINT} vector. */
    public static final class Builder implements Vector.Builder {
        private long[] high;
        private long[] low;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            high = new long[Math.max(capacity, 1)];
            low = new long[high.length];
        }

        @Override
        public DataType type() {
            return DataType.HUGEINT;
        }

        public void appendHalves(long highValue, long lowValue) {
            ensure();
            high[size] = highValue;
            low[size++] = lowValue;
        }

        public void appendBigInteger(BigInteger value) {
            if (!Values.inHugeIntRange(value)) {
                throw new ArithmeticException("Out of range for HUGEINT: " + value);
            }
            appendHalves(value.shiftRight(64).longValue(), value.longValue());
        }

        @Override
        public void appendNull() {
            ensure();
            nulls = Nulls.mark(nulls, size++, high.length);
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else {
                appendBigInteger((BigInteger) value);
            }
        }

        private void ensure() {
            if (size == high.length) {
                high = Arrays.copyOf(high, size * 2);
                low = Arrays.copyOf(low, size * 2);
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public HugeIntVector build() {
            return new HugeIntVector(Arrays.copyOf(high, size), Arrays.copyOf(low, size), Nulls.trim(nulls, size));
        }
    }
}
