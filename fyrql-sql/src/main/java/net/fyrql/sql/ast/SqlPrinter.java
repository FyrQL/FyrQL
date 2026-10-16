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

package net.fyrql.sql.ast;

import net.fyrql.sql.ast.FromItem.Alias;
import net.fyrql.sql.ast.QueryBody.SetKind;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes a syntax tree back as SQL text in one canonical form: every
 * operator expression in parentheses, keywords in upper case, identifiers
 * in double quotes only where needed. Parsing the text gives the same tree
 * up to positions, so the text serves to compare trees and to show them.
 */
public final class SqlPrinter {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Words that are quoted as identifiers, since they would read as keywords. */
    private static final Set<String> KEYWORDS = Set.of("ALL", "AND", "ANTI", "ANY", "AS", "ASC", "BETWEEN", "BY",
            "CASE", "CAST", "CROSS", "CURRENT_DATE", "CURRENT_TIMESTAMP", "DATE", "DESC", "DISTINCT", "DIV", "ELSE",
            "END", "EXCEPT", "EXCLUDE", "EXISTS", "EXTRACT", "FALSE", "FILTER", "FIRST", "FOR", "FROM", "FULL",
            "GROUP", "HAVING", "ILIKE", "IN", "INNER", "INTERSECT", "INTERVAL", "IS", "JOIN", "LAST", "LEFT", "LIKE",
            "LIMIT", "NATURAL", "NOT", "NULL", "NULLS", "OFFSET", "ON", "OR", "ORDER", "OUTER", "OVER", "QUALIFY",
            "REPLACE", "RIGHT", "SELECT", "SEMI", "TABLE", "THEN", "TIMESTAMP", "TRUE", "TRY_CAST", "UNION", "USING",
            "VALUES", "WHEN", "WHERE", "WINDOW", "WITH");

    /** Functions without arguments that are written as keywords, without parentheses. */
    private static final Set<String> SPECIAL_FUNCTIONS = Set.of("CURRENT_DATE", "CURRENT_TIMESTAMP");

    private SqlPrinter() {
    }

    /** Returns the text of a query. */
    public static String print(Query query) {
        StringBuilder b = new StringBuilder();
        query(b, query);
        return b.toString();
    }

    /** Returns the text of an expression. */
    public static String print(SqlExpr expression) {
        StringBuilder b = new StringBuilder();
        expr(b, expression);
        return b.toString();
    }

