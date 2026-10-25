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

package net.fyrql.pgwire;

/** An error of the server itself, with the SQLSTATE of PostgreSQL that it sends to the client. */
final class PgException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final String PROTOCOL_VIOLATION = "08P01";
    static final String FEATURE_NOT_SUPPORTED = "0A000";
    static final String INVALID_TRANSACTION_STATE = "25000";
    static final String IN_FAILED_TRANSACTION = "25P02";
    static final String NO_ACTIVE_TRANSACTION = "25P01";
    static final String INVALID_CURSOR_NAME = "34000";
    static final String DUPLICATE_CURSOR = "42P03";
    static final String INVALID_STATEMENT_NAME = "26000";
    static final String DUPLICATE_PREPARED_STATEMENT = "42P05";
    static final String SYNTAX_ERROR = "42601";
    static final String INVALID_PARAMETER_VALUE = "22023";
    static final String INVALID_TEXT_REPRESENTATION = "22P02";
    static final String INVALID_BINARY_REPRESENTATION = "22P03";
    static final String INVALID_AUTHORIZATION = "28000";
    static final String INVALID_PASSWORD = "28P01";
    static final String INVALID_CATALOG = "3D000";
    static final String TOO_MANY_CONNECTIONS = "53300";
    static final String INSUFFICIENT_RESOURCES = "53000";
    static final String QUERY_CANCELED = "57014";

    private final String state;

    PgException(String state, String message) {
        super(message);
        this.state = state;
    }

    PgException(String state, String message, Throwable cause) {
        super(message, cause);
        this.state = state;
    }

    String state() {
        return state;
    }
}
