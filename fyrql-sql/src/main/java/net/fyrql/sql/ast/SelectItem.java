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

import java.util.List;
import java.util.Objects;

/** One item of a {@code SELECT} list: {@code *} with its modifiers, or an expression with an optional alias. */
public sealed interface SelectItem {

    /**
     * {@code [qualifier.]* [EXCLUDE (names)] [REPLACE (expr AS name, ...)]};
     * the qualifier is empty for a plain {@code *}.
     */
    record Star(List<String> qualifier, List<String> exclude, List<Replacement> replace, Position position)
            implements SelectItem {
        public Star {
            qualifier = List.copyOf(qualifier);
            exclude = List.copyOf(exclude);
            replace = List.copyOf(replace);
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code expression AS name} in the {@code REPLACE} of a {@link Star}. */
    record Replacement(SqlExpr expression, String name) {
        public Replacement {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code expression [AS alias]}; the alias is {@code null} when absent. */
    record ExpressionItem(SqlExpr expression, String alias) implements SelectItem {
        public ExpressionItem {
            Objects.requireNonNull(expression, "expression");
        }
    }
}
