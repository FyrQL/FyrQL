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

package net.fyrql.connector.parquet;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.page.DictionaryPage;
import org.apache.parquet.schema.PrimitiveType;

/**
 * The dictionary page of a column chunk, decoded at once: the values of
 * {@code INT32}, {@code INT64}, {@code FLOAT} and {@code DOUBLE} as in
 * {@link PlainDecoder}, those of byte arrays copied into one array with an
 * offset per value. Nothing refers to the bytes of the page afterwards,
 * so that a decompressor can reuse them.
 */
final class PageDictionary {

    private final int size;
    private final long[] values;
    private final byte[] data;
    private final int[] offsets;

    private PageDictionary(int size, long[] values, byte[] data, int[] offsets) {
        this.size = size;
        this.values = values;
        this.data = data;
        this.offsets = offsets;
    }

    /**
     * Decodes a dictionary page.
     *
     * @throws IllegalArgumentException if the page is not in the
     *         {@code PLAIN} encoding or ends before its values
     */
    static PageDictionary read(DictionaryPage page, PrimitiveType type) {
        Encoding encoding = page.getEncoding();
        if (encoding != Encoding.PLAIN && encoding != Encoding.PLAIN_DICTIONARY) {
            throw new IllegalArgumentException("The encoding " + encoding + " of a dictionary page is not supported");
        }
        PlainDecoder.Slice s = PlainDecoder.slice(page.getBytes());
        int size = page.getDictionarySize();
        if (size < 0) {
            throw new IllegalArgumentException("A dictionary page of " + size + " values");
        }
        byte[] b = s.array();
        return switch (type.getPrimitiveTypeName()) {
            case INT32, FLOAT -> {
                PlainDecoder.check(s.from(), s.to(), (long) size * 4);
                long[] values = new long[size];
                PlainDecoder.ints(b, s.from(), s.to(), size, values, 0);
                yield new PageDictionary(size, values, null, null);
            }
            case INT64, DOUBLE -> {
                PlainDecoder.check(s.from(), s.to(), (long) size * 8);
                long[] values = new long[size];
                PlainDecoder.longs(b, s.from(), s.to(), size, values, 0);
                yield new PageDictionary(size, values, null, null);
            }
            case BINARY -> {
                PlainDecoder.check(s.from(), s.to(), (long) size * 4);
                int[] from = new int[size];
                int[] length = new int[size];
                PlainDecoder.binaries(b, s.from(), s.to(), size, from, length);
                yield copy(b, from, length);
            }
            case FIXED_LEN_BYTE_ARRAY, INT96 -> {
                int width = type.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT96 ? 12
                        : type.getTypeLength();
                PlainDecoder.check(s.from(), s.to(), (long) size * width);
                int[] from = new int[size];
                int[] length = new int[size];
                PlainDecoder.fixed(b, s.from(), s.to(), width, size, from, length);
                yield copy(b, from, length);
            }
            case BOOLEAN -> throw new IllegalArgumentException("A dictionary of booleans is not supported");
        };
    }

    private static PageDictionary copy(byte[] b, int[] from, int[] length) {
        int size = from.length;
        int[] offsets = new int[size + 1];
        long total = 0;
        for (int i = 0; i < size; i++) {
            total += length[i];
        }
        byte[] data = new byte[Math.toIntExact(total)];
        for (int i = 0; i < size; i++) {
            System.arraycopy(b, from[i], data, offsets[i], length[i]);
            offsets[i + 1] = offsets[i] + length[i];
        }
        return new PageDictionary(size, null, data, offsets);
    }

    int size() {
        return size;
    }

    /**
     * Returns the dictionary as a page in the {@code PLAIN} encoding again,
     * over arrays of its own, for a column reader of parquet-java.
     */
    DictionaryPage page(PrimitiveType.PrimitiveTypeName physical) {
        byte[] b;
        switch (physical) {
            case INT32, FLOAT -> {
                b = new byte[Math.multiplyExact(size, 4)];
                for (int i = 0; i < size; i++) {
                    PlainDecoder.INT.set(b, 4 * i, (int) values[i]);
                }
            }
            case INT64, DOUBLE -> {
                b = new byte[Math.multiplyExact(size, 8)];
                for (int i = 0; i < size; i++) {
                    PlainDecoder.LONG.set(b, 8 * i, values[i]);
                }
            }
            case BINARY -> {
                b = new byte[Math.addExact(Math.multiplyExact(size, 4), data.length)];
                int pos = 0;
                for (int i = 0; i < size; i++) {
                    int length = offsets[i + 1] - offsets[i];
                    PlainDecoder.INT.set(b, pos, length);
                    System.arraycopy(data, offsets[i], b, pos + 4, length);
                    pos += 4 + length;
                }
            }
            default -> b = data.clone();
        }
        return new DictionaryPage(BytesInput.from(b), size, Encoding.PLAIN);
    }

    /** Returns the values of a dictionary of numbers. */
    long[] values() {
        return values;
    }

    /** Returns the bytes of a dictionary of byte arrays. */
    byte[] data() {
        return data;
    }

    /** Returns where each value of a dictionary of byte arrays starts, and its end. */
    int[] offsets() {
        return offsets;
    }

    /**
     * Checks dictionary ids.
     *
     * @throws IllegalArgumentException if one of {@code count} ids from
     *         index {@code from} on is not that of a value
     */
    void check(int[] ids, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (Integer.compareUnsigned(ids[i], size) >= 0) {
                throw new IllegalArgumentException("The dictionary id " + Integer.toUnsignedString(ids[i])
                        + " is beyond the " + size + " values of the dictionary");
            }
        }
    }
}
