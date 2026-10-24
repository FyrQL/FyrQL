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
 * A {@code VARCHAR} or {@code BLOB} vector. In the flat form it is one byte
 * buffer with an offset per row, the value of row {@code i} being the bytes
 * from {@code offsets[i]} to {@code offsets[i + 1]}. In the dictionary form
 * it is a code per row into a {@link Dictionary}, which several vectors may
 * share, the value of a row being the value of its code. Both forms answer
 * {@link #data}, {@link #start} and {@link #end} alike, so a reader of the
 * bytes of a row need not know the form; only the bytes of consecutive rows
 * need not follow each other in the dictionary form. A {@code VARCHAR}
 * holds UTF-8, so that comparing the bytes orders strings by code point, as
 * DuckDB does.
 */
public final class BytesVector implements Vector {

    private final DataType type;
    private final byte[] data;
    /** The offsets of the flat form, {@code null} in the dictionary form. */
    final int[] offsets;
    /** The codes of the dictionary form, {@code null} in the flat form. */
    final int[] codes;
    /** The dictionary of the dictionary form, {@code null} in the flat form. */
    final Dictionary dictionary;
    private final boolean[] nulls;

    BytesVector(DataType type, byte[] data, int[] offsets, boolean[] nulls) {
        this(type, data, offsets, null, null, nulls);
    }

    BytesVector(DataType type, int[] codes, Dictionary dictionary, boolean[] nulls) {
        this(type, dictionary.data, null, codes, dictionary, nulls);
    }

    private BytesVector(DataType type, byte[] data, int[] offsets, int[] codes, Dictionary dictionary,
            boolean[] nulls) {
        if (type != DataType.VARCHAR && type != DataType.BLOB) {
            throw new IllegalArgumentException("Not a bytes type: " + type);
        }
        this.type = type;
        this.data = data;
        this.offsets = offsets;
        this.codes = codes;
        this.dictionary = dictionary;
        this.nulls = nulls;
    }

    /**
     * Creates a {@code VARCHAR} or {@code BLOB} vector over arrays, which are not copied: the bytes of the values,
     * an offset per row and one more, the value of row {@code i} being the bytes from {@code offsets[i]} to
     * {@code offsets[i + 1]}, and a mask with {@code true} where the value is {@code NULL}, or {@code null} if none
     * is; a {@code NULL} should have no bytes. The caller must not change the arrays afterwards.
     *
     * @throws IllegalArgumentException if the offsets decrease or lie outside the bytes
     */
    public static BytesVector wrap(DataType type, byte[] data, int[] offsets, boolean[] nulls) {
        checkOffsets(data, offsets, "row");
        return new BytesVector(type, data, offsets, Nulls.wrap(nulls, offsets.length - 1));
    }

    /**
     * Creates a {@code VARCHAR} or {@code BLOB} vector in the dictionary form over arrays, which are not copied: a
     * code per row into the dictionary, and a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. Every code must lie in the dictionary, that of a {@code NULL} too, whose bytes do
     * not count. The caller must not change the arrays afterwards.
     *
     * @throws IllegalArgumentException if a code lies outside the dictionary
     */
    public static BytesVector dictionary(DataType type, int[] codes, Dictionary dictionary, boolean[] nulls) {
        int size = dictionary.size();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] < 0 || codes[i] >= size) {
                throw new IllegalArgumentException("The code " + codes[i] + " of row " + i + " is beyond the "
                        + size + " values of the dictionary");
            }
        }
        return new BytesVector(type, codes, dictionary, Nulls.wrap(nulls, codes.length));
    }

    /** Creates a {@code VARCHAR} vector without {@code NULL}s. */
    public static BytesVector ofStrings(String... values) {
        Builder b = new Builder(DataType.VARCHAR, values.length);
        for (String v : values) {
            b.appendString(v);
        }
        return b.build();
    }

    private static void checkOffsets(byte[] data, int[] offsets, String what) {
        if (offsets.length == 0 || offsets[0] < 0 || offsets[offsets.length - 1] > data.length) {
            throw new IllegalArgumentException("Offsets outside of " + data.length + " bytes");
        }
        for (int i = 1; i < offsets.length; i++) {
            if (offsets[i] < offsets[i - 1]) {
                throw new IllegalArgumentException("The offset of " + what + " " + i + " is before that of " + what
                        + " " + (i - 1));
            }
        }
    }

    @Override
    public DataType type() {
        return type;
    }

    @Override
    public int size() {
        return codes != null ? codes.length : offsets.length - 1;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    @Override
    public boolean hasNulls() {
        return Nulls.any(nulls, size());
    }

    /** Tells whether the vector is in the dictionary form. */
    public boolean hasDictionary() {
        return codes != null;
    }

    /** Returns the dictionary of the dictionary form, {@code null} in the flat form. */
    public Dictionary dictionary() {
        return dictionary;
    }

    /** Returns the code of a row in the dictionary form. */
    public int code(int row) {
        if (codes == null) {
            throw new IllegalStateException("A vector without a dictionary");
        }
        return codes[row];
    }

    /**
     * Returns the shared buffer, in the dictionary form that of the dictionary; the bytes of a row lie between its
     * {@link #start} and {@link #end}.
     */
    public byte[] data() {
        return data;
    }

    /** Returns the start of the bytes of a row in the buffer. */
    public int start(int row) {
        return codes == null ? offsets[row] : dictionary.offsets[codes[row]];
    }

    /** Returns the end of the bytes of a row in the buffer. */
    public int end(int row) {
        return codes == null ? offsets[row + 1] : dictionary.offsets[codes[row] + 1];
    }

    /** Returns the number of bytes of a row. */
    public int length(int row) {
        return end(row) - start(row);
    }

    /** The number of bytes of a row when made flat: those of a {@code NULL} from a dictionary do not count. */
    int flatLength(int row) {
        return codes == null ? offsets[row + 1] - offsets[row] : isNull(row) ? 0 : dictionary.length(codes[row]);
    }

    /** Returns a copy of the bytes of a row, which must not be {@code NULL}. */
    public byte[] getBytes(int row) {
        return Arrays.copyOfRange(data, start(row), end(row));
    }

    /** Returns the string of a row of a {@code VARCHAR} vector, which must not be {@code NULL}. */
    public String getString(int row) {
        int start = start(row);
        return new String(data, start, end(row) - start, StandardCharsets.UTF_8);
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
        if (a.dictionary != null && a.dictionary == b.dictionary && a.codes[rowA] == b.codes[rowB]) {
            return 0;
        }
        return Arrays.compareUnsigned(a.data, a.start(rowA), a.end(rowA), b.data, b.start(rowB), b.end(rowB));
    }

    /** Tells whether the bytes of two rows are equal. */
    public static boolean equal(BytesVector a, int rowA, BytesVector b, int rowB) {
        if (a.dictionary != null && a.dictionary == b.dictionary && a.codes[rowA] == b.codes[rowB]) {
            return true;
        }
        return Arrays.equals(a.data, a.start(rowA), a.end(rowA), b.data, b.start(rowB), b.end(rowB));
    }

    /** Returns a hash of the bytes of a row. */
    public int hash(int row) {
        if (codes != null) {
            return dictionary.hash(codes[row]);
        }
        return hash(data, offsets[row], offsets[row + 1]);
    }

    private static int hash(byte[] data, int from, int to) {
        int h = 1;
        for (int i = from; i < to; i++) {
            h = 31 * h + data[i];
        }
        return h;
    }

    /** Selects rows; the dictionary form keeps its dictionary and selects only the codes. */
    @Override
    public BytesVector select(int[] rows) {
        if (codes != null) {
            int[] newCodes = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                newCodes[i] = codes[rows[i]];
            }
            return new BytesVector(type, newCodes, dictionary, Nulls.select(nulls, rows));
        }
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

    /**
     * The distinct values of a column for the dictionary form of {@link BytesVector}: one byte buffer with an
     * offset per value and one more, the value of code {@code c} being the bytes from {@code offsets[c]} to
     * {@code offsets[c + 1]}. A dictionary is immutable and may be shared by any number of vectors, so that the
     * check of its offsets and the hashes of its values are paid once.
     */
    public static final class Dictionary {
        final byte[] data;
        final int[] offsets;
        /** The hashes of the values, computed when first needed. */
        private volatile int[] hashes;

        private Dictionary(byte[] data, int[] offsets) {
            this.data = data;
            this.offsets = offsets;
        }

        /**
         * Creates a dictionary over arrays, which are not copied: the bytes of the values and an offset per value
         * and one more. The caller must not change the arrays afterwards.
         *
         * @throws IllegalArgumentException if the offsets decrease or lie outside the bytes
         */
        public static Dictionary of(byte[] data, int[] offsets) {
            checkOffsets(data, offsets, "value");
            return new Dictionary(data, offsets);
        }

        /** Returns the number of values. */
        public int size() {
            return offsets.length - 1;
        }

        /** Returns the buffer of the values; the bytes of a value lie between its start and end. */
        public byte[] data() {
            return data;
        }

        /** Returns the start of the bytes of a value in the buffer. */
        public int start(int code) {
            return offsets[code];
        }

        /** Returns the end of the bytes of a value in the buffer. */
        public int end(int code) {
            return offsets[code + 1];
        }

        /** Returns the number of bytes of a value. */
        public int length(int code) {
            return offsets[code + 1] - offsets[code];
        }

        /** Returns a copy of the bytes of a value. */
        public byte[] getBytes(int code) {
            return Arrays.copyOfRange(data, offsets[code], offsets[code + 1]);
        }

        /** Returns a value as a string, decoded from UTF-8. */
        public String getString(int code) {
            return new String(data, offsets[code], length(code), StandardCharsets.UTF_8);
        }

        /** Returns the hash of a value, the same as {@link BytesVector#hash} of a row with that value. */
        public int hash(int code) {
            int[] h = hashes;
            if (h == null) {
                h = new int[size()];
                for (int c = 0; c < h.length; c++) {
                    h[c] = BytesVector.hash(data, offsets[c], offsets[c + 1]);
                }
                hashes = h;
            }
            return h[code];
        }
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
