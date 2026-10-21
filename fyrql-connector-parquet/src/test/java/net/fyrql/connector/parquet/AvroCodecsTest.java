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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airlift.compress.snappy.SnappyCompressor;
import io.airlift.compress.zstd.ZstdCompressor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** The codecs of Avro blocks, against blocks compressed as Avro compresses them. */
class AvroCodecsTest {

    private static final byte[] DATA = ("manifest entries, ".repeat(400) + "the end").getBytes(StandardCharsets.UTF_8);

    @Test
    void everyCodecGivesTheBytesBack() throws IOException {
        assertSame(DATA, AvroCodecs.decompress("null", DATA));
        assertSame(DATA, AvroCodecs.decompress(null, DATA));
        assertArrayEquals(DATA, AvroCodecs.decompress("deflate", deflate(DATA)));
        assertArrayEquals(DATA, AvroCodecs.decompress("snappy", snappy(DATA)));
        assertArrayEquals(DATA, AvroCodecs.decompress("zstandard", zstandard(DATA)));
        assertArrayEquals(new byte[0], AvroCodecs.decompress("deflate", deflate(new byte[0])));
        assertArrayEquals(new byte[0], AvroCodecs.decompress("snappy", snappy(new byte[0])));
    }

    @Test
    void invalidBlocksAndUnknownCodecs() {
        byte[] snappy = snappy(DATA);
        snappy[snappy.length - 1] ^= 1;
        IOException checksum = assertThrows(IOException.class, () -> AvroCodecs.decompress("snappy", snappy));
        assertTrue(checksum.getMessage().contains("checksum"), checksum.getMessage());
        assertThrows(IOException.class, () -> AvroCodecs.decompress("snappy", new byte[] {1, 2}));
        assertThrows(IOException.class, () -> AvroCodecs.decompress("deflate", new byte[] {-1, -1, -1, -1}));
        assertThrows(IOException.class, () -> AvroCodecs.decompress("zstandard", Arrays.copyOf(DATA, 20)));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> AvroCodecs.decompress("bzip2", DATA));
        assertTrue(unknown.getMessage().contains("bzip2"), unknown.getMessage());
    }

    private static byte[] deflate(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION,
                true))) {
            d.write(data);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static byte[] snappy(byte[] data) {
        SnappyCompressor compressor = new SnappyCompressor();
        byte[] out = new byte[compressor.maxCompressedLength(data.length) + 4];
        int n = compressor.compress(data, 0, data.length, out, 0, out.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer.wrap(out, n, 4).putInt((int) crc.getValue());
        return Arrays.copyOf(out, n + 4);
    }

    private static byte[] zstandard(byte[] data) {
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] out = new byte[compressor.maxCompressedLength(data.length)];
        int n = compressor.compress(data, 0, data.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }
}
