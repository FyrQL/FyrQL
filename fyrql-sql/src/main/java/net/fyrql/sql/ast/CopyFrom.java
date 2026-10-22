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

import net.fyrql.sql.Position;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code COPY name [(columns)] FROM 'path' (options)}, which loads a file
 * into a table that exists.
 *
 * @param name the name of the table, of one to three parts
 * @param columns the columns the file holds, in its order, empty for all
 *        columns of the table
 * @param path the file to read, as written
 * @param options the options in their order, by names in lower case, as
 *        those of {@link CopyTo}
 * @param position the position of the name
 * @param pathPosition the position of the path
 */
public record CopyFrom(List<String> name, List<String> columns, String path, Map<String, String> options,
        Position position, Position pathPosition) implements Statement {

    public CopyFrom {
        name = List.copyOf(name);
        columns = List.copyOf(columns);
        Objects.requireNonNull(path, "path");
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(pathPosition, "pathPosition");
    }
}
