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

package net.fyrql;

import net.fyrql.connector.FileAccessException;
import net.fyrql.core.value.ConversionException;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryException;
import net.fyrql.sql.SqlException;

import java.io.UncheckedIOException;
import java.util.Set;

/**
 * The SQLSTATEs of the errors of the engine, as the JDBC driver and the
 * PostgreSQL wire protocol server give them to their clients. The states
 * follow PostgreSQL where it has one for the case.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class SqlStates {

    /** A feature FyrQL does not have. */
    public static final String FEATURE_NOT_SUPPORTED = "0A000";
    /** A value that does not fit its type. */
    public static final String NUMERIC_OUT_OF_RANGE = "22003";
    /** A division by zero. */
    public static final String DIVISION_BY_ZERO = "22012";
    /** A value that cannot be converted to a type. */
    public static final String INVALID_CAST = "22018";
    /** A {@code NULL} for a column that is {@code NOT NULL}. */
    public static final String NOT_NULL_VIOLATION = "23502";
    /** An unknown schema, or none selected to create a table in. */
    public static final String INVALID_SCHEMA_NAME = "3F000";
    /** Any other error of the query text. */
    public static final String SYNTAX_ERROR_OR_ACCESS_RULE = "42000";
    /** A file that the engine does not allow statements to read or write. */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** The query cannot be parsed. */
    public static final String SYNTAX_ERROR = "42601";
    /** A column that is neither grouped nor aggregated. */
    public static final String GROUPING_ERROR = "42803";
    /** An ambiguous column name. */
    public static final String AMBIGUOUS_COLUMN = "42702";
    /** An unknown column. */
    public static final String UNDEFINED_COLUMN = "42703";
    /** An unknown function, or one without a variant for the arguments. */
    public static final String UNDEFINED_FUNCTION = "42883";
    /** An unknown table, source or table function. */
    public static final String UNDEFINED_TABLE = "42P01";
    /** An ambiguous table name. */
    public static final String AMBIGUOUS_TABLE = "42712";
    /** A table to create that exists. */
    public static final String DUPLICATE_TABLE = "42P07";
    /** The memory budget of the engine is exceeded. */
    public static final String OUT_OF_MEMORY = "53200";
    /** The query is nested too deeply. */
    public static final String STATEMENT_TOO_COMPLEX = "54001";
    /** The query was canceled, on request or after its timeout. */
    public static final String QUERY_CANCELED = "57014";
    /** A source failed: its database, service or files. */
    public static final String IO_ERROR = "58030";
    /** Any other error. */
    public static final String INTERNAL_ERROR = "XX000";

    /** The exceptions of the connectors for a failing source, by name: the engine does not depend on them. */
    private static final Set<String> SOURCE_EXCEPTIONS = Set.of("net.fyrql.connector.jdbc.JdbcSourceException",
            "net.fyrql.connector.http.HttpSourceException");

    private SqlStates() {
    }

    /**
     * Returns the state of an error of the engine. It comes from the first
     * exception in the chain of causes that tells it: a canceled query, a
     * file the engine does not allow ({@code 42501}), the reason of a
     * {@link SqlException}, an overflow, a failed conversion, a failing
     * source or the memory budget; otherwise it is {@code XX000}. A
     * canceled query is {@code 57014}, also after its timeout, whatever
     * error its sources stopped with.
     */
    public static String state(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof QueryCancelledException) {
                return QUERY_CANCELED;
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
        return INTERNAL_ERROR;
    }

    /** Tells whether an error of the engine is a query that its timeout canceled, see {@link #state}. */
    public static boolean timedOut(Throwable e) {
        if (!state(e).equals(QUERY_CANCELED)) {
            return false;
        }
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof QueryCancelledException c) {
                return c.reason() == QueryContext.Reason.TIMEOUT;
            }
        }
        return false;
    }

    /** Returns the state of a query that cannot be parsed or bound, by the reason FyrQL gives. */
    public static String stateOf(String reason) {
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
        if (r.startsWith("No schema has been selected")
                || r.startsWith("Schema with name") && r.endsWith(" does not exist")) {
            return INVALID_SCHEMA_NAME;
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
