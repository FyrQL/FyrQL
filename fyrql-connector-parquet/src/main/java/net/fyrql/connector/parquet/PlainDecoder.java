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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes values in the {@code PLAIN} encoding of Parquet: integers and
 * floating point numbers little-endian in 4 or 8 bytes, booleans as bits
 * from the least significant bit on, byte arrays of a fixed length one
 * after the other, and other byte arrays each after its length in 4
 * bytes. A floating point number is decoded as its bits, a 4-byte value
 * sign extended to a {@code long}. A byte array is located, not copied.
 */
final class PlainDecoder {

    static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle BIG_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private PlainDecoder() {
    }

    /** Bytes of a page: {@code array[from]} up to {@code array[to]}, exclusive. */
    record Slice(byte[] array, int from, int to) {
    }

    /** Returns the bytes of an input, without a copy if they are in a heap buffer. */
    static Slice slice(BytesInput input) {
        try {
            ByteBuffer buffer = input.toByteBuffer();
            if (buffer.hasArray()) {
                int from = buffer.arrayOffset() + buffer.position();
                return new Slice(buffer.array(), from, from + buffer.remaining());
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            return new Slice(bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes values of 4 bytes into {@code out[at]} on.
     *
     * @return the position after the values
     * @throws IllegalArgumentException if the bytes end before the values
     */
    static int ints(byte[] b, int pos, int end, int count, long[] out, int at) {
        check(pos, end, (long) count * 4);
        for (int i = 0; i < count; i++) {
            out[at + i] = (int) INT.get(b, pos + 4 * i);
        }
        return pos + 4 * count;
    }

    /**
     * Decodes values of 8 bytes into {@code out[at]} on.
     *
     * @return the position after the values
     * @throws IllegalArgumentException if the bytes end before the values
     */
    static int longs(byte[] b, int pos, int end, int count, long[] out, int at) {
        check(pos, end, (long) count * 8);
        for (int i = 0; i < count; i++) {
            out[at + i] = (long) LONG.get(b, pos + 8 * i);
        }
        return pos + 8 * count;
    }

    /**
     * Decodes booleans as 0 or 1 into {@code out[at]} on, from a bit of the
     * bytes that start at a position.
     *
     * @return the bit after the values
     * @throws IllegalArgumentException if the bytes end before the values
     */
    static long booleans(byte[] b, int pos, int end, long bit, int count, long[] out, int at) {
        check(pos, end, (bit + count + 7) >>> 3);
        for (int i = 0; i < count; i++) {
            long k = bit + i;
            out[at + i] = b[pos + (int) (k >>> 3)] >>> (k & 7) & 1;
        }
        return bit + count;
    }

    /**
     * Locates byte arrays, each after its length in 4 bytes.
     *
     * @param from takes where each array starts
     * @param length takes the length of each array
     * @return the position after the arrays
     * @throws IllegalArgumentException if the bytes end before the arrays
     */
    static int binaries(byte[] b, int pos, int end, int count, int[] from, int[] length) {
        for (int i = 0; i < count; i++) {
            if (end - pos < 4) {
                throw truncated();
            }
            int n = (int) INT.get(b, pos);
            pos += 4;
            if (n < 0 || n > end - pos) {
                throw truncated();
            }
            from[i] = pos;
            length[i] = n;
            pos += n;
        }
        return pos;
    }

    /**
     * Locates byte arrays of a fixed length.
     *
     * @return the position after the arrays
     * @throws IllegalArgumentException if the bytes end before the arrays
     */
    static int fixed(byte[] b, int pos, int end, int width, int count, int[] from, int[] length) {
        check(pos, end, (long) count * width);
        for (int i = 0; i < count; i++) {
            from[i] = pos + i * width;
            length[i] = width;
        }
        return pos + count * width;
    }

    /** Throws unless the bytes from a position hold at least a number of bytes. */
    static void check(int pos, int end, long bytes) {
        if (bytes > end - pos) {
            throw truncated();
        }
    }

    static IllegalArgumentException truncated() {
        return new IllegalArgumentException("The page ends before its values");
    }
}
