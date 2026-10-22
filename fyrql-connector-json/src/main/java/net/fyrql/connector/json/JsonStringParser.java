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

import net.fyrql.core.type.DataType;

/**
 * Reads the JSON strings of a column of a given layout as its type, in
 * place of the cast of a string to the type, for a source whose strings
 * have forms of their own, such as dates written as
 * {@code /Date(1700000000000)/}; see {@link JsonLayout#of(net.fyrql.core.type.Schema, JsonStringParser)}.
 * <p>
 * Internal; not covered by the compatibility promise.
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
