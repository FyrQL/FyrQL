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

package net.fyrql.connector.jdbc;

/**
 * A failure of a database behind a JDBC source, or a value it returns that
 * does not fit the type of its column. The message names the source and,
 * where there is one, the statement.
 */
public final class JdbcSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JdbcSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    public JdbcSourceException(String message) {
        super(message);
    }
}
