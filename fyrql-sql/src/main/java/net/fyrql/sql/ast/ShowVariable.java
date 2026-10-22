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

import java.util.Objects;

/**
 * {@code SHOW name}: the value of a setting of the session, as a query of
 * one row; {@code SHOW ALL} every setting, one per row.
 *
 * @param name the name of the setting, as written, or {@code ALL}
 * @param position the position of the statement
 */
public record ShowVariable(String name, Position position) implements Statement {

    public ShowVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
    }
}
