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

package net.fyrql.connector.json;

import net.fyrql.core.type.DataType;

/**
 * Reads the JSON strings of a column of a given layout as its type, in
 * place of the cast of a string to the type, for a source whose strings
 * have forms of their own, such as dates written as
 * {@code /Date(1700000000000)/}; see {@link JsonLayout#of(net.fyrql.core.type.Schema, JsonStringParser)}.
 */
@FunctionalInterface
public interface JsonStringParser {

    /**
     * Returns the value of a string as a scalar or decimal type, in the
     * representation of FyrQL, such as the days since 1970-01-01 of a
     * {@code DATE}; {@code VARCHAR} included.
     *
     * @throws RuntimeException if the string is no value of the type; the
     *         reader reports it as a value that does not fit, with the
     *         message of the exception
     */
    Object parse(String text, DataType type);
}
