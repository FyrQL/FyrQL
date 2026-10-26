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
 * {@code DROP VIEW [IF EXISTS] name}.
 *
 * @param name the name of the view, of one to three parts
 * @param position the position of the name
 */
public record DropView(List<String> name, boolean ifExists, Position position) implements Statement {

    public DropView {
        name = List.copyOf(name);
        Objects.requireNonNull(position, "position");
    }
}
