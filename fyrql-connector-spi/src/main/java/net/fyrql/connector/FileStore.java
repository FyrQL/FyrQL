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
import java.util.List;

/**
 * Where files of the locations of one scheme live, such as {@code s3}:
 * the connectors of file formats read and write through it. A
 * {@link Connector} offers a store with {@link Connector#fileStore()}; the
 * engine gives every connector all stores as {@link FileStores}.
 * <p>
 * A location names a file, a directory, or files by a pattern with
 * {@code *} and {@code ?} in its last part, see {@link Globs}.
 */
public interface FileStore {

    /** Returns the scheme of the locations of the store, in lower case, such as {@code s3}. */
    String scheme();

    /**
     * Returns the files a location names, sorted by their locations: the
     * file itself, the files directly in a directory whose names end with
     * an extension in any case, or the files whose names match the pattern;
     * an empty list if there are none.
     *
     * @param extension the extension of the files of a directory, such as
     *        {@code .parquet}, or empty for all of them
     * @throws IllegalArgumentException if the location has a pattern
     *         outside its last part or is no valid location of the store
     */
    List<FileObject> list(String location, String extension) throws IOException;

    /**
     * Returns the file at a location.
     *
     * @throws java.nio.file.NoSuchFileException if there is no file there
     * @throws IllegalArgumentException if the location is no valid location of the store
     */
    FileObject object(String location) throws IOException;

    /**
     * Starts to write a file at a location, see {@link FileOutput}.
     *
     * @throws IllegalArgumentException if the location cannot hold a file
     */
    FileOutput create(String location) throws IOException;
}
