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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tokens of a text of SQL as PostgreSQL reads them: words, names in
 * double quotes, strings in single quotes (also {@code E'...'} with
 * backslashes) and in dollar quotes, numbers, parameters {@code $n},
 * operators and punctuation; comments and white space separate them. The
 * server splits a text of several statements at its semicolons, finds the
 * statements it answers itself and rewrites the forms of PostgreSQL with
 * them.
 */
final class SqlText {

    private SqlText() {
    }

    enum Kind {
        /** A word: a keyword or a name without quotes. */
        WORD,
        /** A name in double quotes. */
        QUOTED,
        /** A string in single quotes, with its prefix such as {@code E}. */
        STRING,
        /** A string in dollar quotes. */
        DOLLAR,
        NUMBER,
        /** A parameter {@code $n}. */
        PARAMETER,
        /** An operator, such as {@code ::}, {@code ~} or {@code <>}. */
        OPERATOR,
        /** One of {@code ( ) [ ] , ; .}. */
        PUNCTUATION
    }

    /**
     * A token.
     *
     * @param start where it starts in the text
     * @param end where it ends
     */
    record Token(Kind kind, String text, int start, int end) {

        /** Tells whether the token is a word, in any case, among the given words in upper case. */
        boolean is(String... words) {
            if (kind != Kind.WORD) {
                return false;
            }
            for (String w : words) {
                if (text.equalsIgnoreCase(w)) {
                    return true;
                }
            }
            return false;
        }

        /** Tells whether the token is the given operator or punctuation. */
        boolean isSymbol(String symbol) {
            return (kind == Kind.OPERATOR || kind == Kind.PUNCTUATION) && text.equals(symbol);
        }

        /** Returns a word in upper case, or the text of another token. */
        String upper() {
            return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : text;
        }

        /**
         * Returns the value of a name or string: a word in lower case as
         * PostgreSQL folds it, a quoted name and a string without their quotes.
         */
        String value() {
            return switch (kind) {
                case WORD -> text.toLowerCase(Locale.ROOT);
                case QUOTED -> text.substring(1, text.length() - 1).replace("\"\"", "\"");
                case STRING -> stringValue(text);
                case DOLLAR -> {
                    int open = text.indexOf('$', 1) + 1;
                    yield text.substring(open, text.length() - open);
                }
                default -> text;
            };
        }
    }

    /** A text without a closing quote or comment, which the parser reports as PostgreSQL does. */
    static final class UnterminatedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UnterminatedException(String message) {
            super(message);
        }
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 0x80;
    }

    private static final String OPERATOR_CHARS = "+-*/<>=~!@#%^&|`?:";

    /**
     * Reads the tokens of a text.
     *
     * @throws UnterminatedException if a string, a name or a block comment has no end
     */
    static List<Token> tokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            int start = i;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i = blockComment(sql, i);
            } else if (c == '\'') {
                i = string(sql, i, false);
                tokens.add(new Token(Kind.STRING, sql.substring(start, i), start, i));
            } else if ((c == 'E' || c == 'e') && next == '\'') {
                i = string(sql, i + 1, true);
                tokens.add(new Token(Kind.STRING, sql.substring(start, i), start, i));
            } else if (c == '"') {
                i = quoted(sql, i);
                tokens.add(new Token(Kind.QUOTED, sql.substring(start, i), start, i));
            } else if (c == '$' && next >= '0' && next <= '9') {
                i++;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.PARAMETER, sql.substring(start, i), start, i));
            } else if (c == '$' && (next == '$' || isWordStart(next))) {
                int tagEnd = sql.indexOf('$', i + 1);
                String tag = tagEnd < 0 ? null : sql.substring(i, tagEnd + 1);
                if (tag == null || !tag.substring(1, tag.length() - 1).chars().allMatch(ch -> isWordPart((char) ch)
                        && ch != '$')) {
                    tokens.add(new Token(Kind.OPERATOR, "$", start, ++i));
                    continue;
                }
                int close = sql.indexOf(tag, tagEnd + 1);
                if (close < 0) {
                    throw new UnterminatedException("unterminated dollar-quoted string");
                }
                i = close + tag.length();
                tokens.add(new Token(Kind.DOLLAR, sql.substring(start, i), start, i));
            } else if (isWordStart(c)) {
                while (i < n && isWordPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i), start, i));
            } else if (Character.isDigit(c) || c == '.' && Character.isDigit(next)) {
                i = number(sql, i);
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start, i));
            } else if ("()[],;.".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.PUNCTUATION, String.valueOf(c), start, ++i));
            } else if (OPERATOR_CHARS.indexOf(c) >= 0) {
                while (i < n && OPERATOR_CHARS.indexOf(sql.charAt(i)) >= 0
                        && !(sql.charAt(i) == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                        && !(sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*')) {
                    i++;
                }
                tokens.add(new Token(Kind.OPERATOR, sql.substring(start, i), start, i));
            } else {
                tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), start, ++i));
            }
        }
        return tokens;
    }

    private static int blockComment(String sql, int start) {
        int depth = 0;
        int i = start;
        while (i < sql.length()) {
            if (sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new UnterminatedException("unterminated /* comment");
    }

    /** Returns the end of a string from its opening quote; with escapes, a backslash takes the next character. */
    private static int string(String sql, int quote, boolean escapes) {
        int i = quote + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (escapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new UnterminatedException("unterminated quoted string");
    }

    private static int quoted(String sql, int quote) {
        int i = quote + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == '"') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new UnterminatedException("unterminated quoted identifier");
    }

    private static int number(String sql, int start) {
        int i = start;
        int n = sql.length();
        while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i < n && sql.charAt(i) == '.' && !(i + 1 < n && sql.charAt(i + 1) == '.')) {
            i++;
            while (i < n && Character.isDigit(sql.charAt(i))) {
                i++;
            }
        }
        if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (sql.charAt(j) == '+' || sql.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(sql.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
            }
        }
        return i;
    }

    /** The value of a string token: without its prefix and quotes, a doubled quote one quote, with escapes after E. */
    static String stringValue(String token) {
        boolean escapes = token.charAt(0) == 'E' || token.charAt(0) == 'e';
        String body = token.substring(escapes ? 2 : 1, token.length() - 1);
        if (!escapes) {
            return body.replace("''", "'");
        }
        StringBuilder b = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                b.append('\'');
                i++;
            } else if (c == '\\' && i + 1 < body.length()) {
                char x = body.charAt(++i);
                switch (x) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    default -> b.append(x);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Splits a text at the semicolons outside strings, names and comments
     * into its statements; a statement of nothing but white space and
     * comments is left out.
     *
     * @throws UnterminatedException if a string, a name or a block comment has no end
     */
    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        int from = 0;
        boolean content = false;
        for (Token t : tokens(sql)) {
            if (t.isSymbol(";")) {
                if (content) {
                    statements.add(sql.substring(from, t.start()));
                }
                from = t.end();
                content = false;
            } else {
                content = true;
            }
        }
        if (content) {
            statements.add(sql.substring(from));
        }
        return statements;
    }
}
