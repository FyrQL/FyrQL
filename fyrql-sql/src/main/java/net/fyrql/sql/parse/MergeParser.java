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
import net.fyrql.sql.ast.Merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the structure of the statement {@code MERGE}, of which JSqlParser
 * knows only some forms, in the form of DuckDB:
 * <pre>
 * MERGE INTO name [[AS] alias] USING source
 *     { ON condition | USING ( column [, ...] ) }
 *     { WHEN [NOT] MATCHED [BY { SOURCE | TARGET }] [AND condition] THEN action } ... [;]
 * action:
 *     UPDATE [SET column = value [, ...]] | DELETE | DO NOTHING
 *     | INSERT [( column [, ...] )] VALUES ( value [, ...] ) | INSERT [BY NAME]
 * </pre>
 * The source, the conditions and the values are parts of the text, which
 * the caller parses with JSqlParser; a part ends where a word of the
 * statement follows on its level of parentheses and outside a
 * {@code CASE}.
 */
final class MergeParser {

    /** A part of the rewritten text, from {@code start} to before {@code end}. */
    record Part(int start, int end) {
    }

    /** {@code column = value} of a {@code SET}; {@code at} is where the column starts. */
    record Assignment(String column, Part value, int at) {
    }

    /**
     * A branch as read here.
     *
     * @param condition the condition after {@code AND}, or {@code null}
     * @param action {@code UPDATE}, {@code DELETE}, {@code INSERT} or {@code NOTHING}
     * @param set the assignments of {@code UPDATE SET}, or {@code null} for a bare {@code UPDATE}
     * @param columns the column list of {@code INSERT}, or empty
     * @param values the values of {@code INSERT ... VALUES}, or {@code null}
     * @param byName whether it is {@code INSERT BY NAME}
     * @param at where the word {@code WHEN} starts
     */
    record Branch(Merge.Match match, Part condition, String action, List<Assignment> set, List<String> columns,
            List<Part> values, boolean byName, int at) {
    }

    /**
     * A statement {@code MERGE} as read here.
     *
     * @param nameAt where the name of the table starts
     * @param on the condition of {@code ON}, or {@code null} with {@code USING}
     */
    record Parsed(List<String> name, int nameAt, String alias, Part source, Part on, List<String> using,
            List<Branch> branches) {
    }

    private final SourceText source;
    private final String text;
    private final List<int[]> tokens;
    private int t;

    private MergeParser(SourceText source) {
        this.source = source;
        this.text = source.text();
        this.tokens = SourceText.tokens(text);
    }

    /** Tells whether a text starts with the word {@code MERGE}, after spaces and comments. */
    static boolean isMerge(String sql) {
        return CopyParser.word(sql, 0, "merge") >= 0;
    }

    /**
     * Reads a statement {@code MERGE}.
     *
     * @throws SqlException with the position of the error if the text is no such statement
     */
    static Parsed parse(SourceText source) {
        return new MergeParser(source).merge();
    }

