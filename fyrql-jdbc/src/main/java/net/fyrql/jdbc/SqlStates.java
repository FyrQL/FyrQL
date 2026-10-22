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

package net.fyrql.jdbc;

import net.fyrql.connector.FileAccessException;
import net.fyrql.core.value.ConversionException;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryException;
import net.fyrql.sql.SqlException;

import java.io.UncheckedIOException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.Set;

/**
 * The SQLStates of the driver and the exceptions that carry them. The
 * states follow PostgreSQL where it has one for the case; the message is
 * that of FyrQL, whose exception stays the cause.
 */
final class SqlStates {

    /** A parameter of a prepared statement has no value. */
    static final String PARAMETER_NOT_SET = "07001";
    /** The connection cannot be established: a bad URL or configuration. */
    static final String CONNECTION_FAILURE = "08001";
    /** The connection is closed. */
    static final String CONNECTION_CLOSED = "08003";
    /** A feature the driver does not have. */
    static final String FEATURE_NOT_SUPPORTED = "0A000";
    /** A value that does not fit the type it is read as. */
    static final String NUMERIC_OUT_OF_RANGE = "22003";
    /** A division by zero. */
    static final String DIVISION_BY_ZERO = "22012";
    /** A value that cannot be converted to the type it is read as. */
    static final String INVALID_CAST = "22018";
    /** A value of an argument the method does not take. */
    static final String INVALID_PARAMETER = "22023";
    /** A {@code NULL} for a column that is {@code NOT NULL}. */
    static final String NOT_NULL_VIOLATION = "23502";
    /** A result set that is not on a row. */
    static final String INVALID_CURSOR_STATE = "24000";
    /** A statement that writes on a read-only connection. */
    static final String READ_ONLY = "25006";
    /** An unknown catalog, which is a source of the engine. */
    static final String INVALID_CATALOG = "3D000";
    /** An unknown schema of the default source. */
    static final String INVALID_SCHEMA = "3F000";
    /** Any other error of the query text. */
    static final String SYNTAX_ERROR_OR_ACCESS_RULE = "42000";
    /** A file that the engine does not allow statements to read or write. */
    static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** The query cannot be parsed. */
    static final String SYNTAX_ERROR = "42601";
    /** A column that is neither grouped nor aggregated. */
    static final String GROUPING_ERROR = "42803";
    /** An ambiguous column name. */
    static final String AMBIGUOUS_COLUMN = "42702";
    /** A method that does not apply to the object, such as {@code executeQuery(sql)} of a prepared statement. */
    static final String WRONG_OBJECT_TYPE = "42809";
    /** An unknown column. */
    static final String UNDEFINED_COLUMN = "42703";
    /** An unknown function, or one without a variant for the arguments. */
    static final String UNDEFINED_FUNCTION = "42883";
    /** An unknown table, source or table function. */
    static final String UNDEFINED_TABLE = "42P01";
    /** An ambiguous table name. */
    static final String AMBIGUOUS_TABLE = "42712";
    /** A table to create that exists. */
    static final String DUPLICATE_TABLE = "42P07";
    /** The memory budget of the engine is exceeded. */
    static final String OUT_OF_MEMORY = "53200";
    /** The query was canceled on request. */
    static final String QUERY_CANCELED = "57014";
    /** The query is nested too deeply. */
    static final String STATEMENT_TOO_COMPLEX = "54001";
    /** A source failed: its database, service or files. */
    static final String IO_ERROR = "58030";
    /** A method called on a closed object or out of order. */
    static final String FUNCTION_SEQUENCE = "HY010";
    /** Any other error. */
    static final String GENERAL = "HY000";
    /** The query ran longer than its timeout. */
    static final String TIMEOUT = "HYT00";

    /** The exceptions of the connectors for a failing source, by name: the driver does not depend on them. */
    private static final Set<String> SOURCE_EXCEPTIONS = Set.of("net.fyrql.connector.jdbc.JdbcSourceException",
            "net.fyrql.connector.http.HttpSourceException");

    private SqlStates() {
    }

    /** The connection cannot be established. */
    static SQLNonTransientConnectionException connectionFailure(String message, Throwable cause) {
        return new SQLNonTransientConnectionException(message, CONNECTION_FAILURE, cause);
    }

    /** The connection is closed. */
    static SQLNonTransientConnectionException connectionClosed() {
        return new SQLNonTransientConnectionException("The connection is closed", CONNECTION_CLOSED);
    }

    /** A statement or result set is closed. */
    static SQLException closed(String what) {
        return new SQLException("The " + what + " is closed", FUNCTION_SEQUENCE);
    }

    /** A feature the driver does not have; {@code what} is its name, such as {@code Savepoints}. */
    static SQLFeatureNotSupportedException unsupported(String what) {
        return new SQLFeatureNotSupportedException(what + " are not supported by FyrQL", FEATURE_NOT_SUPPORTED);
    }

