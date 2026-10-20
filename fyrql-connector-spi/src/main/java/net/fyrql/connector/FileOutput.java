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
import java.io.OutputStream;

/**
 * A file of a {@link FileStore} being written, which appears under its
 * location only with {@link #commit}, replacing a file there; closing it
 * without a commit leaves nothing behind. A local file is written under a
 * temporary name, see {@link TemporaryFile}; an object in S3 as a
 * multipart upload.
 */
public interface FileOutput extends AutoCloseable {

    /** Returns the location the file gets, as an absolute one. */
    String location();

    /**
     * Returns the stream to write the content to, the same on every call.
     * Closing the stream does not commit the file.
     */
    OutputStream stream() throws IOException;

    /**
     * Closes the stream if it is open and makes the file appear under its
     * location.
     *
     * @throws IOException if it cannot; nothing appears then
     */
    void commit() throws IOException;

    /** Abandons the file unless it was committed. */
    @Override
    void close();
}
