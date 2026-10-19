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
import net.fyrql.core.type.DataType.MapType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code MAP} vector: the entries of all maps in a key vector and a value
 * vector, the entries of row {@code i} being those from {@code offsets[i]}
 * to {@code offsets[i + 1]}. Keys are never {@code NULL}.
 */
public final class MapVector implements Vector {

    private final MapType type;
    private final int[] offsets;
    private final Vector keys;
    private final Vector values;
    private final boolean[] nulls;

    MapVector(MapType type, int[] offsets, Vector keys, Vector values, boolean[] nulls) {
        this.type = type;
        this.offsets = offsets;
        this.keys = keys;
        this.values = values;
        this.nulls = nulls;
    }

    @Override
    public MapType type() {
        return type;
    }

    @Override
    public int size() {
        return offsets.length - 1;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    /** Returns the vector of all keys. */
    public Vector keys() {
        return keys;
    }

    /** Returns the vector of all values. */
    public Vector values() {
        return values;
    }

    /** Returns the index of the first entry of a row. */
    public int start(int row) {
        return offsets[row];
    }

    /** Returns the index after the last entry of a row. */
    public int end(int row) {
        return offsets[row + 1];
    }

    /** Returns the number of entries of a row. */
    public int length(int row) {
        return offsets[row + 1] - offsets[row];
    }

    @Override
    public Object get(int row) {
        if (isNull(row)) {
            return null;
        }
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = offsets[row]; i < offsets[row + 1]; i++) {
            map.put(keys.get(i), values.get(i));
        }
        return map;
    }

    @Override
    public MapVector select(int[] rows) {
        int[] newOffsets = new int[rows.length + 1];
        int total = 0;
        for (int i = 0; i < rows.length; i++) {
            newOffsets[i] = total;
            total += length(rows[i]);
        }
        newOffsets[rows.length] = total;
        int[] entries = new int[total];
        int k = 0;
        for (int row : rows) {
            for (int i = offsets[row]; i < offsets[row + 1]; i++) {
                entries[k++] = i;
            }
        }
        return new MapVector(type, newOffsets, keys.select(entries), values.select(entries),
                Nulls.select(nulls, rows));
    }

    /** Builds a {@code MAP} vector through the builders of its keys and values. */
    public static final class Builder implements Vector.Builder {
        private final MapType type;
        private final Vector.Builder keys;
        private final Vector.Builder values;
        private int[] offsets;
        private boolean[] nulls;
        private int size;

        public Builder(MapType type, int capacity) {
            this.type = type;
            keys = Vectors.builder(type.key(), capacity * 2);
            values = Vectors.builder(type.value(), capacity * 2);
            offsets = new int[Math.max(capacity, 1) + 1];
        }

        @Override
        public DataType type() {
            return type;
        }

        public Vector.Builder keys() {
            return keys;
        }

        public Vector.Builder values() {
            return values;
        }

        /** Completes the map whose entries were appended since the last call. */
        public void endMap() {
            if (keys.size() != values.size()) {
                throw new IllegalStateException(keys.size() + " keys, " + values.size() + " values");
            }
            ensureRow();
            offsets[++size] = keys.size();
        }

        @Override
        public void appendNull() {
            ensureRow();
            nulls = Nulls.mark(nulls, size, offsets.length - 1);
            offsets[++size] = keys.size();
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
                return;
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getKey() == null) {
                    throw new IllegalArgumentException("A MAP key must not be NULL");
                }
                keys.append(e.getKey());
                values.append(e.getValue());
            }
            endMap();
        }

        private void ensureRow() {
            if (size + 1 == offsets.length) {
                offsets = Arrays.copyOf(offsets, size * 2 + 1);
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public MapVector build() {
            return new MapVector(type, Arrays.copyOf(offsets, size + 1), keys.build(), values.build(),
                    Nulls.trim(nulls, size));
        }
    }
}
