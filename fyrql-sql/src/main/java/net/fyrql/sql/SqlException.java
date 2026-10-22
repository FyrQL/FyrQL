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
        this(reason, position, null);
    }

    /**
     * Creates the exception with the error that caused it, such as a
     * location that the engine does not allow to read.
     */
    public SqlException(String reason, Position position, Throwable cause) {
        super(position.isKnown() ? reason + " at " + position : reason, cause);
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
