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

import java.sql.SQLException;
import java.util.Arrays;

/**
 * A query text with {@code ?} for its parameters. A {@code ?} is a
 * parameter unless it is inside a string {@code '...'}, a quoted identifier
 * {@code "..."}, a line comment from {@code --}, a block comment or a
 * string {@code $$...$$}; the text is split the way the parser of FyrQL
 * reads it. {@link #render} puts the literals of the values in place of
 * the parameters.
 */
final class ParameterizedSql {

    private final String sql;
    /** The offsets of the parameters in the text. */
    private final int[] positions;

    private ParameterizedSql(String sql, int[] positions) {
        this.sql = sql;
        this.positions = positions;
    }

    /** Finds the parameters of a query text. */
    static ParameterizedSql parse(String sql) {
        int[] positions = new int[8];
        int count = 0;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '\'' || c == '"') {
                i = quoted(sql, i, c);
            } else if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i = until(sql, i + 2, "*/");
            } else if (c == '$' && next == '$') {
                i = until(sql, i + 2, "$$");
            } else if (isIdentifierStart(c) && (i == 0 || !isIdentifierPart(sql.charAt(i - 1)))) {
                // An identifier may hold a '$', which then starts no string.
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
            } else {
                if (c == '?') {
                    if (count == positions.length) {
                        positions = Arrays.copyOf(positions, count * 2);
                    }
                    positions[count++] = i;
                }
                i++;
            }
        }
        return new ParameterizedSql(sql, Arrays.copyOf(positions, count));
    }

    /** Returns the end of a quoted string or identifier; a doubled quote stays inside. */
    private static int quoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /** Returns the offset after the end mark, or the end of the text if the mark is missing. */
    private static int until(String sql, int from, String end) {
        int i = sql.indexOf(end, from);
        return i < 0 ? sql.length() : i + end.length();
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Returns the query text as it was given. */
    String sql() {
        return sql;
    }

    /** Returns the number of parameters. */
    int count() {
        return positions.length;
    }

    /**
     * Returns the text with the literals in place of the parameters. A
     * literal next to a letter or a digit is set apart by a space, so that
     * {@code ?AND} does not run into one word.
     *
     * @param literals the literal of every parameter, from the first
     * @throws SQLException {@code 07001} if a parameter has no value
     */
    String render(String[] literals) throws SQLException {
        StringBuilder b = new StringBuilder(sql.length() + 16 * positions.length);
        int from = 0;
        for (int i = 0; i < positions.length; i++) {
            String literal = literals[i];
            if (literal == null) {
                throw new SQLException("No value is set for parameter " + (i + 1), SqlStates.PARAMETER_NOT_SET);
            }
            int p = positions[i];
            b.append(sql, from, p);
            if (p > 0 && isIdentifierPart(sql.charAt(p - 1))) {
                b.append(' ');
            }
            b.append(literal);
            if (p + 1 < sql.length() && isIdentifierPart(sql.charAt(p + 1))) {
                b.append(' ');
            }
            from = p + 1;
        }
        return b.append(sql, from, sql.length()).toString();
    }
}
