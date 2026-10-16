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
import java.util.UUID;

/** A {@code UUID} vector: the most and least significant 64 bits per row. */
public final class UuidVector implements Vector {

    private final long[] most;
    private final long[] least;
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
