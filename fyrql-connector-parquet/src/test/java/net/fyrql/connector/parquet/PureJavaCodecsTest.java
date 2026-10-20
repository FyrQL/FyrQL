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
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The codecs in pure Java: each compresses and decompresses a page, writes
 * a file that DuckDB reads, and needs no Hadoop class at run time.
 */
class PureJavaCodecsTest {

    @TempDir
    Path dir;

    @Test
    void everyCodecRoundTrips() throws Exception {
        byte[] page = "a page of Parquet, a page of Parquet, a page of Parquet".repeat(100)
                .getBytes(StandardCharsets.UTF_8);
        PureJavaCodecs codecs = new PureJavaCodecs();
        for (CompressionCodecName codec : PureJavaCodecs.SUPPORTED) {
            CompressionCodecFactory.BytesInputCompressor compressor = codecs.getCompressor(codec);
            assertEquals(codec, compressor.getCodecName());
            byte[] compressed = PureJavaCodecs.bytes(compressor.compress(BytesInput.from(page)));
            CompressionCodecFactory.BytesInputDecompressor decompressor = codecs.getDecompressor(codec);
            assertArrayEquals(page, PureJavaCodecs.bytes(decompressor.decompress(BytesInput.from(compressed),
                    page.length)), codec.name());
            ByteBuffer out = ByteBuffer.allocateDirect(page.length);
            decompressor.decompress(ByteBuffer.wrap(compressed), compressed.length, out, page.length);
            byte[] direct = new byte[page.length];
            out.get(direct);
            assertArrayEquals(page, direct, codec.name());
        }
    }

    @Test
    void duckDbReadsTheFilesOfEveryCodec() throws Exception {
        for (CompressionCodecName codec : PureJavaCodecs.SUPPORTED) {
            Path file = ParquetTestFiles.write(dir.resolve(codec.name() + ".parquet"),
                    "message m { required int64 id; optional binary name (STRING); }", codec, w -> {
                        for (long i = 0; i < 1000; i++) {
                            long id = i;
                            w.row(g -> {
                                g.add("id", id);
                                if (id % 3 != 0) {
                                    g.add("name", "name-" + id);
                                }
                            });
                        }
                    });
            assertEquals(List.of(List.of("1000", "499500", "666")), ParquetTestFiles.duckdbRows(
                    "SELECT count(*), sum(id), count(name) FROM read_parquet(" + ParquetTestFiles.sql(file) + ")"),
                    codec.name());
            assertEquals(codec, ParquetTestFiles.footer(file).metadata().getBlocks().get(0).getColumns().get(0)
                    .getCodec());
        }
    }

    @Test
    void otherCodecsAreNamedInTheError() {
        assertEquals("The codec BROTLI is not supported; supported are UNCOMPRESSED, SNAPPY, GZIP, ZSTD, LZ4_RAW",
                assertThrows(UnsupportedOperationException.class,
                        () -> new PureJavaCodecs().getDecompressor(CompressionCodecName.BROTLI)).getMessage());
        assertThrows(UnsupportedOperationException.class,
                () -> new PureJavaCodecs().getCompressor(CompressionCodecName.LZ4));
        // A class of hadoop-common that the stubs do not have: only javac sees Hadoop.
        assertThrows(ClassNotFoundException.class, () -> Class.forName(String.join(".", "org", "apache", "hadoop",
                "fs", "LocalFileSystem")));
    }
}
