/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
