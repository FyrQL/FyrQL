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
 * The failure of a query that was canceled, see {@link QueryContext}. Its
 * message tells why and names the operator that stopped, e.g.
 * {@code The query was canceled in Sort#1}; the cause, if any, is the
 * error with which a source stopped when it was asked to.
 */
public final class QueryCancelledException extends QueryException {

    private static final long serialVersionUID = 1L;

    private final QueryContext.Reason reason;

    public QueryCancelledException(String operator, QueryContext.Reason reason, Throwable cause) {
        super(operator, reason.message(), cause);
        this.reason = reason;
    }

    /** Returns why the query was canceled. */
    public QueryContext.Reason reason() {
        return reason;
    }
}
