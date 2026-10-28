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

import java.util.List;
import java.util.Objects;

/**
 * {@code CREATE SCHEMA [IF NOT EXISTS] [source.]schema}.
 *
 * @param name the name of the schema, of one or two parts
 * @param position the position of the name
 */
public record CreateSchema(List<String> name, boolean ifNotExists, Position position) implements Statement {

    public CreateSchema {
        name = List.copyOf(name);
        if (name.isEmpty() || name.size() > 2) {
            throw new IllegalArgumentException("A schema name has one or two parts");
        }
        Objects.requireNonNull(position, "position");
    }
}
