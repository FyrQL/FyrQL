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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

/** Deletion vectors as Iceberg Java writes them, and Roaring bitmaps with every kind of container. */
class PositionBitmapTest {

    @Test
    void theDeletionVectorOfATable() throws IOException {
        IcebergTestTables tables = IcebergTestTables.shared();
        Table table = tables.table("dv_v3");
        List<DeleteFile> deletes = new ArrayList<>();
        for (ManifestFile manifest : table.currentSnapshot().deleteManifests(table.io())) {
            try (CloseableIterable<DeleteFile> files = ManifestFiles.readDeleteManifest(manifest, table.io(),
                    table.specs())) {
                files.forEach(deletes::add);
            }
        }
        assertEquals(1, deletes.size());
        DeleteFile dv = deletes.get(0);
        assertTrue(dv.location().endsWith(".puffin"), dv.location());
        byte[] blob = new byte[Math.toIntExact(dv.contentSizeInBytes())];
        try (RandomAccessFile file = new RandomAccessFile(dv.location(), "r")) {
            file.seek(dv.contentOffset());
            file.readFully(blob);
        }
        assertArrayEquals(new long[] {0, 9}, PositionBitmap.positions(blob));
    }

    @Test
    void containersOfEveryKindUnderSeveralKeys() throws IOException {
        List<Long> all = new ArrayList<>();
        // An array container, a bitmap container and a run container under key 0.
        for (long v : new long[] {1, 5, 70_000}) {
            all.add(v);
        }
        for (long v = 2L << 16; v < (2L << 16) + 10_000; v += 2) {
            all.add(v);
        }
        for (long v = 3L << 16; v <= (3L << 16) + 300; v++) {
            all.add(v);
        }
        // Positions beyond 32 bits, up to the largest key.
        all.add(5_000_000_000L);
        all.add(0xFFFF_FFFFL << 32 | 0xFFFF);
        for (boolean runs : new boolean[] {true, false}) {
            Map<Long, RoaringBitmap> byKey = new TreeMap<>();
            for (long p : all) {
                byKey.computeIfAbsent(p >>> 32, k -> new RoaringBitmap()).add((int) p);
            }
            long[] expected = all.stream().sorted(Long::compareUnsigned).mapToLong(Long::longValue).toArray();
            assertArrayEquals(expected, PositionBitmap.positions(blob(byKey, runs)), "runs " + runs);
        }
    }

    @Test
    void aBitmapWithFourContainersOrMoreHasOffsets() throws IOException {
        Map<Long, RoaringBitmap> byKey = new TreeMap<>();
        RoaringBitmap bitmap = new RoaringBitmap();
        List<Long> expected = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            for (int v = 0; v < 50; v++) {
                bitmap.add(c << 16 | v);
                expected.add((long) (c << 16 | v));
            }
        }
        byKey.put(0L, bitmap);
        assertArrayEquals(expected.stream().mapToLong(Long::longValue).toArray(),
                PositionBitmap.positions(blob(byKey, true)));
        assertArrayEquals(new long[0], PositionBitmap.positions(blob(new TreeMap<>(), false)));
    }

    @Test
    void invalidBlobs() throws IOException {
        Map<Long, RoaringBitmap> byKey = new TreeMap<>();
        byKey.put(0L, RoaringBitmap.bitmapOf(1, 2, 3));
        byte[] good = blob(byKey, false);
        byte[] crc = good.clone();
        crc[crc.length - 1] ^= 1;
        assertTrue(assertThrows(IOException.class, () -> PositionBitmap.positions(crc)).getMessage()
                .contains("checksum"));
        byte[] magic = good.clone();
        magic[4] = 0;
        assertThrows(IOException.class, () -> PositionBitmap.positions(magic));
        byte[] length = good.clone();
        length[3]++;
        assertThrows(IOException.class, () -> PositionBitmap.positions(length));
        assertThrows(IOException.class, () -> PositionBitmap.positions(new byte[3]));
    }

    /** Returns the blob of a deletion vector over 32-bit bitmaps by their keys. */
    private static byte[] blob(Map<Long, RoaringBitmap> byKey, boolean runs) {
        List<byte[]> parts = new ArrayList<>();
        int size = 8;
        for (Map.Entry<Long, RoaringBitmap> e : byKey.entrySet()) {
            RoaringBitmap bitmap = e.getValue().clone();
            if (runs) {
                bitmap.runOptimize();
            } else {
                bitmap.removeRunCompression();
            }
            ByteBuffer b = ByteBuffer.allocate(4 + bitmap.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(e.getKey().intValue());
            bitmap.serialize(b);
            parts.add(b.array());
            size += b.capacity();
        }
        ByteBuffer bitmap = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        bitmap.putLong(byKey.size());
        parts.forEach(bitmap::put);
        ByteBuffer blob = ByteBuffer.allocate(12 + size);
        blob.putInt(4 + size);
        blob.putInt(0xD1D33964);
        blob.put(bitmap.array());
        CRC32 crc = new CRC32();
        crc.update(blob.array(), 4, 4 + size);
        blob.putInt((int) crc.getValue());
        return blob.array();
    }
}
