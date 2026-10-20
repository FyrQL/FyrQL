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

import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the statement {@code COPY}, which JSqlParser does not know, in the
 * form of DuckDB:
 * <pre>
 * COPY ( query ) TO 'path' [ [WITH] ( option [value] [, ...] ) ] [;]
 * COPY name TO 'path' [ [WITH] ( option [value] [, ...] ) ] [;]
 * </pre>
 * The query in parentheses is cut out by counting parentheses outside
 * strings, quoted names and comments, and is parsed as any query; the
 * rest of the text is read here. A value is a word, a string, a number,
 * or absent, which stands for {@code true}. {@code COPY ... FROM} is an
 * error.
 */
final class CopyParser {

    /** The kinds of tokens. */
    private enum Kind {
        WORD, QUOTED, STRING, NUMBER, SYMBOL, END
    }

    /**
     * A token: its kind, its value (a word as written, a quoted name or a
     * string without its quotes), and where it starts and ends in the text.
     */
    private record Token(Kind kind, String value, int start, int end) {
        boolean is(String word) {
            return kind == Kind.WORD && value.equalsIgnoreCase(word);
        }

        boolean isSymbol(char c) {
            return kind == Kind.SYMBOL && value.charAt(0) == c;
        }
    }

    /**
     * A statement {@code COPY} as read here.
     *
     * @param queryStart where the query in parentheses starts, or -1 for a name
     * @param queryEnd where it ends
     * @param name the parts of the name, or empty for a query
     * @param namePosition the position of the name, or of the query
     * @param path the file
     * @param pathPosition the position of the file
     * @param options the options, by names in lower case, in their order
     */
    record Copy(int queryStart, int queryEnd, List<String> name, Position namePosition, String path,
            Position pathPosition, Map<String, String> options) {
    }

    private final String sql;
    private final List<Token> tokens = new ArrayList<>();
    private int next;

    private CopyParser(String sql) {
        this.sql = sql;
    }