    /** Returns an identifier, in double quotes if it is not a plain word or reads as a keyword. */
    public static String identifier(String name) {
        if (PLAIN_IDENTIFIER.matcher(name).matches() && !KEYWORDS.contains(name.toUpperCase(Locale.ROOT))) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Returns a string literal in single quotes. */
    public static String string(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    private static void query(StringBuilder b, Query q) {
        if (!q.with().isEmpty()) {
            b.append("WITH ");
            for (int i = 0; i < q.with().size(); i++) {
                Query.CommonTable t = q.with().get(i);
                b.append(i > 0 ? ", " : "").append(identifier(t.name()));
                if (!t.columns().isEmpty()) {
                    names(b, t.columns());
                }
                b.append(" AS (");
                query(b, t.query());
                b.append(')');
            }
            b.append(' ');
        }
        body(b, q.body());
        orderBy(b, q.orderBy());
        if (q.limit() != null) {
            b.append(" LIMIT ");
            expr(b, q.limit());
        }
        if (q.offset() != null) {
            b.append(" OFFSET ");
            expr(b, q.offset());
        }
    }

    private static void body(StringBuilder b, QueryBody body) {
        switch (body) {
            case QueryBody.Select s -> select(b, s);
            case QueryBody.SetOperation s -> {
                operand(b, s.left(), s.kind(), false);
                b.append(' ').append(s.kind()).append(s.all() ? " ALL " : " ");
                operand(b, s.right(), s.kind(), true);
            }
            case QueryBody.Values v -> {
                b.append("VALUES ");
                for (int i = 0; i < v.rows().size(); i++) {
                    b.append(i > 0 ? ", " : "");
                    exprs(b, v.rows().get(i));
                }
            }
            case QueryBody.Parenthesized p -> {
                b.append('(');
                query(b, p.query());
                b.append(')');
            }
        }
    }

    /** Writes an operand of a set operation, in parentheses where the precedence would change otherwise. */
    private static void operand(StringBuilder b, QueryBody operand, SetKind parent, boolean right) {
        boolean parens = operand instanceof QueryBody.SetOperation s
                && (right || s.kind() != SetKind.INTERSECT && parent == SetKind.INTERSECT);
        if (parens) {
            b.append('(');
        }
        body(b, operand);
        if (parens) {
            b.append(')');
        }
    }

    private static void select(StringBuilder b, QueryBody.Select s) {
        b.append("SELECT ").append(s.distinct() ? "DISTINCT " : "");
        for (int i = 0; i < s.items().size(); i++) {
            b.append(i > 0 ? ", " : "");
            switch (s.items().get(i)) {
                case SelectItem.Star star -> {
                    for (String part : star.qualifier()) {
                        b.append(identifier(part)).append('.');
                    }
                    b.append('*');
                    if (!star.exclude().isEmpty()) {
                        b.append(" EXCLUDE ");
                        names(b, star.exclude());
                    }
                    if (!star.replace().isEmpty()) {
                        b.append(" REPLACE (");
                        for (int k = 0; k < star.replace().size(); k++) {
                            b.append(k > 0 ? ", " : "");
                            expr(b, star.replace().get(k).expression());
                            b.append(" AS ").append(identifier(star.replace().get(k).name()));
                        }
                        b.append(')');
                    }
                }
                case SelectItem.ExpressionItem item -> {
                    expr(b, item.expression());
                    if (item.alias() != null) {
                        b.append(" AS ").append(identifier(item.alias()));
                    }
                }
            }
        }
        if (s.from() != null) {
            b.append(" FROM ");
            from(b, s.from());
        }
        if (s.where() != null) {
            b.append(" WHERE ");
            expr(b, s.where());
        }
        if (s.groupBy() != null) {
            b.append(" GROUP BY ");
            if (s.groupBy().all()) {
                b.append("ALL");
            } else {
                list(b, s.groupBy().expressions());
            }
        }
        if (s.having() != null) {
            b.append(" HAVING ");
            expr(b, s.having());
        }
    }

    private static void from(StringBuilder b, FromItem item) {
        switch (item) {
            case FromItem.TableName t -> {
                qualified(b, t.name());
                alias(b, t.alias());
            }
            case FromItem.FileName f -> {
                b.append(string(f.path()));
                alias(b, f.alias());
            }
            case FromItem.Subquery s -> {
                b.append('(');
                query(b, s.query());
                b.append(')');
                alias(b, s.alias());
            }
            case FromItem.TableFunctionCall f -> {
                b.append(identifier(f.name())).append('(');
                list(b, f.arguments());
                int i = f.arguments().size();
                for (Map.Entry<String, SqlExpr> e : f.namedArguments().entrySet()) {
                    b.append(i++ > 0 ? ", " : "").append(identifier(e.getKey())).append(" = ");
                    expr(b, e.getValue());
                }
                b.append(')');
                alias(b, f.alias());
            }
            case FromItem.Join j -> {
                from(b, j.left());
                if (j.natural()) {
                    b.append(j.kind() == FromItem.JoinKind.INNER ? " NATURAL JOIN " : " NATURAL " + j.kind() + " JOIN ");
                } else {
                    b.append(' ').append(j.kind()).append(" JOIN ");
                }
                if (j.right() instanceof FromItem.Join) {
                    b.append('(');
                    from(b, j.right());
                    b.append(')');
                } else {
                    from(b, j.right());
                }
                if (j.on() != null) {
                    b.append(" ON ");
                    expr(b, j.on());
                } else if (!j.using().isEmpty()) {
                    b.append(" USING ");
                    names(b, j.using());
                }
            }
        }
    }

    private static void alias(StringBuilder b, Alias alias) {
        if (alias != null) {
            b.append(" AS ").append(identifier(alias.name()));
            if (!alias.columns().isEmpty()) {
                names(b, alias.columns());
            }
        }
    }

    private static void orderBy(StringBuilder b, List<OrderItem> items) {
        if (items.isEmpty()) {
            return;
        }
        b.append(" ORDER BY ");
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            b.append(i > 0 ? ", " : "");
            if (item.isAll()) {
                b.append("ALL");
            } else {
                expr(b, item.expression());
            }
            b.append(item.ascending() ? " ASC" : " DESC").append(item.nullsFirst() ? " NULLS FIRST" : " NULLS LAST");
        }
    }

    private static void expr(StringBuilder b, SqlExpr e) {
        switch (e) {
            case SqlExpr.Literal l -> {
                switch (l.kind()) {
                    case NUMBER, BOOLEAN, NULL -> b.append(l.text());
                    case STRING -> b.append(string(l.text()));
                    case DATE -> b.append("DATE ").append(string(l.text()));
                    case TIMESTAMP -> b.append("TIMESTAMP ").append(string(l.text()));
                }
            }
            case SqlExpr.Identifier id -> qualified(b, id.parts());
            case SqlExpr.Binary x -> {
                b.append('(');
                expr(b, x.left());
                b.append(' ').append(x.op().symbol()).append(' ');
                expr(b, x.right());
                b.append(')');
            }
            case SqlExpr.Unary u -> {
                b.append('(').append(u.op().symbol());
                expr(b, u.operand());
                b.append(')');
            }
            case SqlExpr.Between x -> {
                b.append('(');
                expr(b, x.value());
                b.append(x.negated() ? " NOT BETWEEN " : " BETWEEN ");
                expr(b, x.low());
                b.append(" AND ");
                expr(b, x.high());
                b.append(')');
            }
            case SqlExpr.InList x -> {
                b.append('(');
                expr(b, x.value());
                b.append(x.negated() ? " NOT IN " : " IN ");
                exprs(b, x.list());
                b.append(')');
            }
            case SqlExpr.InQuery x -> {
                b.append('(');
                expr(b, x.value());
                b.append(x.negated() ? " NOT IN (" : " IN (");
                query(b, x.query());
                b.append("))");
            }
            case SqlExpr.Exists x -> {
                b.append(x.negated() ? "(NOT EXISTS (" : "(EXISTS (");
                query(b, x.query());
                b.append("))");
            }
            case SqlExpr.ScalarSubquery s -> {
                b.append('(');
                query(b, s.query());
                b.append(')');
            }
            case SqlExpr.Like x -> {
                b.append('(');
                expr(b, x.value());
                b.append(x.negated() ? " NOT" : "").append(x.caseInsensitive() ? " ILIKE " : " LIKE ");
                expr(b, x.pattern());
                b.append(')');
            }
            case SqlExpr.IsNull x -> {
                b.append('(');
                expr(b, x.operand());
                b.append(x.negated() ? " IS NOT NULL)" : " IS NULL)");
            }
            case SqlExpr.IsDistinct x -> {
                b.append('(');
                expr(b, x.left());
                b.append(x.negated() ? " IS NOT DISTINCT FROM " : " IS DISTINCT FROM ");
                expr(b, x.right());
                b.append(')');
            }
            case SqlExpr.Case c -> {
                b.append("CASE");
                if (c.operand() != null) {
                    b.append(' ');
                    expr(b, c.operand());
                }
                for (SqlExpr.When w : c.whens()) {
                    b.append(" WHEN ");
                    expr(b, w.condition());
                    b.append(" THEN ");
                    expr(b, w.result());
                }
                if (c.otherwise() != null) {
                    b.append(" ELSE ");
                    expr(b, c.otherwise());
                }
                b.append(" END");
            }
            case SqlExpr.Cast c -> {
                b.append(c.safe() ? "TRY_CAST(" : "CAST(");
                expr(b, c.operand());
                b.append(" AS ").append(c.type().sqlName()).append(')');
            }
            case SqlExpr.FunctionCall f when f.arguments().isEmpty() && !f.star()
                    && SPECIAL_FUNCTIONS.contains(f.name().toUpperCase(Locale.ROOT)) ->
                b.append(f.name().toUpperCase(Locale.ROOT));
            case SqlExpr.FunctionCall f -> {
                b.append(PLAIN_IDENTIFIER.matcher(f.name()).matches() ? f.name() : identifier(f.name())).append('(');
                if (f.star()) {
                    b.append('*');
                }
                b.append(f.distinct() ? "DISTINCT " : "");
                list(b, f.arguments());
                orderBy(b, f.orderBy());
                b.append(')');
            }
            case SqlExpr.Index x -> {
                // A name or an index takes its index directly; other bases need parentheses.
                boolean bare = x.base() instanceof SqlExpr.Identifier || x.base() instanceof SqlExpr.Index;
                b.append(bare ? "" : "(");
                expr(b, x.base());
                b.append(bare ? "[" : ")[");
                if (x.index() instanceof SqlExpr.Unary u && u.op() == SqlExpr.UnaryOp.MINUS
                        && u.operand() instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.NUMBER) {
                    // As DuckDB names the column l[-1].
                    b.append('-').append(l.text());
                } else {
                    int start = b.length();
                    expr(b, x.index());
                    // The brackets delimit the index, and JSqlParser reads no operator in parentheses there.
                    if (x.index() instanceof SqlExpr.Binary || x.index() instanceof SqlExpr.Unary) {
                        b.deleteCharAt(b.length() - 1).deleteCharAt(start);
                    }
                }
                b.append(']');
            }
            case SqlExpr.StructValue s -> {
                b.append('{');
                for (int i = 0; i < s.names().size(); i++) {
                    b.append(i > 0 ? ", " : "").append(string(s.names().get(i))).append(": ");
                    expr(b, s.values().get(i));
                }
                b.append('}');
            }
            case SqlExpr.Interval i -> b.append("INTERVAL ").append(string(i.value())).append(' ').append(i.unit());
        }
    }

    private static void qualified(StringBuilder b, List<String> parts) {
        for (int i = 0; i < parts.size(); i++) {
            b.append(i > 0 ? "." : "").append(identifier(parts.get(i)));
        }
    }

    private static void names(StringBuilder b, List<String> names) {
        b.append('(');
        for (int i = 0; i < names.size(); i++) {
            b.append(i > 0 ? ", " : "").append(identifier(names.get(i)));
        }
        b.append(')');
    }

    private static void exprs(StringBuilder b, List<SqlExpr> list) {
        b.append('(');
        list(b, list);
        b.append(')');
    }

    private static void list(StringBuilder b, List<SqlExpr> list) {
        for (int i = 0; i < list.size(); i++) {
            b.append(i > 0 ? ", " : "");
            expr(b, list.get(i));
        }
    }
}
