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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A file of a {@link FileStore} in the version in which it was looked up:
 * a local file or an object in a bucket. Its reads see that version; a
 * store that can tell, such as S3 by the entity tag, fails a read of an
 * object that changed since.
 */
public interface FileObject {

    /** Returns the location of the file, such as an absolute path or {@code s3://bucket/key}. */
    String location();

    /** Returns the name of the file: the last part of its location. */
    default String name() {
        String location = location();
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return location.substring(slash + 1);
    }

    /** Returns the size of the file in bytes. */
    long size();

    /**
     * Returns a text that changes when the content of the file changes,
     * such as the time of the last modification of a local file or the
     * entity tag of an object.
     */
    String version();

    /** Opens a stream over the whole file. */
    InputStream open() throws IOException;

    /**
     * Reads bytes from a position into a buffer, at most as many as the
     * buffer has room for, and fewer only at the end of the file.
     *
     * @return the number of bytes read, or {@code -1} if the position is
     *         at or past the end of the file
     */
    int read(long position, ByteBuffer buffer) throws IOException;

    /** Returns the path of a local file, through which it can be read faster; empty for other stores. */
    default Optional<Path> path() {
        return Optional.empty();
    }
}
