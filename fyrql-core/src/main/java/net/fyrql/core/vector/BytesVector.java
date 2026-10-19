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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A {@code VARCHAR} or {@code BLOB} vector: one byte buffer with an offset
 * per row, the value of row {@code i} being the bytes from
 * {@code offsets[i]} to {@code offsets[i + 1]}. A {@code VARCHAR} holds
 * UTF-8, so that comparing the bytes orders strings by code point, as
 * DuckDB does.
 */
public final class BytesVector implements Vector {

    private final DataType type;
    private final byte[] data;
    final int[] offsets;
    private final boolean[] nulls;

    BytesVector(DataType type, byte[] data, int[] offsets, boolean[] nulls) {
        if (type != DataType.VARCHAR && type != DataType.BLOB) {
            throw new IllegalArgumentException("Not a bytes type: " + type);
        }
        this.type = type;
        this.data = data;
        this.offsets = offsets;
        this.nulls = nulls;
    }

    /** Creates a {@code VARCHAR} vector without {@code NULL}s. */
    public static BytesVector ofStrings(String... values) {
        Builder b = new Builder(DataType.VARCHAR, values.length);
        for (String v : values) {
            b.appendString(v);
        }
        return b.build();
    }

    @Override
    public DataType type() {
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

    /** Returns the shared buffer; the bytes of a row lie between its offsets. */
    public byte[] data() {
        return data;
    }

    /** Returns the start of the bytes of a row in the buffer. */
    public int start(int row) {
        return offsets[row];
    }

    /** Returns the end of the bytes of a row in the buffer. */
    public int end(int row) {
        return offsets[row + 1];
    }

    /** Returns the number of bytes of a row. */
    public int length(int row) {
        return offsets[row + 1] - offsets[row];
    }

    /** Returns a copy of the bytes of a row, which must not be {@code NULL}. */
    public byte[] getBytes(int row) {
        return Arrays.copyOfRange(data, offsets[row], offsets[row + 1]);
    }

    /** Returns the string of a row of a {@code VARCHAR} vector, which must not be {@code NULL}. */
    public String getString(int row) {
        return new String(data, offsets[row], length(row), StandardCharsets.UTF_8);
    }

    @Override
    public Object get(int row) {
        if (isNull(row)) {
            return null;
        }
        return type == DataType.VARCHAR ? getString(row) : getBytes(row);
    }

    /** Compares the bytes of two rows as unsigned bytes, which for UTF-8 is the code point order. */
    public static int compare(BytesVector a, int rowA, BytesVector b, int rowB) {
        return Arrays.compareUnsigned(a.data, a.offsets[rowA], a.offsets[rowA + 1],
                b.data, b.offsets[rowB], b.offsets[rowB + 1]);
    }

    /** Tells whether the bytes of two rows are equal. */
    public static boolean equal(BytesVector a, int rowA, BytesVector b, int rowB) {
        return Arrays.equals(a.data, a.offsets[rowA], a.offsets[rowA + 1], b.data, b.offsets[rowB],
                b.offsets[rowB + 1]);
    }

    /** Returns a hash of the bytes of a row. */
    public int hash(int row) {
        int h = 1;
        for (int i = offsets[row]; i < offsets[row + 1]; i++) {
            h = 31 * h + data[i];
        }
        return h;
    }

    @Override
    public BytesVector select(int[] rows) {
        int[] newOffsets = new int[rows.length + 1];
        int total = 0;
        for (int i = 0; i < rows.length; i++) {
            newOffsets[i] = total;
            total += length(rows[i]);
        }
        newOffsets[rows.length] = total;
        byte[] newData = new byte[total];
        for (int i = 0; i < rows.length; i++) {
            System.arraycopy(data, offsets[rows[i]], newData, newOffsets[i], length(rows[i]));
        }
        return new BytesVector(type, newData, newOffsets, Nulls.select(nulls, rows));
    }

    /** Builds a {@code VARCHAR} or {@code BLOB} vector. */
    public static final class Builder implements Vector.Builder {
        private final DataType type;
        private byte[] data;
        private int used;
        private int[] offsets;
        private boolean[] nulls;
        private int size;

        public Builder(DataType type, int capacity) {
            if (type != DataType.VARCHAR && type != DataType.BLOB) {
                throw new IllegalArgumentException("Not a bytes type: " + type);
            }
            this.type = type;
            offsets = new int[Math.max(capacity, 1) + 1];
            data = new byte[Math.max(capacity, 1) * 16];
        }

        @Override
        public DataType type() {
            return type;
        }

        /** Appends a range of a byte array. */
        public void appendBytes(byte[] bytes, int from, int length) {
            ensureRow();
            if (used + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, used + length));
            }
            System.arraycopy(bytes, from, data, used, length);
            used += length;
            offsets[++size] = used;
        }

        public void appendBytes(byte[] bytes) {
            appendBytes(bytes, 0, bytes.length);
        }

        /** Appends a string as UTF-8. */
        public void appendString(String s) {
            appendBytes(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void appendNull() {
            ensureRow();
            nulls = Nulls.mark(nulls, size, offsets.length - 1);
            offsets[++size] = used;
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else if (value instanceof String s) {
                if (type != DataType.VARCHAR) {
                    throw new IllegalArgumentException("Not a " + type + ": " + s);
                }
                appendString(s);
            } else {
                if (type != DataType.BLOB) {
                    throw new IllegalArgumentException("Not a " + type + ": byte[]");
                }
                appendBytes((byte[]) value);
            }
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
        public BytesVector build() {
            return new BytesVector(type, Arrays.copyOf(data, used), Arrays.copyOf(offsets, size + 1),
                    Nulls.trim(nulls, size));
        }
    }
}
