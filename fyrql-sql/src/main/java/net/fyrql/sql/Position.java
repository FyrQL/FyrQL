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

package net.fyrql.sql;

import java.io.Serializable;

/**
 * A place in the text of a query: line and column, both counted from one,
 * a column in UTF-16 characters with a tab counting as one. A position of
 * zero is {@link #UNKNOWN}.
 */
public record Position(int line, int column) implements Serializable {

    /** The position of a node that has none, e.g. one built by a program. */
    public static final Position UNKNOWN = new Position(0, 0);

    public Position {
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("position: " + line + ":" + column);
        }
    }

    /** Tells whether this is a real position. */
    public boolean isKnown() {
        return line > 0;
    }

    @Override
    public String toString() {
        return isKnown() ? "line " + line + ", column " + column : "unknown position";
    }
}
