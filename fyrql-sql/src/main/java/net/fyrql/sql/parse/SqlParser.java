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
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.SqlExpr;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.parser.TokenMgrException;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one query of the DuckDB dialect into the syntax tree of FyrQL. The
 * work is done by JSqlParser, which never shows outside this package: the
 * text is first adapted where the two disagree (see {@link SourceText}),
 * then parsed, then translated, rejecting every form FyrQL does not
 * support. All errors are {@link SqlException}s with the position in the
 * original text.
 */
public final class SqlParser {

    private static final Pattern LEXICAL_POSITION = Pattern.compile("line (\\d+), column (\\d+)");

    private SqlParser() {
    }

    /**
     * A query of a prepared statement with its parameters.
     *
     * @param query the query
     * @param parameters the parameters, the one numbered {@code n} at index
     *        {@code n - 1}
     */
    public record Parameterized(Query query, List<SqlExpr.Parameter> parameters) {
        public Parameterized {
            Objects.requireNonNull(query, "query");
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Parses a query; a trailing semicolon is allowed.
     *
     * @throws SqlException if the text is not one query of the supported
     *         forms, or has a parameter
     */
    public static Query parse(String sql) {
        return parse(sql, false).query();
    }

    /**
     * Parses a query of a prepared statement, which may have parameters
     * {@code ?}; a trailing semicolon is allowed.
     *
     * @throws SqlException if the text is not one query of the supported forms
     */
    public static Parameterized parseWithParameters(String sql) {
        return parse(sql, true);
    }

    private static Parameterized parse(String sql, boolean parameters) {
        Objects.requireNonNull(sql, "sql");
        SourceText source = SourceText.of(sql);
        Statements statements = statements(source);
        if (statements == null || statements.isEmpty()) {
            throw new SqlException("Empty query", Position.UNKNOWN);
        }
        if (statements.size() > 1) {
            throw new SqlException("Only one statement is supported", position(statements.get(1), source));
        }
        JSqlParserAdapter adapter = new JSqlParserAdapter(source, parameters);
        Query query = adapter.statement(statements.get(0));
        TreeMap<Integer, SqlExpr.Parameter> byIndex = new TreeMap<>();
        for (SqlExpr.Parameter p : adapter.parameters()) {
            byIndex.putIfAbsent(p.index(), p);
        }
        List<SqlExpr.Parameter> numbered = new ArrayList<>(byIndex.values());
        for (int i = 0; i < numbered.size(); i++) {
            if (numbered.get(i).index() != i + 1) {
                throw new IllegalStateException("The parameters are not numbered 1 to " + numbered.size() + ": "
                        + byIndex.keySet());
            }
        }
        return new Parameterized(query, numbered);
    }

    private static Statements statements(SourceText source) {
        if (source.text().isBlank()) {
            return null;
        }
        CCJSqlParser parser = parser(source, false);
        try {
            // As JSqlParser itself does: the simple grammar first, which is fast on deep nesting.
            try {
                return parser.Statements();
            } catch (ParseException simple) {
                parser = parser(source, true);
                return parser.Statements();
            }
        } catch (ParseException e) {
            throw syntaxError(e, source);
        } catch (TokenMgrException e) {
            throw lexicalError(e, source);
        } catch (StackOverflowError e) {
            throw new SqlException("Query is nested too deeply", Position.UNKNOWN);
        } catch (IllegalArgumentException e) {
            // JSqlParser keeps at most three arguments of a window function and fails on more, after the call.
            if (!String.valueOf(e.getMessage()).contains("analytic expression")) {
                throw e;
            }
            Token over = parser.token == null ? null : parser.token.next != null ? parser.token.next : parser.token;
            throw new SqlException("Not supported: a window function with more than three arguments",
                    over == null ? Position.UNKNOWN : source.position(over));
        }
    }

    private static CCJSqlParser parser(SourceText source, boolean complex) {
        CCJSqlParser parser = CCJSqlParserUtil.newParser(source.text());
        if (parser == null) {
            throw new SqlException("Empty query", Position.UNKNOWN);
        }
        return parser.withAllowComplexParsing(complex);
    }

    private static SqlException syntaxError(ParseException e, SourceText source) {
        Token current = e.currentToken;
        Token next = current == null ? null : current.next;
        if (next == null) {
            return new SqlException("Syntax error", Position.UNKNOWN);
        }
        if (next.kind == 0) {
            return new SqlException("Syntax error at end of input", source.end());
        }
        return new SqlException("Syntax error at or near \"" + source.originalText(next) + "\"",
                source.position(next));
    }

    private static SqlException lexicalError(TokenMgrException e, SourceText source) {
        String message = String.valueOf(e.getMessage());
        Matcher m = LEXICAL_POSITION.matcher(message);
        Position position = m.find()
                ? source.position(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))
                : Position.UNKNOWN;
        if (message.contains("<EOF>")) {
            return new SqlException("Unterminated quoted string, quoted identifier or comment", position);
        }
        return new SqlException("Syntax error: invalid character", position);
    }

    private static Position position(Statement statement, SourceText source) {
        if (statement instanceof net.sf.jsqlparser.parser.ASTNodeAccess a && a.getASTNode() != null) {
            return source.position(a.getASTNode().jjtGetFirstToken());
        }
        return Position.UNKNOWN;
    }
}
