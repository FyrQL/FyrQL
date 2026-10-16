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

import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.Query;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.parser.TokenMgrException;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;

import java.util.Objects;
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
     * Parses a query; a trailing semicolon is allowed.
     *
     * @throws SqlException if the text is not one query of the supported forms
     */
    public static Query parse(String sql) {
        Objects.requireNonNull(sql, "sql");
        SourceText source = SourceText.of(sql);
        Statements statements = statements(source);
        if (statements == null || statements.isEmpty()) {
            throw new SqlException("Empty query", Position.UNKNOWN);
        }
        if (statements.size() > 1) {
            throw new SqlException("Only one statement is supported", position(statements.get(1), source));
        }
        return new JSqlParserAdapter(source).statement(statements.get(0));
    }

    private static Statements statements(SourceText source) {
        if (source.text().isBlank()) {
            return null;
        }
        try {
            // As JSqlParser itself does: the simple grammar first, which is fast on deep nesting.
            try {
                return parser(source, false).Statements();
            } catch (ParseException simple) {
                return parser(source, true).Statements();
            }
        } catch (ParseException e) {
            throw syntaxError(e, source);
        } catch (TokenMgrException e) {
            throw lexicalError(e, source);
        } catch (StackOverflowError e) {
            throw new SqlException("Query is nested too deeply", Position.UNKNOWN);
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
