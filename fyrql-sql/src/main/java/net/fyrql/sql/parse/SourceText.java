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

import net.fyrql.sql.Position;
import net.sf.jsqlparser.parser.Token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The text of a query as the parser sees it, rewritten where DuckDB and
 * JSqlParser disagree, with the way back to the positions in the original
 * text. Outside string literals, quoted identifiers and comments:
 * <ul>
 * <li>{@code //}, which JSqlParser reads as a line comment, becomes
 * {@code DIV}, its integer division;</li>
 * <li>{@code ==}, which JSqlParser does not know, becomes {@code =};</li>
 * <li>a bare word {@code div} becomes the quoted identifier {@code "div"}, so
 * that {@code DIV} written by the user is an identifier as in DuckDB and not
 * an operator;</li>
 * <li>a {@code QUALIFY} clause moves before a {@code WINDOW} clause it
 * follows, since JSqlParser reads the two only the other way round.</li>
 * </ul>
 * Lines end at {@code \n}, {@code \r} or {@code \r\n}; columns count UTF-16
 * characters from one, as JSqlParser counts them.
 */
final class SourceText {

    /** The words that end a {@code QUALIFY} clause, or a {@code WINDOW} clause before it. */
    private static final Set<String> CLAUSE_ENDS = Set.of("ORDER", "LIMIT", "OFFSET", "UNION", "EXCEPT", "INTERSECT",
            "WINDOW", "QUALIFY", "USING", "FETCH");

    /** The kinds of the tokens of {@link #tokens}. */
    private static final int WORD = 0;
    private static final int OPEN = 1;
    private static final int CLOSE = 2;
    private static final int SEMICOLON = 3;
    private static final int OTHER = 4;

    private final String original;
    private final String text;
    /** The offset in the original text of every character of the rewritten text, and of its end. */
    private final int[] origin;
    private final int[] lineStarts;
    private final int[] originalLineStarts;

    private SourceText(String original, String text, int[] origin) {
        this.original = original;
        this.text = text;
        this.origin = origin;
        this.lineStarts = lineStarts(text);
        this.originalLineStarts = lineStarts(original);
    }

    /** Rewrites a query text; see the class description. */
    static SourceText of(String sql) {
        Builder b = new Builder(sql);
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '\'' || c == '"') {
                i = b.copy(i, quoted(sql, i, c));
            } else if (c == '-' && next == '-') {
                int end = i;
                while (end < n && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
                    end++;
                }
                i = b.copy(i, end);
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = b.copy(i, end < 0 ? n : end + 2);
            } else if (c == '$' && next == '$') {
                int end = sql.indexOf("$$", i + 2);
                i = b.copy(i, end < 0 ? n : end + 2);
            } else if (c == '/' && next == '/') {
                // " DIV ": the token DIV covers the two slashes.
                b.add(' ', i).add('D', i).add('I', i).add('V', i + 1).add(' ', i + 2);
                i += 2;
            } else if (c == '=' && next == '=' && (i == 0 || "<>!=".indexOf(sql.charAt(i - 1)) < 0)
                    && (i + 2 >= n || sql.charAt(i + 2) != '=')) {
                b.add('=', i);
                i += 2;
            } else if (isIdentifierStart(c) && (i == 0 || !isIdentifierPart(sql.charAt(i - 1)))) {
                int end = i;
                while (end < n && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                if (end - i == 3 && sql.regionMatches(true, i, "div", 0, 3)) {
                    b.add('"', i).add(sql.charAt(i), i).add(sql.charAt(i + 1), i + 1).add(sql.charAt(i + 2), i + 2)
                            .add('"', i + 2);
                    i = end;
                } else {
                    i = b.copy(i, end);
                }
            } else {
                b.add(c, i);
                i++;
            }
        }
        SourceText result = b.build();
        int[] swap;
        while ((swap = windowBeforeQualify(result.text)) != null) {
            result = result.swapped(swap[0], swap[1], swap[2]);
        }
        return result;
    }

    /**
     * Finds a {@code WINDOW} clause that a {@code QUALIFY} clause follows on
     * the same level of parentheses, as DuckDB has them, and returns the
     * start of the one, the start of the other and the end of the other.
     * Returns {@code null} if there is none.
     */
    private static int[] windowBeforeQualify(String text) {
        List<int[]> tokens = tokens(text);
        for (int t = 0; t + 3 < tokens.size(); t++) {
            int[] window = tokens.get(t);
            if (!word(text, window, "WINDOW") || tokens.get(t + 1)[2] != WORD || !word(text, tokens.get(t + 2), "AS")
                    || tokens.get(t + 3)[2] != OPEN) {
                continue;
            }
            int depth = window[3];
            int qualify = end(text, tokens, t + 1, depth);
            if (qualify < tokens.size() && word(text, tokens.get(qualify), "QUALIFY")) {
                int end = end(text, tokens, qualify + 1, depth);
                return new int[] {window[0], tokens.get(qualify)[0],
                        end < tokens.size() ? tokens.get(end)[0] : text.length()};
            }
        }
        return null;
    }

    /** Returns the index of the first token from {@code t} on that ends a clause on the given level. */
    private static int end(String text, List<int[]> tokens, int t, int depth) {
        for (; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            if (token[3] < depth || token[3] == depth && (token[2] == SEMICOLON
                    || token[2] == WORD && CLAUSE_ENDS.contains(text.substring(token[0], token[1])
                            .toUpperCase(Locale.ROOT)))) {
                return t;
            }
        }
        return t;
    }

    private static boolean word(String text, int[] token, String word) {
        return token[2] == WORD && text.regionMatches(true, token[0], word, 0, word.length())
                && token[1] - token[0] == word.length();
    }

    /**
     * Splits a text into tokens as far as the search for clauses needs:
     * words, quoted identifiers as words, parentheses, semicolons and
     * everything else, without comments. Each token is its start, its end,
     * its kind and its level of parentheses, where a parenthesis counts on
     * the outer level.
     */
    private static List<int[]> tokens(String text) {
        List<int[]> tokens = new ArrayList<>();
        int n = text.length();
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : 0;
            int start = i;
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && next == '-') {
                while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            int kind = OTHER;
            if (c == '\'' || c == '"') {
                i = quoted(text, i, c);
                kind = c == '"' ? WORD : OTHER;
            } else if (c == '$' && next == '$') {
                int end = text.indexOf("$$", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (isIdentifierStart(c)) {
                while (i < n && isIdentifierPart(text.charAt(i))) {
                    i++;
                }
                kind = WORD;
            } else {
                i++;
                kind = c == '(' ? OPEN : c == ')' ? CLOSE : c == ';' ? SEMICOLON : OTHER;
            }
            if (kind == CLOSE) {
                depth--;
            }
            tokens.add(new int[] {start, i, kind, depth});
            if (kind == OPEN) {
                depth++;
            }
        }
        return tokens;
    }

    /**
     * Returns the text with the part from {@code a} to {@code b} moved
     * behind the part from {@code b} to {@code c}, with a space between, each
     * character keeping its position in the original text.
     */
    private SourceText swapped(int a, int b, int c) {
        Builder builder = new Builder(original);
        for (int i = 0; i < a; i++) {
            builder.add(text.charAt(i), origin[i]);
        }
        for (int i = b; i < c; i++) {
            builder.add(text.charAt(i), origin[i]);
        }
        builder.add(' ', origin[b]);
        for (int i = a; i < b; i++) {
            builder.add(text.charAt(i), origin[i]);
        }
        for (int i = c; i < text.length(); i++) {
            builder.add(text.charAt(i), origin[i]);
        }
        return builder.build();
    }

    /** Returns the end of a quoted string or identifier that starts at {@code start}; a doubled quote stays inside. */
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

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Returns the rewritten text, for the parser. */
    String text() {
        return text;
    }

    /** Returns the position in the original text where a token of the rewritten text starts. */
    Position position(Token token) {
        return originalPosition(origin[offset(token.beginLine, token.beginColumn)]);
    }

    /** Returns the original text of a token, e.g. {@code //} for the token {@code DIV}. */
    String originalText(Token token) {
        int begin = origin[offset(token.beginLine, token.beginColumn)];
        int end = origin[Math.min(offset(token.endLine, token.endColumn) + 1, text.length())];
        return original.substring(begin, Math.max(begin, end));
    }

    /**
     * Returns the original text from where a token starts to the end,
     * without the white space and semicolons that close it.
     */
    String originalFrom(Token token) {
        String rest = original.substring(origin[offset(token.beginLine, token.beginColumn)]);
        int end = rest.length();
        while (end > 0 && (Character.isWhitespace(rest.charAt(end - 1)) || rest.charAt(end - 1) == ';')) {
            end--;
        }
        return rest.substring(0, end);
    }

    /** Returns the position in the original text of a line and column of the rewritten text. */
    Position position(int line, int column) {
        return originalPosition(origin[offset(line, column)]);
    }

    /** Returns the position just after the end of the original text. */
    Position end() {
        return originalPosition(original.length());
    }

    private int offset(int line, int column) {
        if (line < 1) {
            return 0;
        }
        int start = lineStarts[Math.min(line, lineStarts.length) - 1];
        return Math.max(0, Math.min(start + column - 1, text.length()));
    }

    private Position originalPosition(int offset) {
        int line = Arrays.binarySearch(originalLineStarts, offset);
        if (line < 0) {
            line = -line - 2;
        }
        return new Position(line + 1, offset - originalLineStarts[line] + 1);
    }

    private static int[] lineStarts(String s) {
        int[] starts = new int[8];
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                i++;
            }
            if (c == '\r' || c == '\n') {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, count);
    }

    private static final class Builder {
        private final String original;
        private final StringBuilder text;
        private int[] origin;

        Builder(String original) {
            this.original = original;
            this.text = new StringBuilder(original.length() + 16);
            this.origin = new int[original.length() + 17];
        }

        Builder add(char c, int originalOffset) {
            if (text.length() + 1 >= origin.length) {
                origin = Arrays.copyOf(origin, origin.length * 2);
            }
            origin[text.length()] = originalOffset;
            text.append(c);
            return this;
        }

        /** Copies the original text from {@code begin} to {@code end} unchanged and returns {@code end}. */
        int copy(int begin, int end) {
            for (int i = begin; i < end; i++) {
                add(original.charAt(i), i);
            }
            return end;
        }

        SourceText build() {
            origin[text.length()] = original.length();
            return new SourceText(original, text.toString(), Arrays.copyOf(origin, text.length() + 1));
        }
    }
}
