/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.parquet;

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
        return new SeekableInputStream() {
            @Override
            public int read() throws IOException {
                return file.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return file.read(bytes, offset, length);
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
            }

            @Override
            public void readFully(byte[] bytes, int offset, int length) throws IOException {
                file.readFully(bytes, offset, length);
            }

            @Override
            public int read(ByteBuffer buffer) throws IOException {
                if (!buffer.hasRemaining()) {
                    return 0;
                }
                if (buffer.hasArray()) {
                    int n = file.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                    if (n > 0) {
                        buffer.position(buffer.position() + n);
                    }
                    return n;
                }
                byte[] bytes = new byte[buffer.remaining()];
                int n = file.read(bytes);
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
