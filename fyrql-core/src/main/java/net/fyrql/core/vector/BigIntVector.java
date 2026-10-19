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

package net.fyrql.core.vector;

import net.fyrql.core.type.DataType;

import java.util.Arrays;

/** A {@code BIGINT} or {@code TIMESTAMP} (microseconds since 1970) vector over a {@code long[]}. */
public final class BigIntVector implements Vector {

    private final DataType type;
    private final long[] values;
    private final boolean[] nulls;

    BigIntVector(DataType type, long[] values, boolean[] nulls) {
        if (type != DataType.BIGINT && type != DataType.TIMESTAMP) {
            throw new IllegalArgumentException("Not a long type: " + type);
        }
        this.type = type;
        this.values = values;
        this.nulls = nulls;
    }

    /** Creates a {@code BIGINT} vector without {@code NULL}s over the array, which is not copied. */
    public static BigIntVector of(long... values) {
        return new BigIntVector(DataType.BIGINT, values, null);
    }

    /**
     * Creates a vector over arrays of {@code BIGINT} or {@code TIMESTAMP}, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static BigIntVector wrap(DataType type, long[] values, boolean[] nulls) {
        return new BigIntVector(type, values, Nulls.wrap(nulls, values.length));
    }

    @Override
    public DataType type() {
        return type;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public long getLong(int row) {
        return values[row];
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : values[row];
    }

    @Override
    public BigIntVector select(int[] rows) {
        long[] selected = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = values[rows[i]];
        }
        return new BigIntVector(type, selected, Nulls.select(nulls, rows));
    }

    /** Builds a {@code BIGINT} or {@code TIMESTAMP} vector. */
    public static final class Builder implements Vector.Builder {
        private final DataType type;
        private long[] values;
        private boolean[] nulls;
        private int size;

        public Builder(DataType type, int capacity) {
            if (type != DataType.BIGINT && type != DataType.TIMESTAMP) {
                throw new IllegalArgumentException("Not a long type: " + type);
            }
            this.type = type;
            values = new long[Math.max(capacity, 1)];
        }

        @Override
        public DataType type() {
            return type;
        }

        public void appendLong(long value) {
            ensure();
            values[size++] = value;
        }

        @Override
        public void appendNull() {
            ensure();
            nulls = Nulls.mark(nulls, size++, values.length);
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else {
                appendLong((Long) value);
            }
        }

        private void ensure() {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public BigIntVector build() {
            return new BigIntVector(type, Arrays.copyOf(values, size), Nulls.trim(nulls, size));
        }
    }
}
