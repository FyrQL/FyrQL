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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the binder needs to know of the tables: names resolved along the
 * search path, the columns of a table, and the tables of table function
 * calls. The engine implements it. Errors are {@link SqlException}s without
 * a position; the binder adds the position of the name in the query.
 */
public interface CatalogView {

    /**
     * Resolves the name of a table, of one to three parts.
     *
     * @throws SqlException if there is no such table or the name is ambiguous
     */
    TableRef resolve(List<String> name);

    /** Returns the columns of a resolved table. */
    Schema schema(TableRef table);

    /**
     * Returns the table a call of a table function reads.
     *
     * @param arguments constant positional arguments as Java values
     * @param namedArguments constant named arguments, in the order of the call
     * @throws SqlException if there is no such function or the arguments do not fit
     */
    TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments);

    /**
     * Returns the table function that reads a file named in {@code FROM}, as
     * DuckDB chooses it by the extension: {@code read_csv} for {@code .csv}
     * and {@code .tsv}, {@code read_parquet} for {@code .parquet},
     * {@code read_json} for {@code .json}, {@code .jsonl} and
     * {@code .ndjson}, each also with {@code .gz} or {@code .zst} after it.
     */
    static Optional<String> fileFunction(String path) {
        String p = path.toLowerCase(Locale.ROOT);
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
