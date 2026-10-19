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
