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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The directory of an engine to which its operators spill: a directory
 * named {@value #PREFIX} and a random suffix under a parent, created when an
 * operator spills first and deleted with everything in it when the engine
 * closes. The files of an operator are deleted when the operator closes,
 * also after a failure or a cancel. The directory counts what all queries
 * spilled, see {@link #statistics()}; a directory left over by a process
 * that ended without closing its engine may be deleted by hand.
 */
public final class SpillDirectory implements AutoCloseable {

    /** The prefix of the name of the directory of an engine. */
    public static final String PREFIX = "fyrql-spill-";

    /** The most files the operators of an engine keep open after writing them, to read them later. */
    static final int MAX_OPEN_FILES = 256;

    private final Path parent;
    private final AtomicLong files = new AtomicLong();
    private final AtomicInteger open = new AtomicInteger();
    private final LongAdder parts = new LongAdder();
    private final LongAdder written = new LongAdder();
    private final LongAdder read = new LongAdder();
    private Path directory;
    private boolean closed;

    /** A directory under the given parent, which is created too if it does not exist. */
    public SpillDirectory(Path parent) {
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    /** A directory under the temporary directory of the JVM, the system property {@code java.io.tmpdir}. */
    public static SpillDirectory temporary() {
        return new SpillDirectory(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** Returns the parent under which the directory is created. */
    public Path parent() {
        return parent;
    }

    /** Returns the directory, or {@code null} if nothing was spilled yet or it is closed. */
    public synchronized Path path() {
        return directory;
    }

    /** Returns what the queries of the engine spilled so far, and read back. */
    public SpillStatistics statistics() {
        return new SpillStatistics(parts.sum(), written.sum(), read.sum());
    }

    /**
     * Creates a file of an operator, empty until the operator appends to
     * it; the operator deletes it.
     *
     * @param operator the label of the operator, e.g. {@code Sort#1}
     * @param what     what the file holds, e.g. {@code run}
     * @param schema   the schema of the batches of the file
     * @param context  the query, which counts the bytes and may be canceled
     * @throws QueryException if the directory cannot be created
     */
    SpillFile create(String operator, String what, Schema schema, QueryContext context) {
        Path dir;
        try {
            dir = directory();
        } catch (IOException e) {
            throw new QueryException(operator, "Spilling to " + parent + " failed: " + e, e);
        }
        String name = files.incrementAndGet() + "-" + operator.replaceAll("[^A-Za-z0-9_-]", "") + "-" + what
                + ".spill";
        return new SpillFile(this, dir.resolve(name), operator, what, schema, context);
    }

    private synchronized Path directory() throws IOException {
        if (closed) {
            throw new IOException("The engine is closed");
        }
        if (directory == null) {
            Files.createDirectories(parent);
            directory = Files.createTempDirectory(parent, PREFIX);
        }
        return directory;
    }

    /** Counts a file that an operator opened. */
    void opened() {
        open.incrementAndGet();
    }

    /** Counts a file that an operator closed. */
    void closed() {
        open.decrementAndGet();
    }

    /** Returns the files the operators hold open. */
    int openFiles() {
        return open.get();
    }

    /** Counts a part that an operator spilled, with its bytes. */
    void spilled(long bytes) {
        parts.increment();
        written.add(bytes);
    }

    /** Counts bytes that an operator read back. */
    void readBack(long bytes) {
        read.add(bytes);
    }

    /**
     * Deletes the directory with everything in it, at best effort; an
     * operator that spills afterwards fails.
     */
    @Override
    public void close() {
        Path dir;
        synchronized (this) {
            closed = true;
            dir = directory;
            directory = null;
        }
        if (dir != null) {
            delete(dir);
        }
    }

    private static void delete(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path p : entries) {
                        delete(p);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // At best effort: what stays has the prefix of the directory in its name.
        }
    }
}
