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
 * A query failed while it ran: an overflow, a conversion error, a memory
 * budget exceeded. The message names the operator; the cause is the error
 * itself.
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
