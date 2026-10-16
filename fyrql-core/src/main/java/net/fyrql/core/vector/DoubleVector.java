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

/** A {@code DOUBLE} vector over a {@code double[]}. */
public final class DoubleVector implements Vector {

    private final double[] values;
    private final boolean[] nulls;

    DoubleVector(double[] values, boolean[] nulls) {
        this.values = values;
        this.nulls = nulls;
    }

    /** Creates a vector without {@code NULL}s over the array, which is not copied. */
    public static DoubleVector of(double... values) {
        return new DoubleVector(values, null);
    }

    /**
     * Creates a vector over arrays, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static DoubleVector wrap(double[] values, boolean[] nulls) {
        return new DoubleVector(values, Nulls.wrap(nulls, values.length));
    }

    @Override
    public DataType type() {
        return DataType.DOUBLE;
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
    public double getDouble(int row) {
        return values[row];
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : values[row];
    }

    @Override
    public DoubleVector select(int[] rows) {
        double[] selected = new double[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = values[rows[i]];
        }
        return new DoubleVector(selected, Nulls.select(nulls, rows));
    }

    /** Builds a {@code DOUBLE} vector. */
    public static final class Builder implements Vector.Builder {
        private double[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            values = new double[Math.max(capacity, 1)];
        }

        @Override
        public DataType type() {
            return DataType.DOUBLE;
        }

        public void appendDouble(double value) {
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
                appendDouble((Double) value);
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
        public DoubleVector build() {
            return new DoubleVector(Arrays.copyOf(values, size), Nulls.trim(nulls, size));
        }
    }
}
