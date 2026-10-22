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

package net.fyrql.flight;

import net.fyrql.arrow.ArrowTypeException;
import net.fyrql.connector.FileAccessException;
import net.fyrql.core.value.ConversionException;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryException;
import net.fyrql.sql.SqlException;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;

/**
 * The statuses with which the server answers errors: the message is always
 * that of FyrQL, with the position in the query text or the operator that
 * failed.
 */
final class FlightErrors {

    private FlightErrors() {
    }

    /**
     * Turns an error of the engine into a status. It comes from the first
     * exception in the chain of causes that tells it: a canceled query is
     * {@code CANCELLED}, or {@code TIMED_OUT} after its timeout; a file
     * that the engine does not allow statements to read or write is
     * {@code UNAUTHORIZED}, which gRPC calls {@code PERMISSION_DENIED}; a query
     * that cannot be parsed or bound, values that do not fit, an overflow
     * or a failed conversion is {@code INVALID_ARGUMENT}; the memory budget
     * of the engine is {@code RESOURCE_EXHAUSTED}; anything else, such as a
     * failing source, is {@code INTERNAL}.
     */
    static FlightRuntimeException of(Throwable e) {
        if (e instanceof FlightRuntimeException f) {
            return f;
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        return status(e).withDescription(message).withCause(e).toRuntimeException();
    }

    private static CallStatus status(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof QueryCancelledException c) {
                return c.reason() == QueryContext.Reason.TIMEOUT ? CallStatus.TIMED_OUT : CallStatus.CANCELLED;
            }
            if (t instanceof FileAccessException
                    || t instanceof SqlException && t.getCause() instanceof FileAccessException) {
                return CallStatus.UNAUTHORIZED;
            }
            if (t instanceof SqlException || t instanceof ArithmeticException || t instanceof ConversionException
                    || t instanceof ArrowTypeException) {
                return CallStatus.INVALID_ARGUMENT;
            }
            if (t instanceof QueryException && String.valueOf(t.getMessage()).startsWith("Memory budget")) {
                return CallStatus.RESOURCE_EXHAUSTED;
            }
        }
        return e instanceof IllegalArgumentException ? CallStatus.INVALID_ARGUMENT : CallStatus.INTERNAL;
    }

    /** A handle that the server does not know, or not for this client. */
    static FlightRuntimeException notFound(String message) {
        return CallStatus.NOT_FOUND.withDescription(message).toRuntimeException();
    }

    /** A request the server does not take. */
    static FlightRuntimeException invalid(String message) {
        return CallStatus.INVALID_ARGUMENT.withDescription(message).toRuntimeException();
    }

    /** A feature the server does not have. */
    static FlightRuntimeException unimplemented(String message) {
        return CallStatus.UNIMPLEMENTED.withDescription(message).toRuntimeException();
    }
}
