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

package net.fyrql.codegen;

import net.fyrql.core.type.DataType;

import java.math.BigDecimal;

/** Small helpers that generated code calls; public because the generated classes live in other class loaders. */
public final class GeneratedSupport {

    private GeneratedSupport() {
    }

    /**
     * Returns the unscaled value of a decimal of at most 18 digits, failing
     * as {@link net.fyrql.core.vector.DecimalVector.Builder#appendBigDecimal}
     * fails for a value that does not belong to the type.
     */
    public static long unscaled(Object value, DataType.Decimal type) {
        if (!type.accepts(value)) {
            throw new IllegalArgumentException("Not a " + type + ": " + value);
        }
        return ((BigDecimal) value).unscaledValue().longValue();
    }
}
