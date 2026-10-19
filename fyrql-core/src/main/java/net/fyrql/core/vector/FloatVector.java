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

/** A {@code FLOAT} vector over a {@code float[]}. */
public final class FloatVector implements Vector {

    final float[] values;
    private final boolean[] nulls;

    FloatVector(float[] values, boolean[] nulls) {
        this.values = values;
        this.nulls = nulls;
    }

    /** Creates a vector without {@code NULL}s over the array, which is not copied. */
    public static FloatVector of(float... values) {
        return new FloatVector(values, null);
    }

    /**
     * Creates a vector over arrays, which are not copied: the values
     * and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static FloatVector wrap(float[] values, boolean[] nulls) {
        return new FloatVector(values, Nulls.wrap(nulls, values.length));
    }

    @Override
    public DataType type() {
        return DataType.FLOAT;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    @Override
    public boolean hasNulls() {
        return Nulls.any(nulls, size());
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public float getFloat(int row) {
        return values[row];
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : values[row];
    }

    @Override
    public FloatVector select(int[] rows) {
        float[] selected = new float[rows.length];
        for (int i = 0; i < rows.length; i++) {
            selected[i] = values[rows[i]];
        }
        return new FloatVector(selected, Nulls.select(nulls, rows));
    }

    /** Builds a {@code FLOAT} vector. */
    public static final class Builder implements Vector.Builder {
        private float[] values;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            values = new float[Math.max(capacity, 1)];
        }

        @Override
        public DataType type() {
            return DataType.FLOAT;
        }

        public void appendFloat(float value) {
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
                appendFloat((Float) value);
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
        public FloatVector build() {
            return new FloatVector(Arrays.copyOf(values, size), Nulls.trim(nulls, size));
        }
    }
}
