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

import net.fyrql.connector.FileObject;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A Parquet file of a store for the reader. A local file is read through
 * {@link LocalFile}. Any other file is read in ranges, each one request to
 * the store. A read of a known length, such as the column chunks of a row
 * group, goes to the store as it is, unless a buffer already holds it. A
 * read that may return fewer bytes, such as of the header of a page, of a
 * dictionary or of an index, fills a buffer of {@code readAhead} bytes
 * from its position; a read of the last eight bytes, the length of the
 * footer and the magic, fills it with the tail of the file, so that the
 * footer usually takes one request.
 */
final class StoreFile implements InputFile {

    /** The size from which a read that may return fewer bytes goes to the store directly. */
    static final int DIRECT = 1 << 16;

    /** The bytes at the end of a file: the length of the footer and the magic. */
    private static final int TAIL = 8;

    private final FileObject file;
    private final int readAhead;

    StoreFile(FileObject file, int readAhead) {
        this.file = file;
        this.readAhead = readAhead;
    }

    @Override
    public long getLength() {
        return file.size();
    }

    @Override
    public SeekableInputStream newStream() throws IOException {
        Optional<Path> path = file.path();
        if (path.isPresent()) {
            return new LocalFile(path.get()).newStream();
        }
        return new RangeStream();
    }

    @Override
    public String toString() {
        return file.location();
    }

    /** A stream that reads ranges of the file through a buffer. */
    private final class RangeStream extends SeekableInputStream {
        private final long size = file.size();
        private byte[] buffer;
        /** The position in the file of the first byte of the buffer. */
        private long start;
        /** The number of bytes in the buffer. */
        private int length;
        private long position;

        @Override
        public long getPos() {
            return position;
        }

        @Override
        public void seek(long newPosition) throws IOException {
            if (newPosition < 0 || newPosition > size) {
                throw new EOFException("Cannot seek to " + newPosition + " in " + file.location() + " of " + size
                        + " bytes");
            }
            position = newPosition;
        }

        @Override
        public int read() throws IOException {
            if (position >= size) {
                return -1;
            }
            if (!buffered()) {
                fill();
            }
            return buffer[(int) (position++ - start)] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            ByteBuffer target = ByteBuffer.wrap(bytes, offset, count);
            int n = read(target);
            return n;
        }

        /** Reads what the buffer holds from the position, after filling it if it holds nothing there. */
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            if (!buffered() && target.remaining() >= DIRECT) {
                int n = (int) Math.min(target.remaining(), size - position);
                readRange(position, target.slice(target.position(), n));
                target.position(target.position() + n);
                position += n;
                return n;
            }
            if (!buffered()) {
                fill();
            }
            int n = (int) Math.min(target.remaining(), start + length - position);
            target.put(buffer, (int) (position - start), n);
            position += n;
            return n;
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            readFully(ByteBuffer.wrap(bytes));
        }

        @Override
        public void readFully(byte[] bytes, int offset, int count) throws IOException {
            readFully(ByteBuffer.wrap(bytes, offset, count));
        }

        @Override
        public void readFully(ByteBuffer target) throws IOException {
            if (target.remaining() > size - position) {
                throw new EOFException("The Parquet file " + file.location() + " ended early");
            }
            while (target.hasRemaining()) {
                if (buffered()) {
                    int n = (int) Math.min(target.remaining(), start + length - position);
                    target.put(buffer, (int) (position - start), n);
                    position += n;
                } else if (size - position <= TAIL) {
                    fill();
                } else {
                    int n = target.remaining();
                    readRange(position, target.slice(target.position(), n));
                    target.position(target.position() + n);
                    position += n;
                }
            }
        }

        private boolean buffered() {
            return buffer != null && position >= start && position < start + length;
        }

        /** Fills the buffer from the position, or with the tail of the file for its last eight bytes. */
        private void fill() throws IOException {
            long from = size - position <= TAIL ? Math.max(0, size - readAhead) : position;
            int n = (int) Math.min(readAhead, size - from);
            if (buffer == null || buffer.length < n) {
                buffer = new byte[n];
            }
            length = 0;
            readRange(from, ByteBuffer.wrap(buffer, 0, n));
            start = from;
            length = n;
        }

        /** Reads a range of the file into a buffer, which it fills completely. */
        private void readRange(long from, ByteBuffer target) throws IOException {
            long at = from;
            while (target.hasRemaining()) {
                int n = file.read(at, target);
                if (n <= 0) {
                    throw new EOFException("The Parquet file " + file.location() + " ended early");
                }
                at += n;
            }
        }

        @Override
        public void close() {
            buffer = null;
        }
    }
}
