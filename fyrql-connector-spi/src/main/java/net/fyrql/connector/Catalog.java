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

package net.fyrql.connector;

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.util.List;
import java.util.OptionalLong;

/** The schemas and tables of a source, with their columns and, where known, statistics. */
public interface Catalog {

    /** Returns the schemas; {@code main} for a source without schemas of its own. */
    List<String> schemas();

    /** Returns the tables of a schema. */
    List<String> tables(String schema);

    /**
     * Returns the columns of a table.
     *
     * @throws IllegalArgumentException if there is no such table
     */
    Schema schema(TableRef table);

    /** Returns the number of rows of a table, if the source knows it cheaply. */
    default OptionalLong rowCount(TableRef table) {
        return OptionalLong.empty();
    }
}
