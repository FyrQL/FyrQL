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
 * {@code SET name = 'value'} or {@code SET name TO 'value'}: a setting of
 * the session; {@code USE source[.schema]} is a {@code SET} of
 * {@code search_path}.
 *
 * @param name the name of the setting, as written
 * @param value the value, the text of a string, a number, or a name as
 *        written, its parts separated by dots, a quoted part in double quotes
 * @param position the position of the statement
 */
public record SetVariable(String name, String value, Position position) implements Statement {

    public SetVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(position, "position");
    }
}
