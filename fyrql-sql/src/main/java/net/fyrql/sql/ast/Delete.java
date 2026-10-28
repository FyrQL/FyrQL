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
 * {@code DELETE FROM name [[AS] alias] [USING using] [WHERE where]}.
 *
 * @param name the name of the table, of one to three parts
 * @param alias the alias of the table, or {@code null}
 * @param using the items of {@code USING}, joined, or {@code null}
 * @param where the condition, or {@code null}
 * @param position the position of the name
 */
public record Delete(List<String> name, String alias, FromItem using, SqlExpr where, Position position)
        implements Statement {

    public Delete {
        name = List.copyOf(name);
        Objects.requireNonNull(position, "position");
    }
}
