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

/**
 * One key of an {@code ORDER BY}: an expression, or {@code ALL} when the
 * expression is {@code null}. Without {@code NULLS FIRST}, {@code NULL}
 * sorts last in both directions, as in DuckDB.
 */
public record OrderItem(SqlExpr expression, boolean ascending, boolean nullsFirst) {

    /** {@code ORDER BY ALL} in the given direction. */
    public static OrderItem all(boolean ascending, boolean nullsFirst) {
        return new OrderItem(null, ascending, nullsFirst);
    }

    /** Tells whether this is {@code ORDER BY ALL}. */
    public boolean isAll() {
        return expression == null;
    }
}
