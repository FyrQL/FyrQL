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

package net.fyrql.sql;

import net.fyrql.sql.ast.Position;

import java.util.Objects;

/**
 * A query that cannot be parsed or bound: a syntax error, a form FyrQL does
 * not support, or a name or type that does not fit. The message names the
 * position in the query text where it is known.
 */
public class SqlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final Position position;

    public SqlException(String reason, Position position) {
        super(position.isKnown() ? reason + " at " + position : reason);
        this.reason = reason;
        this.position = Objects.requireNonNull(position, "position");
    }

    /** Returns the message without the position. */
    public String reason() {
        return reason;
    }

    /** Returns the position in the query text, or {@link Position#UNKNOWN}. */
    public Position position() {
        return position;
    }
}
