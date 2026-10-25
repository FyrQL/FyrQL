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

package net.fyrql.sql.bind;

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the binder needs to know of the tables: names resolved along the
 * search path, the columns of a table, and the tables of table function
 * calls and of files. The engine implements it. Errors are {@link SqlException}s without
 * a position; the binder adds the position of the name in the query.
 */
public interface CatalogView {

    /**
     * Resolves the name of a table, of one to three parts.
     *
     * @throws SqlException if there is no such table or the name is ambiguous
     */
    TableRef resolve(List<String> name);

    /**
     * Resolves the name of a table that may not exist, for
     * {@code DROP TABLE IF EXISTS}. The default writes no tables.
     *
     * @return the table, or empty if there is none of the name
     * @throws SqlException if the name is ambiguous
     */
    default Optional<TableRef> find(List<String> name) {
        throw new SqlException("Not supported: writing tables", Position.UNKNOWN);
    }

    /**
     * Returns where a new table of a name of one to three parts goes, for
     * {@code CREATE TABLE}: in a registered source and an existing schema
     * of it, as the engine resolves the parts; the table itself need not
     * exist. The default writes no tables.
     *
     * @throws SqlException if the name names no source or schema
     */
    default TableRef resolveNew(List<String> name) {
        throw new SqlException("Not supported: writing tables", Position.UNKNOWN);
    }

    /**
     * Checks that a statement may write a file, for {@code COPY ... TO}.
     * The default allows every file.
     *
     * @param path the path of the file as the statement names it
     * @throws SqlException if the statement may not write it
     */
    default void checkFileWrite(String path) {
    }

    /** Returns the columns of a resolved table. */
    Schema schema(TableRef table);

    /**
     * Returns the parts of the name of a resolved table that qualify its
     * columns, as {@code t.c}, {@code schema.t.c} and {@code source.schema.t.c}
     * do; the default is {@code source.schema.table}.
     */
    default List<String> path(TableRef table) {
        return List.of(table.source(), table.schema(), table.table());
    }

    /**
     * Returns the table a call of a table function reads.
     *
     * @param arguments constant positional arguments as Java values
     * @param namedArguments constant named arguments, in the order of the call
     * @throws SqlException if there is no such function or the arguments do not fit
     */
    TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments);

    /**
     * Returns the table that reads a file with given columns, for
     * {@code COPY ... FROM}. The default reads no files.
     *
     * @param format the format, such as {@code csv}
     * @param path the path of the file as the statement names it
     * @param schema the columns to read, by their names and types
     * @param options the options of the statement besides the format, by
     *        names in lower case, in their order
     * @return a table with the given columns or, for a format whose files
     *         hold their own columns, such as Parquet, with those of the file
     * @throws SqlException if no source reads the format, the path is no
     *         file, or an option or a column does not fit the format
     */
    default TableRef fileTable(String format, String path, Schema schema, Map<String, String> options) {
        throw new SqlException("Not supported: reading files", Position.UNKNOWN);
    }

    /**
     * Returns the table function that reads a file named in {@code FROM}, as
     * DuckDB chooses it by the extension: {@code read_csv} for {@code .csv}
     * and {@code .tsv}, {@code read_parquet} for {@code .parquet},
     * {@code read_json} for {@code .json}, {@code .jsonl} and
     * {@code .ndjson}, each also with {@code .gz} or {@code .zst} after it,
     * and {@code read_xlsx} for {@code .xlsx}.
     */
    static Optional<String> fileFunction(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".xlsx")) {
            return Optional.of("read_xlsx");
        }
        for (String compression : List.of(".gz", ".zst")) {
            if (p.endsWith(compression)) {
                p = p.substring(0, p.length() - compression.length());
            }
        }
        if (p.endsWith(".csv") || p.endsWith(".tsv")) {
            return Optional.of("read_csv");
        }
        if (p.endsWith(".parquet")) {
            return Optional.of("read_parquet");
        }
        if (p.endsWith(".json") || p.endsWith(".jsonl") || p.endsWith(".ndjson")) {
            return Optional.of("read_json");
        }
        return Optional.empty();
    }
}
