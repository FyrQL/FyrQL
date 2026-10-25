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

import net.fyrql.SqlStates;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.sql.Position;
import net.fyrql.sql.SqlException;

import java.io.IOException;

/**
 * Turns errors into the messages {@code ErrorResponse} and
 * {@code NoticeResponse}: the severity, the SQLSTATE, the message and,
 * for an error in the text of a statement, its position as the number of
 * the character from 1.
 */
final class PgErrors {

    private PgErrors() {
    }

    /**
     * An error as the client gets it.
     *
     * @param position the character of the text of the statement where it is, from 1, or 0
     */
    record Error(String severity, String state, String message, int position) {
    }

    /**
     * Describes an error of the server or of the engine.
     *
     * @param sql the text of the statement that failed, for the position, or {@code null}
     */
    static Error of(Throwable e, String sql) {
        return switch (e) {
            case PgException p -> new Error("ERROR", p.state(), p.getMessage(), 0);
            case ServerSettings.InvalidSetting s -> new Error("ERROR", s.state(), s.getMessage(), 0);
            case SqlText.UnterminatedException u -> new Error("ERROR", PgException.SYNTAX_ERROR, u.getMessage(), 0);
            default -> engine(e, sql);
        };
    }

    private static Error engine(Throwable e, String sql) {
        String state = SqlStates.state(e);
        if (state.equals(SqlStates.QUERY_CANCELED)) {
            return new Error("ERROR", state, SqlStates.timedOut(e) ? "canceling statement due to statement timeout"
                    : "canceling statement due to user request", 0);
        }
        SqlException s = sqlException(e);
        if (s != null) {
            return new Error("ERROR", state, s.reason(), offset(sql, s.position()));
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e instanceof IllegalArgumentException && state.equals(SqlStates.INTERNAL_ERROR)) {
            // Values that do not fit their parameters, and functions that refuse their arguments.
            state = PgException.INVALID_PARAMETER_VALUE;
        }
        return new Error("ERROR", state, message, 0);
    }

    private static SqlException sqlException(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SqlException s) {
                return s;
            }
            if (t instanceof QueryCancelledException) {
                return null;
            }
        }
        return null;
    }

    /** Returns the character of a line and column of a text, from 1, or 0 if it is not in the text. */
    static int offset(String sql, Position position) {
        if (sql == null || !position.isKnown()) {
            return 0;
        }
        int line = 1;
        int i = 0;
        while (line < position.line() && i < sql.length()) {
            if (sql.charAt(i++) == '\n') {
                line++;
            }
        }
        int offset = i + position.column();
        return line == position.line() && offset <= sql.length() + 1 ? sql.codePointCount(0, offset - 1) + 1 : 0;
    }

    /** Writes an error, or a notice, as its message. */
    static void write(MessageWriter out, char type, Error error) throws IOException {
        out.abandon();
        out.begin(type);
        out.int8('S').string(error.severity());
        out.int8('V').string(error.severity());
        out.int8('C').string(error.state());
        out.int8('M').string(error.message());
        if (error.position() > 0) {
            out.int8('P').string(Integer.toString(error.position()));
        }
        out.int8(0);
        out.end();
    }
}
