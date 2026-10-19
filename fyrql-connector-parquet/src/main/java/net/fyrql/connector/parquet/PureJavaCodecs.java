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

import io.airlift.compress.Compressor;
import io.airlift.compress.Decompressor;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4Decompressor;
import io.airlift.compress.snappy.SnappyCompressor;
import io.airlift.compress.snappy.SnappyDecompressor;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The codecs of Parquet in pure Java: {@code SNAPPY}, {@code ZSTD} and
 * {@code LZ4_RAW} through aircompressor, {@code GZIP} through
 * {@code java.util.zip}, and {@code UNCOMPRESSED}. The factory of
 * parquet-java needs snappy-java and zstd-jni, both native, and the codecs
 * of Hadoop for the rest. {@code LZ4} in the framing of Hadoop,
 * {@code BROTLI} and {@code LZO} are not supported.
 */
final class PureJavaCodecs implements CompressionCodecFactory {

    /** The codecs this factory reads and writes. */
    static final List<CompressionCodecName> SUPPORTED = List.of(CompressionCodecName.UNCOMPRESSED,
            CompressionCodecName.SNAPPY, CompressionCodecName.GZIP, CompressionCodecName.ZSTD,
            CompressionCodecName.LZ4_RAW);

    /** Tells whether the factory reads and writes a codec. */
    static boolean supports(CompressionCodecName name) {
        return SUPPORTED.contains(name);
    }

    /** Returns the message for a codec that is not supported. */
    static String unsupported(CompressionCodecName name) {
        return "The codec " + name + " is not supported; supported are "
                + String.join(", ", SUPPORTED.stream().map(Enum::name).toList());
    }

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName name) {
        return switch (name) {
            case UNCOMPRESSED -> new Block(name, null);
            case SNAPPY -> new Block(name, new SnappyCompressor());
            case ZSTD -> new Block(name, new ZstdCompressor());
            case LZ4_RAW -> new Block(name, new Lz4Compressor());
            case GZIP -> new Gzip();
            default -> throw new UnsupportedOperationException(unsupported(name));
        };
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName name) {
        return switch (name) {
            case UNCOMPRESSED -> new Unblock(null);
            case SNAPPY -> new Unblock(new SnappyDecompressor());
            case ZSTD -> new Unblock(new ZstdDecompressor());
            case LZ4_RAW -> new Unblock(new Lz4Decompressor());
            case GZIP -> new Gunzip();
            default -> throw new UnsupportedOperationException(unsupported(name));
        };
    }

    @Override
    public void release() {
    }

    /** Returns the bytes of an input in an array of their own. */
    static byte[] bytes(BytesInput input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) input.size());
        input.writeAllTo(out);
        return out.toByteArray();
    }

    /** Returns the bytes of a direct or heap buffer, from its position on. */
    private static byte[] bytes(ByteBuffer input, int size) {
        byte[] in = new byte[size];
        input.get(in);
        return in;
    }

    private static void put(byte[] bytes, ByteBuffer output) {
        output.clear();
        output.put(bytes);
        output.flip();
    }

    /** Compresses a page as one block. */
    private record Block(CompressionCodecName name, Compressor compressor) implements BytesInputCompressor {
        @Override
        public BytesInput compress(BytesInput bytes) throws IOException {
            byte[] in = bytes(bytes);
            if (compressor == null) {
                return BytesInput.from(in);
            }
            byte[] out = new byte[compressor.maxCompressedLength(in.length)];
            int n = compressor.compress(in, 0, in.length, out, 0, out.length);
            return BytesInput.from(out, 0, n);
        }

        @Override
        public CompressionCodecName getCodecName() {
            return name;
        }

        @Override
        public void release() {
        }
    }

    /** Decompresses a page that was compressed as one block. */
    private record Unblock(Decompressor decompressor) implements BytesInputDecompressor {
        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            return BytesInput.from(decompress(bytes(bytes), uncompressedSize));
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
                throws IOException {
            put(decompress(bytes(input, compressedSize), uncompressedSize), output);
        }

        private byte[] decompress(byte[] in, int uncompressedSize) throws IOException {
            if (decompressor == null) {
                return in;
            }
            byte[] out = new byte[uncompressedSize];
            int n = decompressor.decompress(in, 0, in.length, out, 0, out.length);
            if (n != uncompressedSize) {
                throw new IOException("Decompressed " + n + " bytes, expected " + uncompressedSize);
            }
            return out;
        }

        @Override
        public void release() {
        }
    }

    private static final class Gzip implements BytesInputCompressor {
        @Override
        public BytesInput compress(BytesInput bytes) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
                bytes.writeAllTo(gz);
            }
            return BytesInput.from(buffer.toByteArray());
        }

        @Override
        public CompressionCodecName getCodecName() {
            return CompressionCodecName.GZIP;
        }

        @Override
        public void release() {
        }
    }

    private static final class Gunzip implements BytesInputDecompressor {
        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            return BytesInput.from(decompress(bytes.toInputStream(), uncompressedSize));
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
                throws IOException {
            put(decompress(new ByteArrayInputStream(bytes(input, compressedSize)), uncompressedSize), output);
        }

        private static byte[] decompress(InputStream in, int uncompressedSize) throws IOException {
            try (GZIPInputStream gz = new GZIPInputStream(in)) {
                byte[] out = gz.readNBytes(uncompressedSize);
                if (out.length != uncompressedSize) {
                    throw new IOException("Decompressed " + out.length + " bytes, expected " + uncompressedSize);
                }
                return out;
            }
        }

        @Override
        public void release() {
        }
    }
}