    /** Tells whether a text starts with the word {@code COPY}, after spaces and comments. */
    static boolean isCopy(String sql) {
        int i = skipSpaceAndComments(sql, 0);
        int end = i;
        while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
            end++;
        }
        return end - i == 4 && sql.regionMatches(true, i, "copy", 0, 4);
    }

    /** Returns the position of the word {@code COPY} of a text that starts with it. */
    static Position start(String sql) {
        return position(sql, skipSpaceAndComments(sql, 0));
    }

    /**
     * Reads a statement {@code COPY}.
     *
     * @throws SqlException with the position of the error if the text is no such statement
     */
    static Copy parse(String sql) {
        CopyParser p = new CopyParser(sql);
        p.tokenize();
        return p.copy();
    }

    /**
     * Returns the text of a statement with spaces in place of everything
     * but the query in parentheses, so that the query parses with the
     * positions of the whole text; line ends stay.
     */
    static String queryOnly(String sql, Copy copy) {
        char[] text = sql.toCharArray();
        for (int i = 0; i < text.length; i++) {
            if ((i < copy.queryStart() || i >= copy.queryEnd()) && text[i] != '\n' && text[i] != '\r') {
                text[i] = ' ';
            }
        }
        return new String(text);
    }

    private Copy copy() {
        Token copy = take();
        if (!copy.is("COPY")) {
            throw syntaxError(copy);
        }
        int queryStart = -1;
        int queryEnd = -1;
        List<String> name = new ArrayList<>();
        Token first = peek();
        Position namePosition = position(sql, first.start());
        if (first.isSymbol('(')) {
            take();
            queryStart = first.end();
            int depth = 1;
            Token t;
            do {
                t = take();
                if (t.kind() == Kind.END) {
                    throw new SqlException("Syntax error at end of input: the query after COPY has no closing"
                            + " parenthesis", position(sql, t.start()));
                }
                if (t.isSymbol('(')) {
                    depth++;
                } else if (t.isSymbol(')')) {
                    depth--;
                }
            } while (depth > 0);
            queryEnd = t.start();
            namePosition = position(sql, skipSpaceAndComments(sql, queryStart));
        } else {
            name.add(namePart(take()));
            while (peek().isSymbol('.')) {
                take();
                name.add(namePart(take()));
            }
        }
        Token to = take();
        if (to.is("FROM")) {
            throw new SqlException("Not supported: COPY ... FROM; read a file with INSERT INTO table SELECT * FROM"
                    + " read_csv('file') or read_json or read_parquet", position(sql, to.start()));
        }
        if (!to.is("TO")) {
            throw syntaxError(to);
        }
        Token path = take();
        if (path.kind() != Kind.STRING) {
            throw path.kind() == Kind.END ? syntaxError(path)
                    : new SqlException("Syntax error at or near \"" + text(path) + "\": the file after TO is a string"
                            + " in single quotes", position(sql, path.start()));
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (peek().is("WITH")) {
            take();
            if (!peek().isSymbol('(')) {
                throw syntaxError(peek());
            }
        }
        if (peek().isSymbol('(')) {
            take();
            option(options);
            while (peek().isSymbol(',')) {
                take();
                option(options);
            }
            Token close = take();
            if (!close.isSymbol(')')) {
                throw syntaxError(close);
            }
        }
        boolean semicolon = peek().isSymbol(';');
        if (semicolon) {
            take();
        }
        Token end = take();
        if (end.kind() != Kind.END) {
            throw semicolon ? new SqlException("Only one statement is supported", position(sql, end.start()))
                    : syntaxError(end);
        }
        return new Copy(queryStart, queryEnd, List.copyOf(name), namePosition, path.value(),
                position(sql, path.start()), Collections.unmodifiableMap(options));
    }

    /** Reads {@code option [value]} into the options. */
    private void option(Map<String, String> options) {
        Token name = take();
        if (name.kind() != Kind.WORD && name.kind() != Kind.QUOTED) {
            throw syntaxError(name);
        }
        String key = name.value().toLowerCase(Locale.ROOT);
        String value;
        Token t = peek();
        if (t.isSymbol(',') || t.isSymbol(')')) {
            value = "true";
        } else if (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED || t.kind() == Kind.STRING
                || t.kind() == Kind.NUMBER) {
            value = take().value();
        } else if ((t.isSymbol('-') || t.isSymbol('+')) && tokens.get(next + 1).kind() == Kind.NUMBER) {
            take();
            value = (t.isSymbol('-') ? "-" : "") + take().value();
        } else if (t.isSymbol('(') || t.isSymbol('[') || t.isSymbol('{')) {
            throw new SqlException("Not supported: a list as the value of option " + key, position(sql, t.start()));
        } else {
            throw syntaxError(t);
        }
        if (options.putIfAbsent(key, value) != null) {
            throw new SqlException("Option " + key + " is given more than once", position(sql, name.start()));
        }
    }

    private String namePart(Token t) {
        if (t.kind() != Kind.WORD && t.kind() != Kind.QUOTED) {
            throw syntaxError(t);
        }
        return t.value();
    }

    private Token peek() {
        return tokens.get(next);
    }

    private Token take() {
        Token t = tokens.get(next);
        if (t.kind() != Kind.END) {
            next++;
        }
        return t;
    }

    private String text(Token t) {
        return sql.substring(t.start(), t.end());
    }

    private SqlException syntaxError(Token t) {
        if (t.kind() == Kind.END) {
            return new SqlException("Syntax error at end of input", position(sql, t.start()));
        }
        return new SqlException("Syntax error at or near \"" + text(t) + "\"", position(sql, t.start()));
    }

    // ---- tokens

    private void tokenize() {
        int n = sql.length();
        int i = skipSpaceAndComments(sql, 0);
        while (i < n) {
            char c = sql.charAt(i);
            char after = i + 1 < n ? sql.charAt(i + 1) : 0;
            int start = i;
            if (c == '\'' || c == '"') {
                int end = quotedEnd(start, c);
                String inner = sql.substring(start + 1, end - 1);
                String doubled = String.valueOf(c) + c;
                tokens.add(new Token(c == '"' ? Kind.QUOTED : Kind.STRING, inner.replace(doubled, String.valueOf(c)),
                        start, end));
                i = end;
            } else if (c == '$' && after == '$') {
                int close = sql.indexOf("$$", start + 2);
                if (close < 0) {
                    throw unterminated(start);
                }
                tokens.add(new Token(Kind.STRING, sql.substring(start + 2, close), start, close + 2));
                i = close + 2;
            } else if (Character.isLetter(c) || c == '_') {
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, i), start, i));
            } else if (Character.isDigit(c) || c == '.' && Character.isDigit(after)) {
                i = number(start);
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start, i));
            } else {
                i++;
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), start, i));
            }
            i = skipSpaceAndComments(sql, i);
        }
        tokens.add(new Token(Kind.END, "", n, n));
    }

    /** Returns the end of a quoted string or name that starts at {@code start}; a doubled quote stays inside. */
    private int quotedEnd(int start, char quote) {
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
        throw unterminated(start);
    }

    private int number(int start) {
        int i = start;
        int n = sql.length();
        while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
            i++;
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

    private SqlException unterminated(int start) {
        return new SqlException("Unterminated quoted string, quoted identifier or comment", position(sql, start));
    }

    /** Returns where the next token starts: after spaces, line comments and block comments. */
    private static int skipSpaceAndComments(String sql, int from) {
        int n = sql.length();
        int i = from;
        while (i < n) {
            char c = sql.charAt(i);
            char after = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && after == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && after == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) {
                    throw new SqlException("Unterminated quoted string, quoted identifier or comment",
                            position(sql, i));
                }
                i = close + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Returns the position of an offset in a text: lines end at
     * {@code \n}, {@code \r} or {@code \r\n}, columns count UTF-16
     * characters from one.
     */
    private static Position position(String sql, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset && i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\r' && i + 1 < sql.length() && sql.charAt(i + 1) == '\n') {
                i++;
            }
            if (c == '\r' || c == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart + 1);
    }
}
