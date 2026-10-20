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

import net.fyrql.core.type.Schema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A {@link Connector} that also writes files of some formats, for
 * {@code COPY ... TO 'file' (FORMAT format, ...)}. The engine looks for the
 * format among the registered sources, as it does for table functions.
 * <p>
 * The file is written under a temporary name in its directory and gets
 * its name with the commit of the sink, replacing a file of that name;
 * closing the sink without a commit deletes the temporary file. The
 * directory must exist. {@link TemporaryFile} does this.
 */
public interface FileWriterProvider {

    /** Returns the names of the formats, in lower case, such as {@code csv}. */
    List<String> fileFormats();

    /**
     * Opens a sink that writes a file.
     *
     * @param format one of {@link #fileFormats()}
     * @param file the file to write
     * @param schema the columns of the rows
     * @param options the options of the statement besides the format, by
     *        names in lower case, in their order; a value is the text of
     *        the option as written, without the quotes of a string, and
     *        {@code true} for an option without a value
     * @throws IllegalArgumentException with a message for the user if an
     *         option is unknown, which names the known ones, or does not
     *         fit, or if a column has a type the format cannot hold
     */
    Sink openFile(String format, Path file, Schema schema, Map<String, String> options);
}
