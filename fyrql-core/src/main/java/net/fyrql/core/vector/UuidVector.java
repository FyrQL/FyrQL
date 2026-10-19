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
import java.util.UUID;

/** A {@code UUID} vector: the most and least significant 64 bits per row. */
public final class UuidVector implements Vector {

    final long[] most;
    final long[] least;
    private final boolean[] nulls;

    UuidVector(long[] most, long[] least, boolean[] nulls) {
        this.most = most;
        this.least = least;
        this.nulls = nulls;
    }

    @Override
    public DataType type() {
        return DataType.UUID;
    }

    @Override
    public int size() {
        return most.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    @Override
    public boolean hasNulls() {
        return Nulls.any(nulls, size());
    }

    public long getMost(int row) {
        return most[row];
    }

    public long getLeast(int row) {
        return least[row];
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public UUID getUuid(int row) {
        return new UUID(most[row], least[row]);
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : getUuid(row);
    }

    /** Compares two UUIDs as unsigned 128-bit values, which is the order of their text form. */
    public static int compare(long mostA, long leastA, long mostB, long leastB) {
        int c = Long.compareUnsigned(mostA, mostB);
        return c != 0 ? c : Long.compareUnsigned(leastA, leastB);
    }

    @Override
    public UuidVector select(int[] rows) {
        long[] m = new long[rows.length];
        long[] l = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            m[i] = most[rows[i]];
            l[i] = least[rows[i]];
        }
        return new UuidVector(m, l, Nulls.select(nulls, rows));
    }

    /** Builds a {@code UUID} vector. */
    public static final class Builder implements Vector.Builder {
        private long[] most;
        private long[] least;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            most = new long[Math.max(capacity, 1)];
            least = new long[most.length];
        }

        @Override
        public DataType type() {
            return DataType.UUID;
        }

        public void appendUuid(UUID value) {
            ensure();
            most[size] = value.getMostSignificantBits();
            least[size++] = value.getLeastSignificantBits();
        }

        @Override
        public void appendNull() {
            ensure();
            nulls = Nulls.mark(nulls, size++, most.length);
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else {
                appendUuid((UUID) value);
            }
        }

        private void ensure() {
            if (size == most.length) {
                most = Arrays.copyOf(most, size * 2);
                least = Arrays.copyOf(least, size * 2);
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public UuidVector build() {
            return new UuidVector(Arrays.copyOf(most, size), Arrays.copyOf(least, size), Nulls.trim(nulls, size));
        }
    }
}
