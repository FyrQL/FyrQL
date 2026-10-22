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

import io.airlift.compress.snappy.SnappyDecompressor;
import io.airlift.compress.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * The codecs of the blocks of Avro container files, such as the manifests
 * of Iceberg tables: {@code null}, {@code deflate} through
 * {@code java.util.zip}, {@code snappy} and {@code zstandard} through
 * aircompressor, all in pure Java. They live in this module because
 * aircompressor is renamed into its JAR.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class AvroCodecs {

    /** The names of the codecs, as the metadata key {@code avro.codec} of a file gives them. */
    public static final List<String> SUPPORTED = List.of("null", "deflate", "snappy", "zstandard");

    private AvroCodecs() {
    }

    /**
     * Returns the bytes of a block decompressed.
     *
     * @param codec the codec of the file, {@code null} for none
     * @param block the bytes of the block as they are in the file
     * @throws IOException if the block is not valid for the codec, such as a
     *         {@code snappy} block whose checksum differs
     * @throws IllegalArgumentException if the codec is not one of {@link #SUPPORTED}
     */
    public static byte[] decompress(String codec, byte[] block) throws IOException {
        return switch (codec == null ? "null" : codec) {
            case "null" -> block;
            case "deflate" -> inflate(block);
            case "snappy" -> snappy(block);
            case "zstandard" -> zstandard(block);
            default -> throw new IllegalArgumentException("Unsupported Avro codec " + codec + "; supported are "
                    + String.join(", ", SUPPORTED));
        };
    }

    /** Raw deflate without a header, as Avro writes it. */
    private static byte[] inflate(byte[] block) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, block.length * 4));
        Inflater inflater = new Inflater(true);
        try (InflaterOutputStream inflating = new InflaterOutputStream(out, inflater)) {
            inflating.write(block);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** A snappy block followed by the CRC-32 of the uncompressed bytes, big-endian. */
    private static byte[] snappy(byte[] block) throws IOException {
        if (block.length < 4) {
            throw new IOException("A snappy block of " + block.length + " bytes has no checksum");
        }
        int length = block.length - 4;
        byte[] out;
        try {
            out = new byte[SnappyDecompressor.getUncompressedLength(block, 0)];
            int n = new SnappyDecompressor().decompress(block, 0, length, out, 0, out.length);
            if (n != out.length) {
                throw new IOException("Decompressed " + n + " bytes, expected " + out.length);
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid snappy block: " + e.getMessage(), e);
        }
        CRC32 crc = new CRC32();
        crc.update(out);
        long expected = (block[length] & 0xffL) << 24 | (block[length + 1] & 0xffL) << 16
                | (block[length + 2] & 0xffL) << 8 | block[length + 3] & 0xffL;
        if (crc.getValue() != expected) {
            throw new IOException("The checksum of a snappy block differs");
        }
        return out;
    }

    private static byte[] zstandard(byte[] block) throws IOException {
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(block))) {
            return in.readAllBytes();
        } catch (RuntimeException e) {
            throw new IOException("Invalid zstandard block: " + e.getMessage(), e);
        }
    }
}
