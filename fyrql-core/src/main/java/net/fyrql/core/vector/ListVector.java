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
import net.fyrql.core.type.DataType.ListType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@code LIST} vector: the elements of all lists in one child vector, the
 * list of row {@code i} being the elements from {@code offsets[i]} to
 * {@code offsets[i + 1]}.
 */
public final class ListVector implements Vector {

    private final ListType type;
    private final int[] offsets;
    private final Vector child;
    private final boolean[] nulls;

    ListVector(ListType type, int[] offsets, Vector child, boolean[] nulls) {
        this.type = type;
        this.offsets = offsets;
        this.child = child;
        this.nulls = nulls;
    }

    @Override
    public ListType type() {
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

    @Override
    public boolean hasNulls() {
        return Nulls.any(nulls, size());
    }

    /** Returns the vector of all elements. */
    public Vector child() {
        return child;
    }

    /** Returns the index in the child of the first element of a row. */
    public int start(int row) {
        return offsets[row];
    }

    /** Returns the index in the child after the last element of a row. */
    public int end(int row) {
        return offsets[row + 1];
    }

    /** Returns the number of elements of a row. */
    public int length(int row) {
        return offsets[row + 1] - offsets[row];
    }

    @Override
    public Object get(int row) {
        if (isNull(row)) {
            return null;
        }
        List<Object> values = new ArrayList<>(length(row));
        for (int i = offsets[row]; i < offsets[row + 1]; i++) {
            values.add(child.get(i));
        }
        return values;
    }

    @Override
    public ListVector select(int[] rows) {
        int[] newOffsets = new int[rows.length + 1];
        int total = 0;
        for (int i = 0; i < rows.length; i++) {
            newOffsets[i] = total;
            total += length(rows[i]);
        }
        newOffsets[rows.length] = total;
        int[] elements = new int[total];
        int k = 0;
        for (int row : rows) {
            for (int i = offsets[row]; i < offsets[row + 1]; i++) {
                elements[k++] = i;
            }
        }
        return new ListVector(type, newOffsets, child.select(elements), Nulls.select(nulls, rows));
    }

    /** Builds a {@code LIST} vector through the builder of its elements. */
    public static final class Builder implements Vector.Builder {
        private final ListType type;
        private final Vector.Builder elements;
        private int[] offsets;
        private boolean[] nulls;
        private int size;

        public Builder(ListType type, int capacity) {
            this.type = type;
            elements = Vectors.builder(type.element(), capacity * 2);
            offsets = new int[Math.max(capacity, 1) + 1];
        }

        @Override
        public DataType type() {
            return type;
        }

        /** Returns the builder of the elements; append the elements of the next list, then call {@link #endList}. */
        public Vector.Builder elements() {
            return elements;
        }

        /** Completes the list whose elements were appended since the last call. */
        public void endList() {
            ensureRow();
            offsets[++size] = elements.size();
        }

        @Override
        public void appendNull() {
            ensureRow();
            nulls = Nulls.mark(nulls, size, offsets.length - 1);
            offsets[++size] = elements.size();
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
                return;
            }
            List<?> list = (List<?>) value;
            elements.reserve(list.size());
            for (Object element : list) {
                elements.append(element);
            }
            endList();
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
        public ListVector build() {
            return new ListVector(type, Arrays.copyOf(offsets, size + 1), elements.build(), Nulls.trim(nulls, size));
        }
    }
}
