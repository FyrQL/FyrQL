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

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.util.List;
import java.util.Map;

/**
 * A {@link Connector} that also reads files of some formats with columns
 * given in advance, for {@code COPY table FROM 'file' (FORMAT format, ...)}:
 * the columns are those of the table, so that no type is guessed from a
 * sample of the file. The engine looks for the format among its sources, as
 * it does for the writers of {@link FileWriterProvider}; a connector that
 * implements both reads and writes the same formats.
 */
public interface FileReaderProvider {

    /** Returns the names of the formats, in lower case, such as {@code csv}. */
    List<String> fileFormats();

    /**
     * Returns a table of this connector that reads a file. The connector
     * should give the same table for the same file in the same version with
     * the same columns and options, as it does for a table function, see
     * {@link TableFunctionProvider}.
     *
     * @param format one of {@link #fileFormats()}
     * @param location the location of the file to read, a local path or
     *        one with a scheme, see {@link FileStores}
     * @param schema the columns to read, by their names and types; a format
     *        whose files hold their own columns, such as Parquet, may give
     *        the table those of the file instead, which the engine then
     *        casts to the given ones by position
     * @param options the options of the statement besides the format, as
     *        for {@link FileWriterProvider#openFile}
     * @throws IllegalArgumentException with a message for the user if an
     *         option is unknown, which names the known ones, or does not
     *         fit, if a column has a type the format cannot hold, or if the
     *         file does not exist
     */
    TableRef fileTable(String format, String location, Schema schema, Map<String, String> options);
}
