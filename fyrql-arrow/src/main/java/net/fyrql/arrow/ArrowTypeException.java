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

package net.fyrql.arrow;

/**
 * A type or a value that does not cross between FyrQL and Arrow, such as
 * an Arrow type FyrQL has no type for, or a {@code HUGEINT} too large for
 * the decimal Arrow holds it in. The message names the field.
 */
public final class ArrowTypeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public ArrowTypeException(String field, String message) {
        super("Field " + field + ": " + message);
        this.field = field;
    }

    /** Returns the name of the field, with the names of its parents for a nested field, e.g. {@code s.items}. */
    public String field() {
        return field;
    }
}
