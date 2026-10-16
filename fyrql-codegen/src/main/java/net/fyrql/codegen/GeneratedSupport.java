/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
