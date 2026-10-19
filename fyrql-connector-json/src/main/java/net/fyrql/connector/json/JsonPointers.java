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

import com.fasterxml.jackson.core.JsonPointer;

/**
 * JSON pointers as in RFC 6901, which say where in a JSON document its
 * records are: the empty string for the whole document, otherwise a
 * {@code /} before each key or index, such as {@code /data} or
 * {@code /result/items}, with {@code ~0} for {@code ~} and {@code ~1} for
 * {@code /} in a key.
 */
public final class JsonPointers {

    private JsonPointers() {
    }

    /**
     * Checks a JSON pointer.
     *
     * @return the pointer
     * @throws IllegalArgumentException if it is not one
     */
    public static String check(String pointer) {
        try {
            JsonPointer.compile(pointer);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JSON pointer \"" + pointer + "\": " + e.getMessage(), e);
        }
        return pointer;
    }
}
