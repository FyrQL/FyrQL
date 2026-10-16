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

package net.fyrql.core.plan;

import java.util.Objects;

/**
 * The three-part name of a table: the source it was registered as, a schema
 * within the source ({@code main} for sources without schemas), and the
 * table.
 */
public record TableRef(String source, String schema, String table) {

    /** The schema of sources that have no schemas of their own. */
    public static final String MAIN = "main";

    public TableRef {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
    }

    /** Returns a reference in the schema {@code main}. */
    public static TableRef of(String source, String table) {
        return new TableRef(source, MAIN, table);
    }

    @Override
    public String toString() {
        return source + "." + schema + "." + table;
    }
}
