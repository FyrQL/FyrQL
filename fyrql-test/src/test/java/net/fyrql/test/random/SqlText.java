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

package net.fyrql.test.random;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlExpr.BinaryOp;
import net.fyrql.sql.ast.SqlExpr.LiteralKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Writes a {@linkplain ExpressionGenerator generated expression} as SQL
 * syntax over names: each column becomes the name it has in the query, and
 * each literal one of the forms SQL has for it, so that the binder types
 * them as DuckDB does: a bare {@code NULL}, a number whose type follows from
 * its digits, a string that takes the type of its context, a
 * {@code DATE '...'}, or a cast. {@code NOT BETWEEN}, {@code NOT IN} and
 * {@code NOT LIKE} appear at random, as do the other spellings of some
 * functions.
 */
final class SqlText {

    private static final Position P = Position.UNKNOWN;

    private final SplittableRandom random;
    private final List<SqlExpr> columns;

    /**
     * @param columns the syntax of each input column, by position
     */
    SqlText(SplittableRandom random, List<SqlExpr> columns) {
        this.random = random;
        this.columns = List.copyOf(columns);
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    /** Writes an expression. */
    SqlExpr of(Expression e) {
        return switch (e) {
            case Expression.Literal l -> literal(l.type(), l.value());
            case Expression.Column c -> columns.get(c.index());
            case Expression.Arithmetic a -> new SqlExpr.Binary(switch (a.op()) {
                case ADD -> BinaryOp.ADD;
                case SUBTRACT -> BinaryOp.SUBTRACT;
                case MULTIPLY -> BinaryOp.MULTIPLY;
                case DIVIDE -> BinaryOp.DIVIDE;
                case INT_DIVIDE -> BinaryOp.INT_DIVIDE;
                case REMAINDER -> BinaryOp.MODULO;
            }, of(a.left()), of(a.right()), P);
            case Expression.Negate n -> new SqlExpr.Unary(SqlExpr.UnaryOp.MINUS, of(n.operand()), P);
            case Expression.Comparison c -> new SqlExpr.Binary(switch (c.op()) {
                case EQ -> BinaryOp.EQUAL;
                case NE -> BinaryOp.NOT_EQUAL;
                case LT -> BinaryOp.LESS;
                case LE -> BinaryOp.LESS_EQUAL;
                case GT -> BinaryOp.GREATER;
                case GE -> BinaryOp.GREATER_EQUAL;
            }, of(c.left()), of(c.right()), P);
            case Expression.And a -> new SqlExpr.Binary(BinaryOp.AND, of(a.left()), of(a.right()), P);
            case Expression.Or o -> new SqlExpr.Binary(BinaryOp.OR, of(o.left()), of(o.right()), P);
            case Expression.Not n -> new SqlExpr.Unary(SqlExpr.UnaryOp.NOT, of(n.operand()), P);
            case Expression.IsNull n -> new SqlExpr.IsNull(of(n.operand()), n.negated(), P);
            case Expression.Between b -> new SqlExpr.Between(of(b.value()), of(b.low()), of(b.high()), chance(20), P);
            case Expression.In in -> {
                List<SqlExpr> list = new ArrayList<>();
                for (Expression x : in.list()) {
                    list.add(of(x));
                }
                yield new SqlExpr.InList(of(in.value()), list, chance(20), P);
            }
            case Expression.Case c -> {
                List<SqlExpr.When> whens = new ArrayList<>();
                for (Expression.When w : c.whens()) {
                    whens.add(new SqlExpr.When(of(w.condition()), of(w.result())));
                }
                boolean nullOtherwise = c.otherwise() instanceof Expression.Literal l && l.value() == null;
                yield new SqlExpr.Case(null, whens, nullOtherwise && chance(50) ? null : of(c.otherwise()), P);
            }
            case Expression.If i -> call("if", of(i.condition()), of(i.whenTrue()), of(i.whenFalse()));
            case Expression.Coalesce c -> {
                List<SqlExpr> operands = new ArrayList<>();
                for (Expression x : c.operands()) {
                    operands.add(of(x));
                }
                yield new SqlExpr.FunctionCall(operands.size() == 2 && chance(30) ? "ifnull" : "coalesce", operands, P);
            }
            case Expression.Cast c -> new SqlExpr.Cast(of(c.operand()), c.target(), c.safe(), P);
            case Expression.Like l -> new SqlExpr.Like(of(l.string()), of(l.pattern()), l.caseInsensitive(), chance(20),
                    P);
            case Expression.IsDistinct d -> new SqlExpr.IsDistinct(of(d.left()), of(d.right()), d.negated(), P);
            case Expression.Call c -> call(c);
        };
    }

    private SqlExpr call(Expression.Call c) {
        List<SqlExpr> args = new ArrayList<>();
        for (Expression a : c.arguments()) {
            args.add(of(a));
        }
        String name = c.function().name();
        if (name.equals("||")) {
            return new SqlExpr.Binary(BinaryOp.CONCAT, args.get(0), args.get(1), P);
        }
        if (name.equals("epoch") && chance(50)) {
            // A constant 'epoch' part, which the binder writes as epoch().
            return call("date_part", new SqlExpr.Literal(LiteralKind.STRING, "epoch", P), args.get(0));
        }
        return new SqlExpr.FunctionCall(name, args, P);
    }

    private static SqlExpr call(String name, SqlExpr... args) {
        return new SqlExpr.FunctionCall(name, List.of(args), P);
    }

    /** Writes a literal in one of the forms SQL has for it. */
    SqlExpr literal(DataType type, Object value) {
        if (value == null) {
            return chance(50) ? new SqlExpr.Literal(LiteralKind.NULL, "NULL", P) : cast(nullLiteral(), type);
        }
        if (type == DataType.BOOLEAN) {
            return new SqlExpr.Literal(LiteralKind.BOOLEAN, value.toString(), P);
        }
        if (type == DataType.VARCHAR) {
            return string((String) value);
        }
        if (type == DataType.DATE || type == DataType.TIMESTAMP) {
            String text = type == DataType.DATE ? Conversions.formatDate((Integer) value)
                    : Conversions.formatTimestamp((Long) value);
            int form = random.nextInt(3);
            return form == 0 ? new SqlExpr.Literal(type == DataType.DATE ? LiteralKind.DATE : LiteralKind.TIMESTAMP,
                    text, P) : form == 1 ? string(text) : cast(string(text), type);
        }
        if (type == DataType.FLOAT || type == DataType.DOUBLE) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || chance(50)) {
                return cast(string(Casts.format(value, type)), type);
            }
            // A number with an exponent is a DOUBLE.
            SqlExpr number = number(new BigDecimal(Double.toString(Math.abs(d))).toString().replace("E", "e")
                    + (Double.toString(d).contains("E") ? "" : "e0"), d < 0 || 1 / d < 0);
            return type == DataType.DOUBLE ? number : cast(number, type);
        }
        if (type instanceof DataType.Decimal) {
            BigDecimal d = (BigDecimal) value;
            SqlExpr number = number(d.abs().toPlainString(), d.signum() < 0);
            return chance(60) ? number : cast(number, type);
        }
        // An integral type: the bare number takes the smallest type from INTEGER on.
        String digits = value.toString();
        boolean negative = digits.startsWith("-");
        SqlExpr number = number(negative ? digits.substring(1) : digits, negative);
        return chance(60) ? number : cast(number, type);
    }

    private static SqlExpr number(String digits, boolean negative) {
        SqlExpr n = new SqlExpr.Literal(LiteralKind.NUMBER, digits, P);
        return negative ? new SqlExpr.Unary(SqlExpr.UnaryOp.MINUS, n, P) : n;
    }

    static SqlExpr string(String value) {
        return new SqlExpr.Literal(LiteralKind.STRING, value, P);
    }

    private static SqlExpr nullLiteral() {
        return new SqlExpr.Literal(LiteralKind.NULL, "NULL", P);
    }

    private SqlExpr cast(SqlExpr operand, DataType type) {
        return new SqlExpr.Cast(operand, type, false, P);
    }
}
