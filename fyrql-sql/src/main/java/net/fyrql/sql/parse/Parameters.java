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

package net.fyrql.sql.parse;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Values;
import net.fyrql.sql.render.DuckDbDialect;

import java.util.Arrays;
import java.util.Objects;

/**
 * A query text with {@code ?} for its parameters. A {@code ?} is a
 * parameter unless it is inside a string {@code '...'}, a quoted identifier
 * {@code "..."}, a line comment from {@code --}, a block comment or a
 * string {@code $$...$$}; the text is split the way the parser of FyrQL
 * reads it, so that the parameters are those
 * {@link SqlParser#parseWithParameters} numbers, in the same order.
 * {@link #render} puts literals in place of the parameters.
 */
public final class Parameters {

    private final String sql;
    /** The offsets of the parameters in the text. */
    private final int[] positions;

    private Parameters(String sql, int[] positions) {
        this.sql = sql;
        this.positions = positions;
    }

    /** Finds the parameters of a query text. */
    public static Parameters parse(String sql) {
        Objects.requireNonNull(sql, "sql");
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
        return new Parameters(sql, Arrays.copyOf(positions, count));
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
    public String sql() {
        return sql;
    }

    /** Returns the number of parameters. */
    public int count() {
        return positions.length;
    }

    /**
     * Returns the text with the literals in place of the parameters. A
     * literal next to a letter or a digit is set apart by a space, so that
     * {@code ?AND} does not run into one word.
     *
     * @param literals the literal of every parameter, from the first
     * @throws IllegalArgumentException if the number of literals differs
     *         from that of the parameters, or a literal is {@code null}
     */
    public String render(String[] literals) {
        if (literals.length != positions.length) {
            throw new IllegalArgumentException("The query has " + positions.length + " parameters, not "
                    + literals.length);
        }
        StringBuilder b = new StringBuilder(sql.length() + 16 * positions.length);
        int from = 0;
        for (int i = 0; i < positions.length; i++) {
            String literal = literals[i];
            if (literal == null) {
                throw new IllegalArgumentException("No value is set for parameter " + (i + 1));
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

    /**
     * Returns the literal of a value that the binder takes as a value of
     * exactly its type, as it takes a parameter of that type: a cast, a
     * {@code DATE '...'}, a {@code TIMESTAMP '...'} or {@code TRUE} and
     * {@code FALSE}, never a bare string, number or {@code NULL}, whose
     * types adapt to their context.
     *
     * @param value the value as {@link Values} holds it, or one of
     *        {@code java.time} that {@link Values#fromJava} converts, or
     *        {@code null}
     * @throws IllegalArgumentException if the value is none of the type, or
     *         out of its range, or the type has a {@code MAP} in it: the
     *         parser of FyrQL reads no cast to a map type, so there is no
     *         literal of one
     */
    public static String literal(Object value, DataType type) {
        if (hasMap(type)) {
            throw new IllegalArgumentException("There is no literal of the type " + type.sqlName()
                    + ": FyrQL parses no cast to a MAP");
        }
        try {
            value = Values.fromJava(value, type);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Out of range for " + type + ": " + Values.describe(value), e);
        }
        Values.require(type, value);
        String literal = DuckDbDialect.INSTANCE.literal(value, type);
        return value != null && type == DataType.VARCHAR ? "CAST(" + literal + " AS VARCHAR)" : literal;
    }

    /** Tells whether a type is a {@code MAP} or has one in it. */
    public static boolean hasMap(DataType type) {
        return switch (type) {
            case DataType.MapType m -> true;
            case DataType.ListType l -> hasMap(l.element());
            case DataType.Struct s -> s.fields().stream().anyMatch(f -> hasMap(f.type()));
            default -> false;
        };
    }
}
