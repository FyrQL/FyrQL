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

import java.util.Locale;

/** How the records of a JSON input are laid out, named as in DuckDB's {@code format} argument. */
public enum JsonFormat {

    /**
     * One top-level array of records if the input starts with {@code [},
     * otherwise a sequence of values, as for {@link #NEWLINE_DELIMITED}.
     * Unlike DuckDB, which reads a sequence of arrays as such, an input that
     * starts with an array must be just that array; a sequence of arrays
     * needs {@link #NEWLINE_DELIMITED}, so that the input is read only once.
     */
    AUTO,

    /** One top-level array whose elements are the records; nothing may follow it. */
    ARRAY,

    /**
     * A sequence of values, each a record; one per line as in JSON Lines,
     * though any whitespace separates them. A top-level array is one record.
     */
    NEWLINE_DELIMITED;

    /**
     * Returns the format for its name: {@code auto}, {@code array} or
     * {@code newline_delimited}, in any case.
     *
     * @throws IllegalArgumentException for any other name
     */
    public static JsonFormat of(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "array" -> ARRAY;
            case "newline_delimited" -> NEWLINE_DELIMITED;
            default -> throw new IllegalArgumentException("Unknown JSON format \"" + name
                    + "\"; supported are auto, array, newline_delimited");
        };
    }

    /** Returns the name of the format as {@link #of} reads it. */
    public String sqlName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
