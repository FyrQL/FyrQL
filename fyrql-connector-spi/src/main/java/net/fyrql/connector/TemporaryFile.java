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

package net.fyrql.connector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The temporary file of a local {@link FileOutput}, as the
 * {@link FileWriterProvider} describes it: a file named after the target
 * with a random part and {@code .tmp} added, in the directory of the
 * target. {@link #commit} gives it the name of the target, replacing a file
 * of that name; {@link #close} without a commit deletes it.
 */
final class TemporaryFile implements AutoCloseable {

    private final Path target;
    private final Path path;
    private boolean committed;

    private TemporaryFile(Path target, Path path) {
        this.target = target;
        this.path = path;
    }

    /**
     * Creates the temporary file of a target, empty.
     *
     * @param target the file to write; a relative path is resolved against
     *        the working directory
     * @throws IllegalArgumentException if the directory of the target does
     *         not exist, or if the target is a directory
     * @throws UncheckedIOException if the file cannot be created
     */
    public static TemporaryFile create(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null || absolute.getFileName() == null) {
            throw new IllegalArgumentException("Cannot write " + target + ": it is not a file");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Cannot write " + target + ": the directory " + directory
                    + " does not exist");
        }
        if (Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Cannot write " + target + ": it is a directory");
        }
        String name = absolute.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1)
                + ".tmp";
        Path path = directory.resolve(name);
        try {
            Files.createFile(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + path + ": " + e.getMessage(), e);
        }
        return new TemporaryFile(absolute, path);
    }

    /** Returns the file to write, the temporary one. */
    public Path path() {
        return path;
    }

    /** Returns the file the temporary one becomes, as an absolute path. */
    public Path target() {
        return target;
    }

    /**
     * Gives the temporary file the name of the target, atomically where the
     * file system can. The temporary file must be closed by its writer.
     *
     * @throws UncheckedIOException if it cannot, e.g. because the target is
     *         open in another program; the temporary file is then deleted
     */
    public void commit() {
        if (committed) {
            throw new IllegalStateException("Already committed: " + target);
        }
        try {
            try {
                Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            delete();
            throw new UncheckedIOException("Cannot move " + path + " to " + target + ": " + e.getMessage(), e);
        }
        committed = true;
    }

    /** Deletes the temporary file unless it was committed. */
    @Override
    public void close() {
        if (!committed) {
            delete();
        }
    }

    private void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + path + ": " + e.getMessage(), e);
        }
    }
}
