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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.parse.Parameters;
import net.fyrql.sql.parse.SqlParser;
import net.fyrql.test.SqlCorpusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parameters that {@link Parameters} finds in a query text, where a
 * prepared query puts the literals of its values, are those the parser
 * numbers: over the texts of the SQL corpus and 2000 random queries, each
 * with every integer in turn, and then all of them, written as a {@code ?}.
 * A text the parser does not read with a {@code ?} in that place, as in a
 * type such as {@code DECIMAL(10, 2)}, is left out; a text it reads is
 * prepared, which binds it or fails with a {@link SqlException}.
 */
class ParameterTextTest {

    private static final int RANDOM_QUERIES = 2000;

    /** Texts with a {@code ?} where it is no parameter. */
    private static final List<String> TEXTS = List.of(
            "SELECT '?' AS \"?\", 'it''s ?' AS a, -- ?\n /* ? */ $$ ? $$ AS c FROM t WHERE a = 1",
            "SELECT \"a?\"\"b\" FROM t WHERE b = 'x?' OR b = 2");

    @Test
    void theLexerFindsTheParametersTheParserFinds() {
        List<String> texts = new ArrayList<>(TEXTS);
        texts.addAll(SqlCorpusTest.QUERIES);
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED))) {
            for (int i = 0; i < RANDOM_QUERIES; i++) {
                texts.add(new SqlGenerator(RandomRun.random(i), engine).generate().sql());
            }
            int checked = 0;
            int parameters = 0;
            int unread = 0;
            int prepared = 0;
            for (String text : texts) {
                for (String variant : variants(text)) {
                    SqlParser.Parameterized parsed;
                    try {
                        parsed = SqlParser.parseWithParameters(variant);
                    } catch (SqlException e) {
                        unread++;
                        continue;
                    }
                    assertEquals(parsed.parameters().size(), Parameters.parse(variant).count(), variant);
                    checked++;
                    parameters += parsed.parameters().size();
                    try {
                        engine.prepare(variant);
                        prepared++;
                    } catch (SqlException e) {
                        // Not every parameter has a type, or the text does not bind.
                    }
                }
            }
            System.out.println("Parameters in query texts: " + texts.size() + " texts, " + checked
                    + " variants read with " + parameters + " parameters, the same the lexer finds; " + prepared
                    + " prepared, " + unread + " not read");
            assertTrue(checked > texts.size(), checked + " variants of " + texts.size() + " texts");
            assertTrue(prepared > texts.size() / 2, prepared + " of " + checked + " variants prepared");
        }
    }

    /**
     * Returns the text itself, the text with each integer in turn written as
     * a {@code ?}, and the text with all of them so; integers in strings,
     * quoted names and comments stay.
     */
    static List<String> variants(String text) {
        List<int[]> integers = integers(text);
        List<String> result = new ArrayList<>();
        result.add(text);
        for (int[] span : integers) {
            result.add(text.substring(0, span[0]) + "?" + text.substring(span[1]));
        }
        if (integers.size() > 1) {
            StringBuilder b = new StringBuilder();
            int from = 0;
            for (int[] span : integers) {
                b.append(text, from, span[0]).append('?');
                from = span[1];
            }
            result.add(b.append(text.substring(from)).toString());
        }
        return result;
    }

    /** Returns the start and end of each integer of the text outside strings, quoted names and comments. */
    private static List<int[]> integers(String text) {
        List<int[]> result = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && !(text.charAt(j) == c && (j + 1 == n || text.charAt(j + 1) != c))) {
                    // A doubled quote stays inside.
                    j += text.charAt(j) == c ? 2 : 1;
                }
                i = j + 1;
            } else if (text.startsWith("--", i)) {
                int j = text.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (text.startsWith("/*", i) || text.startsWith("$$", i)) {
                String end = c == '/' ? "*/" : "$$";
                int j = text.indexOf(end, i + 2);
                i = j < 0 ? n : j + 2;
            } else if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'
                        || text.charAt(i) == '$')) {
                    i++;
                }
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                boolean fraction = i > 0 && text.charAt(i - 1) == '.'
                        || j < n && (text.charAt(j) == '.' || Character.isLetter(text.charAt(j)));
                if (!fraction) {
                    result.add(new int[] {i, j});
                }
                i = j;
                while (i < n && (text.charAt(i) == '.' || Character.isLetterOrDigit(text.charAt(i)))) {
                    i++;
                }
            } else {
                i++;
            }
        }
        return result;
    }
}
