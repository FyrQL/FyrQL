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

package net.fyrql.connector.iceberg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * The deleted positions of a deletion vector: the blob
 * {@code deletion-vector-v1} of a Puffin file, which is the length of the
 * magic number and the bitmap as four bytes big-endian, the magic number
 * {@code D1 D3 39 64}, a 64-bit Roaring bitmap in its portable form, and
 * the CRC-32 of the magic number and the bitmap, big-endian.
 * <p>
 * The 64-bit bitmap is the number of 32-bit bitmaps as eight bytes and,
 * for each, the upper 32 bits of its positions as four bytes, all
 * little-endian, followed by the 32-bit bitmap in the portable format of
 * Roaring with array, bitmap and run containers.
 */
final class PositionBitmap {

    private static final int MAGIC = 0xD1D33964;
    private static final int SERIAL_COOKIE_NO_RUNCONTAINER = 12346;
    private static final int SERIAL_COOKIE = 12347;
    private static final int NO_OFFSET_THRESHOLD = 4;
    private static final int ARRAY_MAX = 4096;

    private PositionBitmap() {
    }

    /**
     * Returns the positions of a deletion vector, ascending.
     *
     * @param blob the bytes of the blob, as the manifest entry locates them
     * @throws IOException if the blob is not a valid deletion vector
     */
    static long[] positions(byte[] blob) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN);
        try {
            int length = b.getInt();
            if (length < 4 || length != blob.length - 8) {
                throw new IOException("A deletion vector of " + blob.length + " bytes says it has " + length);
            }
            if (b.getInt() != MAGIC) {
                throw new IOException("A deletion vector without its magic number");
            }
            CRC32 crc = new CRC32();
            crc.update(blob, 4, length);
            if ((int) crc.getValue() != ByteBuffer.wrap(blob, 4 + length, 4).getInt()) {
                throw new IOException("The checksum of a deletion vector differs");
            }
            ByteBuffer bitmap = ByteBuffer.wrap(blob, 8, length - 4).slice().order(ByteOrder.LITTLE_ENDIAN);
            return bitmap64(bitmap);
        } catch (RuntimeException e) {
            throw new IOException("Invalid deletion vector: " + e, e);
        }
    }

    /** Reads a 64-bit Roaring bitmap in its portable form. */
    static long[] bitmap64(ByteBuffer b) throws IOException {
        long count = b.getLong();
        if (count < 0 || count > b.remaining() / 4) {
            throw new IOException("Invalid number of bitmaps " + count);
        }
        long[] positions = new long[16];
        int size = 0;
        long lastKey = -1;
        for (long k = 0; k < count; k++) {
            long key = Integer.toUnsignedLong(b.getInt());
            if (key <= lastKey) {
                throw new IOException("The keys of a 64-bit bitmap are not ascending");
            }
            lastKey = key;
            int[] low = bitmap32(b);
            if (size + low.length > positions.length) {
                positions = Arrays.copyOf(positions, Math.max(size + low.length, positions.length * 2));
            }
            for (int v : low) {
                positions[size++] = key << 32 | Integer.toUnsignedLong(v);
            }
        }
        if (b.hasRemaining()) {
            throw new IOException(b.remaining() + " bytes after the bitmaps");
        }
        return Arrays.copyOf(positions, size);
    }

    /** Reads a 32-bit Roaring bitmap in its portable form; the values are unsigned. */
    static int[] bitmap32(ByteBuffer b) throws IOException {
        int cookie = b.getInt();
        int containers;
        byte[] runs = null;
        if ((cookie & 0xFFFF) == SERIAL_COOKIE) {
            containers = (cookie >>> 16) + 1;
            runs = new byte[(containers + 7) / 8];
            b.get(runs);
        } else if (cookie == SERIAL_COOKIE_NO_RUNCONTAINER) {
            containers = b.getInt();
        } else {
            throw new IOException("Not a Roaring bitmap: cookie " + cookie);
        }
        if (containers < 0 || containers > 1 << 16) {
            throw new IOException("Invalid number of containers " + containers);
        }
        int[] keys = new int[containers];
        int[] cardinalities = new int[containers];
        int total = 0;
        for (int c = 0; c < containers; c++) {
            keys[c] = Short.toUnsignedInt(b.getShort());
            cardinalities[c] = Short.toUnsignedInt(b.getShort()) + 1;
            total += cardinalities[c];
        }
        if (runs == null || containers >= NO_OFFSET_THRESHOLD) {
            b.position(b.position() + 4 * containers);
        }
        int[] values = new int[total];
        int n = 0;
        for (int c = 0; c < containers; c++) {
            int high = keys[c] << 16;
            if (runs != null && (runs[c / 8] & 1 << c % 8) != 0) {
                int pairs = Short.toUnsignedInt(b.getShort());
                for (int r = 0; r < pairs; r++) {
                    int start = Short.toUnsignedInt(b.getShort());
                    int length = Short.toUnsignedInt(b.getShort());
                    for (int v = start; v <= start + length; v++) {
                        values[n++] = high | v;
                    }
                }
            } else if (cardinalities[c] <= ARRAY_MAX) {
                for (int i = 0; i < cardinalities[c]; i++) {
                    values[n++] = high | Short.toUnsignedInt(b.getShort());
                }
            } else {
                for (int w = 0; w < 1024; w++) {
                    long word = b.getLong();
                    while (word != 0) {
                        values[n++] = high | w * 64 + Long.numberOfTrailingZeros(word);
                        word &= word - 1;
                    }
                }
            }
        }
        if (n != total) {
            throw new IOException("The containers hold " + n + " values, their headers say " + total);
        }
        return values;
    }
}
