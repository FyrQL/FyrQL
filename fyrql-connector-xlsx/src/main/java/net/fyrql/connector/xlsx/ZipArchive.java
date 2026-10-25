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
package net.fyrql.connector.xlsx;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.SourceCounters;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * The entries of a zip archive, read through {@link FileObject#read} from
 * the central directory at its end, so that a local file and an object in
 * a bucket are read the same way, without a copy: an entry of a local file
 * through one channel, of another file in pieces of 1 MiB, each a request.
 * An entry is stored or deflated; Zip64, encryption and other methods are
 * refused.
 * <p>
 * The entries guard against archives built to exhaust memory: an entry
 * fails as soon as it inflates to more than its size in the directory, and
 * also as soon as it inflates to more than {@link #MAX_RATIO} times its
 * compressed size beyond {@link #MAX_PLAIN_SIZE} bytes. Its checksum is
 * checked at its end.
 */
final class ZipArchive {

    /** The ratio of inflated to compressed size above which a large entry fails. */
    static final long MAX_RATIO = 100;

    /** The size up to which an entry may inflate at any ratio. */
    static final long MAX_PLAIN_SIZE = 64L << 20;

    private static final int END_SIGNATURE = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int END_SIZE = 22;
    private static final int LOCAL_SIZE = 30;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;
    /** The size of the pieces of compressed bytes read from a local file. */
    private static final int LOCAL_READ_SIZE = 64 << 10;
    /** The size of the pieces read from a file in another store, each a request of its own. */
    private static final int REMOTE_READ_SIZE = 1 << 20;

    /** An entry as the central directory describes it. */
    record Entry(String name, int method, int flags, long crc, long compressedSize, long size, long offset) {}

    private final FileObject file;
    private final Map<String, Entry> entries;

    private ZipArchive(FileObject file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    /**
     * Reads the central directory of a file.
     *
     * @throws ZipException if the file is no zip archive this class reads
     */
    static ZipArchive open(FileObject file) throws IOException {
        long size = file.size();
        if (size >= 8) {
            ByteBuffer head = read(file, 0, 8);
            if (head.order(ByteOrder.BIG_ENDIAN).getLong(0) == 0xd0cf11e0a1b11ae1L) {
                throw new ZipException(file.location() + " is an encrypted workbook or an old .xls workbook,"
                        + " which are not supported; save it as an unencrypted .xlsx workbook");
            }
        }
        if (size < END_SIZE) {
            throw invalid(file, "too short for a zip archive");
        }
        int tail = (int) Math.min(size, END_SIZE + 0xffff);
        ByteBuffer end = read(file, size - tail, tail);
        int at = -1;
        for (int i = tail - END_SIZE; i >= 0; i--) {
            if (end.getInt(i) == END_SIGNATURE && i + END_SIZE + (end.getShort(i + 20) & 0xffff) <= tail) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            throw invalid(file, "no end of a central directory");
        }
        if (at >= 20 && end.getInt(at - 20) == ZIP64_LOCATOR_SIGNATURE) {
            throw zip64(file);
        }
        int disk = end.getShort(at + 4) & 0xffff;
        int directoryDisk = end.getShort(at + 6) & 0xffff;
        int count = end.getShort(at + 10) & 0xffff;
        long directorySize = end.getInt(at + 12) & 0xffffffffL;
        long directoryOffset = end.getInt(at + 16) & 0xffffffffL;
        if (count == 0xffff || directorySize == 0xffffffffL || directoryOffset == 0xffffffffL) {
            throw zip64(file);
        }
        if (disk != 0 || directoryDisk != 0) {
            throw invalid(file, "split over several files");
        }
        long endOffset = size - tail + at;
        if (directoryOffset + directorySize > endOffset) {
            throw invalid(file, "a central directory past its end");
        }
        ByteBuffer directory = read(file, directoryOffset, (int) directorySize);
        Map<String, Entry> entries = new LinkedHashMap<>();
        int p = 0;
        for (int n = 0; n < count; n++) {
            if (p + 46 > directorySize || directory.getInt(p) != CENTRAL_SIGNATURE) {
                throw invalid(file, "a damaged central directory");
            }
            int flags = directory.getShort(p + 8) & 0xffff;
            int method = directory.getShort(p + 10) & 0xffff;
            long crc = directory.getInt(p + 16) & 0xffffffffL;
            long compressed = directory.getInt(p + 20) & 0xffffffffL;
            long plain = directory.getInt(p + 24) & 0xffffffffL;
            int nameLength = directory.getShort(p + 28) & 0xffff;
            int extraLength = directory.getShort(p + 30) & 0xffff;
            int commentLength = directory.getShort(p + 32) & 0xffff;
            long offset = directory.getInt(p + 42) & 0xffffffffL;
            if (p + 46 + nameLength > directorySize) {
                throw invalid(file, "a damaged central directory");
            }
            byte[] nameBytes = new byte[nameLength];
            directory.get(p + 46, nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8).replace('\\', '/');
            if (compressed == 0xffffffffL || plain == 0xffffffffL || offset == 0xffffffffL) {
                throw zip64(file);
            }
            if (offset + LOCAL_SIZE + compressed > directoryOffset) {
                throw invalid(file, "the entry " + name + " past the start of the central directory");
            }
            if (entries.putIfAbsent(name, new Entry(name, method, flags, crc, compressed, plain, offset)) != null) {
                throw invalid(file, "the entry " + name + " twice");
            }
            p += 46 + nameLength + extraLength + commentLength;
        }
        return new ZipArchive(file, entries);
    }

    /** Returns the file of the archive. */
    FileObject file() {
        return file;
    }

    /** Returns the names of the entries in the order of the directory. */
    Set<String> names() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * Returns the name of the entry of a part name, compared exactly or else
     * ignoring case, as part names are; empty if there is none.
     */
    Optional<String> find(String name) {
        if (entries.containsKey(name)) {
            return Optional.of(name);
        }
        return entries.keySet().stream().filter(n -> n.equalsIgnoreCase(name)).findFirst();
    }

    /** Returns the size of an entry after inflating, as the directory gives it. */
    long size(String name) throws IOException {
        return entry(name).size();
    }

    /**
     * Opens a stream over the content of an entry, which fails at its end
     * if the checksum does not match.
     *
     * @throws java.nio.file.NoSuchFileException if there is no entry of the name
     * @throws ZipException if the entry is encrypted, compressed with another
     *         method than deflate, or damaged
     */
    InputStream open(String name) throws IOException {
        Entry e = entry(name);
        if ((e.flags() & 1) != 0) {
            throw new ZipException("The entry " + name + " of " + file.location() + " is encrypted, which is not"
                    + " supported");
        }
        if (e.method() != STORED && e.method() != DEFLATED) {
            throw new ZipException("The entry " + name + " of " + file.location() + " is compressed with the method "
                    + e.method() + ", which is not supported; supported are stored and deflate");
        }
        ByteBuffer local = read(file, e.offset(), LOCAL_SIZE);
        if (local.getInt(0) != LOCAL_SIGNATURE) {
            throw new ZipException("The entry " + name + " of " + file.location() + " has no local header");
        }
        long start = e.offset() + LOCAL_SIZE + (local.getShort(26) & 0xffff) + (local.getShort(28) & 0xffff);
        if (e.method() == STORED && e.compressedSize() != e.size()) {
            throw new ZipException("The stored entry " + name + " of " + file.location() + " has two sizes");
        }
        return new EntryStream(e, start);
    }

    private Entry entry(String name) throws IOException {
        Entry e = entries.get(name);
        if (e == null) {
            throw new NoSuchFileException(file.location() + "!/" + name);
        }
        return e;
    }

    private static ByteBuffer read(FileObject file, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int n = file.read(position + buffer.position(), buffer);
            if (n < 0) {
                throw new EOFException(file.location() + " ends early");
            }
        }
        return buffer.flip();
    }

    private static ZipException invalid(FileObject file, String reason) {
        return new ZipException(file.location() + " is no valid xlsx workbook: " + reason);
    }

    private static ZipException zip64(FileObject file) {
        return new ZipException(file.location() + " is a Zip64 archive, which is not supported");
    }

    /** The content of an entry: the compressed bytes read in pieces, inflated if deflated. */
    private final class EntryStream extends InputStream {

        private final Entry entry;
        private final long limit;
        private final Inflater inflater;
        private final CRC32 crc = new CRC32();
        private final ByteBuffer input;
        private FileChannel channel;
        private long position;
        private long remaining;
        private long produced;
        private boolean done;
        private boolean closed;

        EntryStream(Entry entry, long start) {
            this.entry = entry;
            this.position = start;
            this.remaining = entry.compressedSize();
            this.inflater = entry.method() == DEFLATED ? new Inflater(true) : null;
            this.limit = entry.size();
            int size = file.path().isPresent() ? LOCAL_READ_SIZE : REMOTE_READ_SIZE;
            this.input = ByteBuffer.allocate((int) Math.max(1, Math.min(size, entry.compressedSize())));
            input.limit(0);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (len == 0) {
                return 0;
            }
            if (done) {
                return -1;
            }
            int n = inflater == null ? readStored(b, off, len) : readDeflated(b, off, len);
            if (n < 0) {
                finish();
                return -1;
            }
            crc.update(b, off, n);
            produced += n;
            if (produced > limit) {
                throw fail("inflates to more than its size of " + limit + " bytes");
            }
            if (produced > MAX_PLAIN_SIZE && produced > MAX_RATIO * Math.max(1, entry.compressedSize())) {
                throw fail("inflates to more than " + MAX_RATIO + " times its compressed size");
            }
            return n;
        }

        private int readStored(byte[] b, int off, int len) throws IOException {
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, input.remaining());
            input.get(b, off, n);
            return n;
        }

        private int readDeflated(byte[] b, int off, int len) throws IOException {
            try {
                while (true) {
                    int n = inflater.inflate(b, off, len);
                    if (n > 0) {
                        return n;
                    }
                    if (inflater.finished()) {
                        return -1;
                    }
                    if (inflater.needsDictionary()) {
                        throw fail("needs a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        if (!fill()) {
                            throw fail("ends before its compressed data");
                        }
                        inflater.setInput(input);
                    }
                }
            } catch (DataFormatException e) {
                throw fail("has damaged compressed data: " + e.getMessage());
            }
        }

        /** Reads the next piece of the compressed bytes if the last one is used up; false at their end. */
        private boolean fill() throws IOException {
            if (input.hasRemaining()) {
                return true;
            }
            if (remaining == 0) {
                return false;
            }
            input.clear();
            input.limit((int) Math.min(input.capacity(), remaining));
            Optional<Path> local = file.path();
            if (local.isPresent() && channel == null) {
                channel = FileChannel.open(local.get(), StandardOpenOption.READ);
            }
            while (input.hasRemaining()) {
                int n = channel != null ? channel.read(input, position) : file.read(position, input);
                if (channel != null && n > 0) {
                    SourceCounters.current().bytes(n);
                }
                if (n < 0) {
                    throw new EOFException(file.location() + " ends inside the entry " + entry.name());
                }
                position += n;
                remaining -= n;
            }
            input.flip();
            return true;
        }

        private void finish() throws IOException {
            done = true;
            if (produced != limit) {
                throw fail("has " + produced + " bytes instead of " + limit);
            }
            if (crc.getValue() != entry.crc()) {
                throw fail("has a wrong checksum");
            }
        }

        private ZipException fail(String reason) {
            return new ZipException("The entry " + entry.name() + " of " + file.location() + " " + reason);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (inflater != null) {
                    inflater.end();
                }
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Only read from; nothing is lost.
                    }
                }
            }
        }
    }
}
