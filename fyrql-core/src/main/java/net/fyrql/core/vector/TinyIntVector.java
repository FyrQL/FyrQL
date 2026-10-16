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

/** A {@code TINYINT} vector over a {@code byte[]}. */
public final class TinyIntVector implements Vector {

    private final byte[] values;
    private final boolean[] nulls;

    TinyIntVector(byte[] values, boolean[] nulls) {
        this.values = values;
        this.nulls = nulls;
    }

    /** Creates a vector without {@code NULL}s over the array, which is not copied. */
    public static TinyIntVector of(byte... values) {
        return new TinyIntVector(values, null);
    }

    /**
     * Creates a vector over arrays, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static TinyIntVector wrap(byte[] values, boolean[] nulls) {
        return new TinyIntVector(values, Nulls.wrap(nulls, values.length));
    }

    @Override
    public DataType type() {
        return DataType.TINYINT;
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
    public byte getByte(int row) {
        return values[row];
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : values[row];
    }

    @Override
    public TinyIntVector select(int[] rows) {
        byte[] selected = new byte[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = values[rows[i]];
        }
        return new TinyIntVector(selected, Nulls.select(nulls, rows));
    }

    /** Builds a {@code TINYINT} vector. */
    public static final class Builder implements Vector.Builder {
        private byte[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            values = new byte[Math.max(capacity, 1)];
        }

        @Override
        public DataType type() {
            return DataType.TINYINT;
        }

        public void appendByte(byte value) {
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
                appendByte((Byte) value);
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
        public TinyIntVector build() {
            return new TinyIntVector(Arrays.copyOf(values, size), Nulls.trim(nulls, size));
        }
    }
}