    private Parsed merge() {
        expect("MERGE");
        expect("INTO");
        int nameAt = start(t);
        List<String> name = new ArrayList<>();
        name.add(identifier());
        while (symbol(t, '.')) {
            t++;
            name.add(identifier());
        }
        if (name.size() > 3) {
            throw new SqlException("A table name has one to three parts", source.at(nameAt));
        }
        String alias = null;
        if (word(t, "AS")) {
            t++;
            alias = identifier();
        } else if (t < tokens.size() && kind(t) == SourceText.WORD && !word(t, "USING")) {
            alias = identifier();
        }
        expect("USING");
        int from = t;
        while (t < tokens.size() && !(depth(t) == 0 && (word(t, "ON") || word(t, "USING") || word(t, "WHEN")))) {
            t++;
        }
        if (t == from) {
            throw syntaxError(t);
        }
        Part sourcePart = new Part(start(from), end(t - 1));
        Part on = null;
        List<String> using = new ArrayList<>();
        if (word(t, "ON")) {
            t++;
            on = expression(Set.of("WHEN"), false);
        } else if (word(t, "USING")) {
            t++;
            if (t >= tokens.size() || kind(t) != SourceText.OPEN) {
                throw syntaxError(t);
            }
            t++;
            do {
                using.add(identifier());
            } while (comma());
            if (t >= tokens.size() || kind(t) != SourceText.CLOSE) {
                throw syntaxError(t);
            }
            t++;
        } else {
            throw new SqlException("Syntax error: MERGE needs ON or USING after its source", position(t));
        }
        List<Branch> branches = new ArrayList<>();
        while (word(t, "WHEN")) {
            branches.add(branch());
        }
        if (branches.isEmpty()) {
            throw new SqlException("Syntax error: MERGE needs a WHEN", position(t));
        }
        if (t < tokens.size() && kind(t) == SourceText.SEMICOLON) {
            t++;
        }
        if (t < tokens.size()) {
            if (word(t, "RETURNING")) {
                throw new SqlException("Not supported: RETURNING", position(t));
            }
            throw syntaxError(t);
        }
        return new Parsed(name, nameAt, alias, sourcePart, on, using, branches);
    }

    private Branch branch() {
        int at = start(t);
        t++;
        boolean not = word(t, "NOT");
        if (not) {
            t++;
        }
        expect("MATCHED");
        Merge.Match match = not ? Merge.Match.NOT_MATCHED : Merge.Match.MATCHED;
        if (word(t, "BY")) {
            t++;
            if (!not) {
                throw syntaxError(t - 1);
            }
            if (!word(t, "SOURCE") && !word(t, "TARGET")) {
                throw syntaxError(t);
            }
            match = word(t, "SOURCE") ? Merge.Match.NOT_MATCHED_BY_SOURCE : Merge.Match.NOT_MATCHED;
            t++;
        }
        Part condition = null;
        if (word(t, "AND")) {
            t++;
            condition = expression(Set.of("THEN"), false);
        }
        expect("THEN");
        int actionAt = t;
        if (word(t, "UPDATE")) {
            t++;
            List<Assignment> set = null;
            if (word(t, "SET")) {
                t++;
                set = new ArrayList<>();
                do {
                    if (t < tokens.size() && kind(t) == SourceText.OPEN) {
                        throw new SqlException("Not supported: SET (columns) = ... in MERGE", position(t));
                    }
                    int columnAt = start(t);
                    String column = identifier();
                    if (!symbol(t, '=')) {
                        throw syntaxError(t);
                    }
                    t++;
                    set.add(new Assignment(column, expression(Set.of("WHEN", "RETURNING"), true), columnAt));
                } while (comma());
            }
            return checked(new Branch(match, condition, "UPDATE", set, List.of(), null, false, at), actionAt);
        }
        if (word(t, "DELETE")) {
            t++;
            return checked(new Branch(match, condition, "DELETE", null, List.of(), null, false, at), actionAt);
        }
        if (word(t, "DO")) {
            t++;
            expect("NOTHING");
            return new Branch(match, condition, "NOTHING", null, List.of(), null, false, at);
        }
        if (word(t, "INSERT")) {
            t++;
            if (word(t, "BY")) {
                t++;
                expect("NAME");
                return checked(new Branch(match, condition, "INSERT", null, List.of(), null, true, at), actionAt);
            }
            if (word(t, "DEFAULT")) {
                throw new SqlException("Not supported: INSERT DEFAULT VALUES; a table has no defaults", position(t));
            }
            List<String> columns = new ArrayList<>();
            if (t < tokens.size() && kind(t) == SourceText.OPEN) {
                t++;
                do {
                    columns.add(identifier());
                } while (comma());
                close();
            }
            List<Part> values = null;
            if (word(t, "VALUES")) {
                t++;
                if (t >= tokens.size() || kind(t) != SourceText.OPEN) {
                    throw syntaxError(t);
                }
                t++;
                values = new ArrayList<>();
                do {
                    values.add(expression(Set.of(), true));
                } while (comma());
                close();
            } else if (!columns.isEmpty()) {
                throw syntaxError(t);
            }
            return checked(new Branch(match, condition, "INSERT", null, columns, values, false, at), actionAt);
        }
        if (word(t, "ERROR")) {
            throw new SqlException("Not supported: THEN ERROR", position(t));
        }
        throw syntaxError(t);
    }

