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

package net.fyrql.core.function;

import net.fyrql.core.type.DataType;

/**
 * The operators {@code +} and {@code -} with a {@code TIMESTAMP WITH TIME
 * ZONE}, as functions that hold the time zone of the session: an instant
 * plus or minus an interval, the months and days of which count in the
 * local calendar, and an instant minus another, in local days. They are
 * named {@code add} and {@code subtract} as in DuckDB, which renders them
 * back as SQL, but are not functions of the catalog, since DuckDB's
 * functions of these names take every operand of {@code +} and {@code -}.
 */
public final class ZonedOperators {

    private ZonedOperators() {
    }

    /**
     * Returns the function of {@code a + b} for an instant and an interval in
     * either order, or {@code null} for other types.
     */
    public static ScalarFunction add(DataType a, DataType b) {
        if (a == DataType.TIMESTAMPTZ && b == DataType.INTERVAL) {
            return DateFunctions.ADD;
        }
        return a == DataType.INTERVAL && b == DataType.TIMESTAMPTZ ? DateFunctions.ADD_INTERVAL_FIRST : null;
    }

    /**
     * Returns the function of {@code a - b} for an instant minus an interval
     * or another instant, or {@code null} for other types.
     */
    public static ScalarFunction subtract(DataType a, DataType b) {
        return DateFunctions.SUBTRACT.resultType(java.util.List.of(a, b)) != null ? DateFunctions.SUBTRACT : null;
    }
}
