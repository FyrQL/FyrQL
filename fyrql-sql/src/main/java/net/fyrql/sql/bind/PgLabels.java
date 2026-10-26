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

package net.fyrql.sql.bind;

import net.fyrql.core.type.DataType;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;

import java.util.Locale;
import java.util.function.Function;

/**
 * The names PostgreSQL gives the items of a select list without an alias, for the sessions that name columns as
 * PostgreSQL does: a column its name, a field its name, a call its function, a cast the name of its operand or else
 * of its type, {@code CASE} and a few other forms their keyword, and every other expression {@code ?column?}.
 * PostgreSQL ranks the names, so that a cast takes the name of its type over the keyword of its operand.
 */
final class PgLabels {

    /** The name of an item that PostgreSQL cannot name. */
    static final String UNNAMED = "?column?";

    private PgLabels() {
    }

    /** A name with its rank: a strong name stays the name of a cast of the expression, a weak one does not. */
    private record Label(String name, boolean strong) {
    }

    /**
     * Returns the name of an item.
     *
     * @param e the expression of the item
     * @param columns the name of a column that an identifier refers to
     */
    static String name(SqlExpr e, Function<SqlExpr.Identifier, String> columns) {
        Label label = label(e, columns);
        return label == null ? UNNAMED : label.name();
    }

    private static Label label(SqlExpr e, Function<SqlExpr.Identifier, String> columns) {
        return switch (e) {
            case SqlExpr.Identifier id -> strong(columns.apply(id));
            case SqlExpr.FunctionCall f -> call(f.name());
            case SqlExpr.Window w -> call(w.call().name());
            case SqlExpr.Cast c -> {
                Label operand = label(c.operand(), columns);
                yield operand != null && operand.strong() ? operand : weak(typeName(c.type()));
            }
            case SqlExpr.Literal l -> switch (l.kind()) {
                case DATE -> weak("date");
                case TIME -> weak("time");
                case TIMESTAMP -> weak("timestamp");
                case TIMESTAMPTZ -> weak("timestamptz");
                case NUMBER, STRING, BOOLEAN, NULL -> null;
            };
            case SqlExpr.Interval i -> weak("interval");
            case SqlExpr.Case c -> weak("case");
            case SqlExpr.Row r -> weak("row");
            case SqlExpr.Exists x -> x.negated() ? null : strong("exists");
            case SqlExpr.ScalarSubquery s -> strong(firstColumn(s.query(), columns));
            case SqlExpr.Index i -> i.index() instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.STRING
                    ? strong(l.text()) : label(i.base(), columns);
            default -> null;
        };
    }

    /** The name of a call: its function, which PostgreSQL calls {@code btrim} for {@code TRIM}. */
    private static Label call(String function) {
        String name = function.toLowerCase(Locale.ROOT);
        return strong(name.equals("trim") ? "btrim" : name);
    }

    /** The name of the first column of a subquery, as PostgreSQL names the value of a scalar subquery. */
    private static String firstColumn(Query query, Function<SqlExpr.Identifier, String> columns) {
        QueryBody body = query.body();
        while (true) {
            switch (body) {
                case QueryBody.SetOperation s -> body = s.left();
                case QueryBody.Parenthesized p -> body = p.query().body();
                case QueryBody.Select s -> {
                    if (s.items().isEmpty() || !(s.items().get(0) instanceof SelectItem.ExpressionItem item)) {
                        return null;
                    }
                    // The names of the subquery's columns are its own, so an identifier keeps its last part.
                    return item.alias() != null ? item.alias()
                            : name(item.expression(), id -> id.parts().get(id.parts().size() - 1));
                }
                default -> {
                    return null;
                }
            }
        }
    }

    /** The name PostgreSQL gives a type, as {@code int4} for {@code INTEGER}; a list takes that of its element. */
    static String typeName(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> "numeric";
            case DataType.ListType l -> typeName(l.element());
            case DataType.Struct s -> "record";
            case DataType.MapType m -> "map";
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> "bool";
                case TINYINT, SMALLINT -> "int2";
                case INTEGER -> "int4";
                case BIGINT -> "int8";
                case HUGEINT -> "numeric";
                case FLOAT -> "float4";
                case DOUBLE -> "float8";
                case VARCHAR -> "text";
                case BLOB -> "bytea";
                case DATE -> "date";
                case TIME -> "time";
                case TIMESTAMP -> "timestamp";
                case TIMESTAMPTZ -> "timestamptz";
                case INTERVAL -> "interval";
                case UUID -> "uuid";
            };
        };
    }

    private static Label strong(String name) {
        return name == null ? null : new Label(name, true);
    }

    private static Label weak(String name) {
        return new Label(name, false);
    }
}
