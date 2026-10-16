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

import java.util.Arrays;

/** An {@code INTEGER} or {@code DATE} (days since 1970-01-01) vector over an {@code int[]}. */
public final class IntVector implements Vector {

    private final DataType type;
    private final int[] values;
    private final boolean[] nulls;

    IntVector(DataType type, int[] values, boolean[] nulls) {
        if (type != DataType.INTEGER && type != DataType.DATE) {
            throw new IllegalArgumentException("Not an int type: " + type);
        }
        this.type = type;
        this.values = values;
        this.nulls = nulls;
    }

    /** Creates an {@code INTEGER} vector without {@code NULL}s over the array, which is not copied. */
    public static IntVector of(int... values) {
        return new IntVector(DataType.INTEGER, values, null);
    }

    /**
     * Creates a vector over arrays of {@code INTEGER} or {@code DATE}, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static IntVector wrap(DataType type, int[] values, boolean[] nulls) {
        return new IntVector(type, values, Nulls.wrap(nulls, values.length));
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
    public int getInt(int row) {
        return values[row];
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : values[row];
    }

    @Override
    public IntVector select(int[] rows) {
        int[] selected = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = values[rows[i]];
        }
        return new IntVector(type, selected, Nulls.select(nulls, rows));
    }

    /** Builds an {@code INTEGER} or {@code DATE} vector. */
    public static final class Builder implements Vector.Builder {
        private final DataType type;
        private int[] values;
        private boolean[] nulls;
        private int size;

        public Builder(DataType type, int capacity) {
            if (type != DataType.INTEGER && type != DataType.DATE) {
                throw new IllegalArgumentException("Not an int type: " + type);
            }
            this.type = type;
            values = new int[Math.max(capacity, 1)];
        }

        @Override
        public DataType type() {
            return type;
        }

        public void appendInt(int value) {
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
                appendInt((Integer) value);
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
        public IntVector build() {
            return new IntVector(type, Arrays.copyOf(values, size), Nulls.trim(nulls, size));
        }
    }
}
