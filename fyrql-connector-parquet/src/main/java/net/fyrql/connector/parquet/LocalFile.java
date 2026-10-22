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

import net.fyrql.connector.SourceCounters;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * A local Parquet file for the reader. Unlike the stream of
 * {@code LocalInputFile}, whose {@code read(byte[], int, int)} reads one
 * byte at a time, the stream reads a range of bytes at once, which matters
 * where the reader reads through {@code InputStream}, as for dictionaries.
 * The bytes read count for the scan the stream was opened for, see
 * {@link SourceCounters}.
 */
final class LocalFile implements InputFile {

    private final Path path;
    private long length = -1;

    LocalFile(Path path) {
        this.path = path;
    }

    @Override
    public long getLength() throws IOException {
        if (length < 0) {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                length = file.length();
            }
        }
        return length;
    }

    @Override
    public SeekableInputStream newStream() throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        SourceCounters counters = SourceCounters.current();
        return new SeekableInputStream() {
            @Override
            public int read() throws IOException {
                int b = file.read();
                if (b >= 0) {
                    counters.bytes(1);
                }
                return b;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return counted(file.read(bytes, offset, length));
            }

            private int counted(int n) {
                if (n > 0) {
                    counters.bytes(n);
                }
                return n;
            }

            @Override
            public long getPos() throws IOException {
                return file.getFilePointer();
            }

            @Override
            public void seek(long position) throws IOException {
                file.seek(position);
            }

            @Override
            public void readFully(byte[] bytes) throws IOException {
                file.readFully(bytes);
                counters.bytes(bytes.length);
            }

            @Override
            public void readFully(byte[] bytes, int offset, int length) throws IOException {
                file.readFully(bytes, offset, length);
                counters.bytes(length);
            }

            @Override
            public int read(ByteBuffer buffer) throws IOException {
                if (!buffer.hasRemaining()) {
                    return 0;
                }
                if (buffer.hasArray()) {
                    int n = counted(file.read(buffer.array(), buffer.arrayOffset() + buffer.position(),
                            buffer.remaining()));
                    if (n > 0) {
                        buffer.position(buffer.position() + n);
                    }
                    return n;
                }
                byte[] bytes = new byte[buffer.remaining()];
                int n = counted(file.read(bytes));
                if (n > 0) {
                    buffer.put(bytes, 0, n);
                }
                return n;
            }

            @Override
            public void readFully(ByteBuffer buffer) throws IOException {
                while (buffer.hasRemaining()) {
                    if (read(buffer) < 0) {
                        throw new EOFException("The Parquet file " + path + " ended early");
                    }
                }
            }

            @Override
            public void close() throws IOException {
                file.close();
            }
        };
    }
}
