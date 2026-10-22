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

package net.fyrql.exec;

/**
 * A query failed while it ran: an overflow, a conversion error, a memory
 * budget exceeded. The message names the operator; the cause is the error
 * itself.
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
public class QueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operator;

    public QueryException(String operator, String message, Throwable cause) {
        super(message + " in " + operator, cause);
        this.operator = operator;
    }

    public QueryException(String operator, String message) {
        this(operator, message, null);
    }

    /** Returns the label of the operator that failed, e.g. {@code Filter#2}. */
    public String operator() {
        return operator;
    }
}
