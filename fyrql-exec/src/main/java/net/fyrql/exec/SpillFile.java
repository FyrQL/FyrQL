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

package net.fyrql.exec;

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.SpillFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A file to which an operator spills batches of one schema, such as a
 * sorted run or the rows of a partition: appended to batch by batch,
 * finished, and then read from the start, once or more, see
 * {@link #reader()}. A finished file counts as a part that the query and
 * the engine spilled. Each call checks whether the query is canceled.
 * Closing deletes the file, after closing its readers; a failure to write or
 * read is a {@link QueryException} that names the file.
 * <p>
 * The file stays open from the first batch on and its readers read from
 * the same channel, since on some systems opening a file that was just
 * written takes far longer than writing it; once the engine holds
 * {@link SpillDirectory#MAX_OPEN_FILES} files open, a finished one is closed
 * and opened again to be read.
 */
final class SpillFile implements AutoCloseable {

    private final SpillDirectory directory;
    private final Path path;
    private final String operator;
    private final Schema schema;
    private final QueryContext context;
    private final List<SpillReader> readers = new ArrayList<>();
    private FileChannel channel;
    private SpillFormat.Writer writer;
    private boolean finished;
    private long bytes;
    private long rows;
    private int batches;

    SpillFile(SpillDirectory directory, Path path, String operator, Schema schema, QueryContext context) {
        this.directory = directory;
        this.path = path;
        this.operator = operator;
        this.schema = schema;
        this.context = context;
    }

    /** Returns the path of the file. */
    Path path() {
        return path;
    }

    /** Returns the schema of the batches of the file. */
    Schema schema() {
        return schema;
    }

    /** Returns the rows appended so far. */
    long rows() {
        return rows;
    }

    /** Returns the batches appended so far. */
    int batches() {
        return batches;
    }

    /** Returns the bytes of the file, all of them once it is finished. */
    long bytes() {
        return finished ? bytes : writer != null ? writer.bytes() : 0;
    }

    /** Appends a batch; an empty one is skipped. */
    void append(Batch batch) {
        context.check(operator);
        if (finished) {
            throw new IllegalStateException("The file " + path + " is finished");
        }
        if (batch.rowCount() == 0) {
            return;
        }
        try {
            if (writer == null) {
                channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                directory.opened();
                writer = new SpillFormat.Writer(channel);
            }
            writer.write(batch);
        } catch (IOException e) {
            throw failure("Spilling to ", e);
        }
        rows += batch.rowCount();
        batches++;
    }

    /** Writes what is buffered and counts the file as a part, if anything was appended; appending ends. */
    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (writer == null) {
            return;
        }
        SpillFormat.Writer w = writer;
        writer = null;
        try {
            w.flush();
            if (directory.openFiles() > SpillDirectory.MAX_OPEN_FILES) {
                closeChannel();
            }
        } catch (IOException e) {
            throw failure("Spilling to ", e);
        }
        bytes = w.bytes();
        context.spilled(bytes);
        directory.spilled(bytes);
    }

    private void closeChannel() throws IOException {
        if (channel != null) {
            FileChannel c = channel;
            channel = null;
            directory.closed();
            c.close();
        }
    }

    /** Finishes the file and opens a reader of its batches from the start, which closing the file closes. */
    SpillReader reader() {
        finish();
        context.check(operator);
        SpillFormat.Reader r = null;
        if (batches > 0) {
            try {
                r = new SpillFormat.Reader(channel != null ? new View(channel)
                        : FileChannel.open(path, StandardOpenOption.READ), schema);
            } catch (IOException e) {
                throw failure("Reading spilled rows from ", e);
            }
        }
        SpillReader reader = new SpillReader(r);
        readers.add(reader);
        return reader;
    }

    /** Closes the readers and deletes the file, at best effort. */
    @Override
    public void close() {
        for (SpillReader r : readers) {
            r.close();
        }
        readers.clear();
        writer = null;
        try {
            closeChannel();
        } catch (IOException e) {
            // Deleted below anyway.
        }
        finished = true;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // At best effort: the directory of the engine is deleted when it closes.
        }
    }

    private QueryException failure(String what, IOException e) {
        return new QueryException(operator, what + path + " failed: " + e.getMessage(), e);
    }

    /** Reads the open channel of the file from the start, on its own position; closing leaves the channel open. */
    private static final class View implements ReadableByteChannel {
        private final FileChannel channel;
        private long position;
        private boolean open = true;

        View(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            int n = channel.read(dst, position);
            if (n > 0) {
                position += n;
            }
            return n;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /** Reads the batches of a finished file from the start. */
    final class SpillReader implements AutoCloseable {
        private final SpillFormat.Reader reader;
        private long counted;
        private boolean closed;

        private SpillReader(SpillFormat.Reader reader) {
            this.reader = reader;
        }

        /** Returns the next batch, or {@code null} after the last one. */
        Batch next() {
            context.check(operator);
            if (reader == null || closed) {
                return null;
            }
            Batch b;
            try {
                b = reader.next();
            } catch (IOException e) {
                throw failure("Reading spilled rows from ", e);
            }
            long delta = reader.bytes() - counted;
            counted += delta;
            context.readBack(delta);
            directory.readBack(delta);
            return b;
        }

        @Override
        public void close() {
            if (closed || reader == null) {
                closed = true;
                return;
            }
            closed = true;
            try {
                reader.close();
            } catch (IOException e) {
                // Nothing is lost: the file was read as far as needed.
            }
        }
    }
}