    /** Checks that the action of a branch fits the rows it is for. */
    private Branch checked(Branch b, int actionAt) {
        boolean insert = b.action().equals("INSERT");
        if (insert != (b.match() == Merge.Match.NOT_MATCHED)) {
            throw new SqlException(insert ? "Syntax error: INSERT takes only WHEN NOT MATCHED [BY TARGET]"
                    : "Syntax error: " + b.action() + " takes only WHEN MATCHED and WHEN NOT MATCHED BY SOURCE",
                    position(actionAt));
        }
        return b;
    }

    /**
     * Reads an expression from the current token to before the first token
     * on its level of parentheses, outside a {@code CASE}, that is one of
     * the words, a comma if asked for, a semicolon or a closing parenthesis.
     */
    private Part expression(Set<String> words, boolean commas) {
        int from = t;
        if (from < tokens.size() && kind(from) == SourceText.CLOSE) {
            throw syntaxError(from);
        }
        int depth = from < tokens.size() ? depth(from) : 0;
        int cases = 0;
        for (; t < tokens.size(); t++) {
            if (depth(t) < depth) {
                break;
            }
            if (depth(t) > depth) {
                continue;
            }
            if (word(t, "CASE")) {
                cases++;
            } else if (word(t, "END") && cases > 0) {
                cases--;
            } else if (cases == 0 && (kind(t) == SourceText.SEMICOLON || commas && symbol(t, ',')
                    || kind(t) == SourceText.WORD && words.contains(text.substring(start(t), end(t))
                            .toUpperCase(Locale.ROOT)) && text.charAt(start(t)) != '"')) {
                break;
            }
        }
        if (t == from) {
            throw syntaxError(t);
        }
        return new Part(start(from), end(t - 1));
    }

    private boolean comma() {
        if (symbol(t, ',')) {
            t++;
            return true;
        }
        return false;
    }

    private void close() {
        if (t >= tokens.size() || kind(t) != SourceText.CLOSE) {
            throw syntaxError(t);
        }
        t++;
    }

    /** Reads a name: a word as written, or a quoted name without its quotes. */
    private String identifier() {
        if (t >= tokens.size() || kind(t) != SourceText.WORD) {
            throw syntaxError(t);
        }
        String image = text.substring(start(t), end(t));
        t++;
        if (image.startsWith("\"")) {
            return image.substring(1, image.length() - 1).replace("\"\"", "\"");
        }
        return image;
    }

    private void expect(String word) {
        if (!word(t, word)) {
            throw syntaxError(t);
        }
        t++;
    }

    private boolean word(int i, String word) {
        return i < tokens.size() && kind(i) == SourceText.WORD && end(i) - start(i) == word.length()
                && text.regionMatches(true, start(i), word, 0, word.length());
    }

    private boolean symbol(int i, char c) {
        return i < tokens.size() && kind(i) == SourceText.OTHER && end(i) - start(i) == 1
                && text.charAt(start(i)) == c;
    }

    private int start(int i) {
        return tokens.get(i)[0];
    }

    private int end(int i) {
        return tokens.get(i)[1];
    }

    private int kind(int i) {
        return tokens.get(i)[2];
    }

    private int depth(int i) {
        return tokens.get(i)[3];
    }

    private net.fyrql.sql.Position position(int i) {
        return i < tokens.size() ? source.at(start(i)) : source.end();
    }

    private SqlException syntaxError(int i) {
        if (i >= tokens.size()) {
            return new SqlException("Syntax error at end of input", source.end());
        }
        return new SqlException("Syntax error at or near \"" + text.substring(start(i), end(i)) + "\"",
                position(i));
    }
}