    /** An argument that the method does not take. */
    static SQLException invalidArgument(String message) {
        return new SQLException(message, INVALID_PARAMETER);
    }

    /** Creates the exception of the class that belongs to a state. */
    static SQLException create(String message, String state, Throwable cause) {
        return switch (state.substring(0, 2)) {
            case "08" -> new SQLNonTransientConnectionException(message, state, cause);
            case "0A" -> new SQLFeatureNotSupportedException(message, state, cause);
            case "22" -> new SQLDataException(message, state, cause);
            case "23" -> new SQLIntegrityConstraintViolationException(message, state, cause);
            case "42" -> new SQLSyntaxErrorException(message, state, cause);
            case "HY" -> state.equals(TIMEOUT) ? new SQLTimeoutException(message, state, cause)
                    : new SQLException(message, state, cause);
            default -> new SQLException(message, state, cause);
        };
    }

    /**
     * Turns an error of the engine into an exception of JDBC. The state
     * comes from the first exception in the chain of causes that tells it:
     * a canceled query, a file the engine does not allow ({@code 42501}),
     * the reason of a {@link SqlException}, an overflow,
     * a failed conversion, a failing source or the memory budget; otherwise
     * it is {@code HY000}. A canceled query is {@code 57014}, or
     * {@code HYT00} after its timeout, whatever error its sources stopped
     * with. The message is that of the error, with the position in
     * the query text or the operator that failed.
     */
    static SQLException map(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        return create(message, state(e), e);
    }

    /** Returns the state of an error of the engine, see {@link #map}. */
    static String state(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof QueryCancelledException c) {
                return c.reason() == QueryContext.Reason.TIMEOUT ? TIMEOUT : QUERY_CANCELED;
            }
            if (t instanceof FileAccessException
                    || t instanceof SqlException && t.getCause() instanceof FileAccessException) {
                return INSUFFICIENT_PRIVILEGE;
            }
            if (t instanceof SqlException s) {
                return stateOf(s.reason());
            }
            if (t instanceof ArithmeticException) {
                return String.valueOf(t.getMessage()).contains("by zero") ? DIVISION_BY_ZERO : NUMERIC_OUT_OF_RANGE;
            }
            if (t instanceof ConversionException) {
                return INVALID_CAST;
            }
            if (t instanceof UncheckedIOException || SOURCE_EXCEPTIONS.contains(t.getClass().getName())) {
                return IO_ERROR;
            }
            if (t instanceof QueryException && String.valueOf(t.getMessage()).startsWith("Memory budget")) {
                return OUT_OF_MEMORY;
            }
        }
        return GENERAL;
    }

    /** Returns the state of a query that cannot be parsed or bound, by the reason FyrQL gives. */
    static String stateOf(String reason) {
        String r = reason == null ? "" : reason;
        if (r.startsWith("Syntax error") || r.startsWith("Unterminated") || r.equals("Empty query")
                || r.startsWith("Only one statement")) {
            return SYNTAX_ERROR;
        }
        if (r.startsWith("Query is nested too deeply")) {
            return STATEMENT_TOO_COMPLEX;
        }
        if (r.startsWith("Table with name") && r.endsWith(" already exists")) {
            return DUPLICATE_TABLE;
        }
        if (r.startsWith("NOT NULL constraint failed")) {
            return NOT_NULL_VIOLATION;
        }
        if (r.startsWith("Table with name") || r.startsWith("No source ") || r.startsWith("Table function with name")
                || r.startsWith("Referenced table")) {
            return UNDEFINED_TABLE;
        }
        if (r.startsWith("Ambiguous reference to table") || r.startsWith("Table name ") && r.contains(" is ambiguous")) {
            return AMBIGUOUS_TABLE;
        }
        if (r.startsWith("Ambiguous reference to column")) {
            return AMBIGUOUS_COLUMN;
        }
        if (r.startsWith("column \"") && r.contains("must appear in the GROUP BY")
                || r.startsWith("Cannot mix aggregates")) {
            return GROUPING_ERROR;
        }
        if (r.startsWith("Referenced column") || r.startsWith("Column \"")) {
            return UNDEFINED_COLUMN;
        }
        if (r.startsWith("Function with name") || r.startsWith("No function matches")
                || r.startsWith("Wrong number of arguments") || r.startsWith("No operator")) {
            return UNDEFINED_FUNCTION;
        }
        if (r.startsWith("Cannot cast") || r.startsWith("Could not convert") || r.startsWith("Invalid number")) {
            return INVALID_CAST;
        }
        if (r.startsWith("Not supported") || r.startsWith("Only queries")
                || r.contains("not supported")) {
            return FEATURE_NOT_SUPPORTED;
        }
        return SYNTAX_ERROR_OR_ACCESS_RULE;
    }
}
