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
