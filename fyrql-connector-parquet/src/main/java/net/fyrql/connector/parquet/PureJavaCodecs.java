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
 * <p>
 * A decompressor reads a page from its heap buffer without a copy. After
 * {@link #reuseBuffers}, each decompressor decompresses every page into the
 * same array, as the contract of {@link BytesInputDecompressor} allows: a
 * page must then be consumed before the next one is decompressed, which
 * {@link ColumnDecoder} does and the dictionaries of parquet-java do not.
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

    private boolean reuseBuffers;

    /**
     * Sets whether the decompressors of these codecs decompress every page
     * into the same array, from the next page on; at first they do not. A
     * reader sets it after it has read the column chunks, before the first
     * page is decompressed.
     */
    void reuseBuffers(boolean reuse) {
        this.reuseBuffers = reuse;
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
            case UNCOMPRESSED -> new Unblock(null, new Output());
            case SNAPPY -> new Unblock(new SnappyDecompressor(), new Output());
            case ZSTD -> new Unblock(new ZstdDecompressor(), new Output());
            case LZ4_RAW -> new Unblock(new Lz4Decompressor(), new Output());
            case GZIP -> new Gunzip(new Output());
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

    /** The arrays a decompressor decompresses into: a new one for each page, or always the same. */
    private final class Output {
        private byte[] buffer = new byte[0];

        /** Returns an array of at least a size. */
        byte[] get(int size) {
            if (!reuseBuffers) {
                return new byte[size];
            }
            if (buffer.length < size) {
                buffer = new byte[Math.max(size, buffer.length + (buffer.length >> 1))];
            }
            return buffer;
        }
    }

    /** Decompresses a page that was compressed as one block; an uncompressed page stays as it is. */
    private record Unblock(Decompressor decompressor, Output arrays) implements BytesInputDecompressor {
        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            if (decompressor == null) {
                return bytes;
            }
            PlainDecoder.Slice in = PlainDecoder.slice(bytes);
            byte[] out = arrays.get(uncompressedSize);
            decompress(in.array(), in.from(), in.to() - in.from(), out, uncompressedSize);
            return BytesInput.from(out, 0, uncompressedSize);
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
                throws IOException {
            byte[] in = bytes(input, compressedSize);
            if (decompressor == null) {
                put(in, output);
                return;
            }
            byte[] out = new byte[uncompressedSize];
            decompress(in, 0, in.length, out, uncompressedSize);
            put(out, output);
        }

        private void decompress(byte[] in, int from, int length, byte[] out, int uncompressedSize)
                throws IOException {
            int n = decompressor.decompress(in, from, length, out, 0, uncompressedSize);
            if (n != uncompressedSize) {
                throw new IOException("Decompressed " + n + " bytes, expected " + uncompressedSize);
            }
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

    private record Gunzip(Output arrays) implements BytesInputDecompressor {
        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            byte[] out = arrays.get(uncompressedSize);
            decompress(bytes.toInputStream(), out, uncompressedSize);
            return BytesInput.from(out, 0, uncompressedSize);
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
                throws IOException {
            byte[] out = new byte[uncompressedSize];
            decompress(new ByteArrayInputStream(bytes(input, compressedSize)), out, uncompressedSize);
            put(out, output);
        }

        private static void decompress(InputStream in, byte[] out, int uncompressedSize) throws IOException {
            try (GZIPInputStream gz = new GZIPInputStream(in)) {
                int n = gz.readNBytes(out, 0, uncompressedSize);
                if (n != uncompressedSize) {
                    throw new IOException("Decompressed " + n + " bytes, expected " + uncompressedSize);
                }
            }
        }

        @Override
        public void release() {
        }
    }
}
