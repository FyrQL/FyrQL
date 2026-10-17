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
