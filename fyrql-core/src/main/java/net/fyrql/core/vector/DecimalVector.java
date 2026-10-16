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
import net.fyrql.core.type.DataType.Decimal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * A {@code DECIMAL(p,s)} vector: the unscaled values in a {@code long[]} when
 * the precision is at most 18, otherwise in a {@code BigInteger[]}.
 */
public final class DecimalVector implements Vector {

    private final Decimal type;
    private final long[] small;
    private final BigInteger[] big;
    private final boolean[] nulls;

    DecimalVector(Decimal type, long[] small, BigInteger[] big, boolean[] nulls) {
        this.type = type;
        this.small = small;
        this.big = big;
        this.nulls = nulls;
    }

    /**
     * Creates a vector over arrays of a type of at most 18 digits, the values unscaled, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static DecimalVector wrap(Decimal type, long[] unscaled, boolean[] nulls) {
        if (!type.fitsLong()) {
            throw new IllegalArgumentException("Unscaled longs for " + type);
        }
        return new DecimalVector(type, unscaled, null, Nulls.wrap(nulls, unscaled.length));
    }

    @Override
    public Decimal type() {
        return type;
    }

    @Override
    public int size() {
        return small != null ? small.length : big.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    /** Tells whether the unscaled values are {@code long}s. */
    public boolean isSmall() {
        return small != null;
    }

    /** Returns the unscaled value at a row of a small vector, which must not be {@code NULL}. */
    public long getUnscaledLong(int row) {
        return small[row];
    }

    /** Returns the unscaled value at a row, which must not be {@code NULL}. */
    public BigInteger getUnscaled(int row) {
        return small != null ? BigInteger.valueOf(small[row]) : big[row];
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public BigDecimal getBigDecimal(int row) {
        return new BigDecimal(getUnscaled(row), type.scale());
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : getBigDecimal(row);
    }

    @Override
    public DecimalVector select(int[] rows) {
        if (small != null) {
            long[] selected = new long[rows.length];
            for (int i = 0; i < rows.length; i++) {
                selected[i] = small[rows[i]];
            }
            return new DecimalVector(type, selected, null, Nulls.select(nulls, rows));
        }
        BigInteger[] selected = new BigInteger[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = big[rows[i]];
        }
        return new DecimalVector(type, null, selected, Nulls.select(nulls, rows));
    }

    /** Builds a {@code DECIMAL} vector. */
    public static final class Builder implements Vector.Builder {
        private final Decimal type;
        private long[] small;
        private BigInteger[] big;
        private boolean[] nulls;
        private int size;

        public Builder(Decimal type, int capacity) {
            this.type = type;
            if (type.fitsLong()) {
                small = new long[Math.max(capacity, 1)];
            } else {
                big = new BigInteger[Math.max(capacity, 1)];
            }
        }

        @Override
        public DataType type() {
            return type;
        }

        /** Appends an unscaled value that is known to fit the precision. */
        public void appendUnscaled(long unscaled) {
            ensure();
            if (small != null) {
                small[size++] = unscaled;
            } else {
                big[size++] = BigInteger.valueOf(unscaled);
            }
        }

        /** Appends an unscaled value that is known to fit the precision. */
        public void appendUnscaled(BigInteger unscaled) {
            ensure();
            if (small != null) {
                small[size++] = unscaled.longValueExact();
            } else {
                big[size++] = unscaled;
            }
        }

        /**
         * Appends a value with exactly the scale of the type.
         *
         * @throws IllegalArgumentException if the scale differs or the value does not fit
         */
        public void appendBigDecimal(BigDecimal value) {
            if (!type.accepts(value)) {
                throw new IllegalArgumentException("Not a " + type + ": " + value);
            }
            appendUnscaled(value.unscaledValue());
        }

        @Override
        public void appendNull() {
            ensure();
            if (small != null) {
                small[size] = 0;
            } else {
                big[size] = BigInteger.ZERO;
            }
            nulls = Nulls.mark(nulls, size++, capacity());
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else {
                appendBigDecimal((BigDecimal) value);
            }
        }

        private int capacity() {
            return small != null ? small.length : big.length;
        }

        private void ensure() {
            if (size == capacity()) {
                if (small != null) {
                    small = Arrays.copyOf(small, size * 2);
                } else {
                    big = Arrays.copyOf(big, size * 2);
                }
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public DecimalVector build() {
            return new DecimalVector(type, small == null ? null : Arrays.copyOf(small, size),
                    big == null ? null : Arrays.copyOf(big, size), Nulls.trim(nulls, size));
        }
    }
}
