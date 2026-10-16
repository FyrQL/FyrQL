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
