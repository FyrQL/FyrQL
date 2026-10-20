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

package net.fyrql.sql.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code COPY (query) TO 'path' (options)}, or {@code COPY table TO ...},
 * which is the query {@code SELECT * FROM table}, or
 * {@code COPY table (columns) TO ...}, which is
 * {@code SELECT columns FROM table}.
 *
 * @param query the rows to write
 * @param path the file to write, as written
 * @param options the options in their order, by names in lower case; a
 *        value is the text as written, without the quotes of a string,
 *        and {@code true} for an option without a value
 * @param position the position of the path
 */
public record CopyTo(Query query, String path, Map<String, String> options, Position position) implements Statement {

    public CopyTo {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(path, "path");
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        Objects.requireNonNull(position, "position");
    }
}
