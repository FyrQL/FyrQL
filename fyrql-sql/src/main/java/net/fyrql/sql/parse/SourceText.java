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

package net.fyrql.sql.parse;

import net.fyrql.sql.ast.Position;
import net.sf.jsqlparser.parser.Token;

import java.util.Arrays;

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
 * an operator.</li>
 * </ul>
 * Lines end at {@code \n}, {@code \r} or {@code \r\n}; columns count UTF-16
 * characters from one, as JSqlParser counts them.
 */
final class SourceText {

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
        return b.build();
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
