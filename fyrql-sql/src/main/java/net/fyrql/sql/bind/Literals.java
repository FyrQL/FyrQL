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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * The types of literals, and the evaluation of constant expressions, as the
 * binder needs them.
 */
final class Literals {

    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** A batch of one row without columns, on which constant expressions are evaluated. */
    private static final Batch ONE_ROW = new Batch(new Schema(List.of()), 1, List.of());

    private Literals() {
    }

    /**
     * Types a numeric literal as DuckDB 1.5.6 does. A number with an
     * exponent is a {@code DOUBLE}; one with a decimal point a
     * {@code DECIMAL} with as many digits as it is written with, or a
     * {@code DOUBLE} beyond 38 digits. An integer is an {@code INTEGER} if
     * its digits fit one, else a {@code BIGINT} if they fit one; larger
     * digits become a {@code BIGINT} or {@code HUGEINT} by the signed value,
     * since DuckDB folds a minus sign into such a literal, and a
     * {@code DOUBLE} beyond. So {@code -2147483648} is a {@code BIGINT} and
     * {@code -9223372036854775808} as well.
     *
     * @param text the digits as written, without a sign
     * @param negative whether a minus sign precedes the literal
     */
    static ExpressionBinder.Bound number(String text, boolean negative, Position position) {
        try {
            if (text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                double d = Double.parseDouble(text);
                return ExpressionBinder.Bound.typed(new Expression.Literal(DataType.DOUBLE, negative ? -d : d));
            }
            int dot = text.indexOf('.');
            if (dot >= 0) {
                int scale = text.length() - dot - 1;
                int precision = Math.max(1, dot + scale);
                if (precision > DataType.MAX_DECIMAL_PRECISION) {
                    double d = Double.parseDouble(text);
                    return ExpressionBinder.Bound.typed(new Expression.Literal(DataType.DOUBLE, negative ? -d : d));
                }
                BigDecimal value = new BigDecimal(text).setScale(scale);
                return ExpressionBinder.Bound.typed(new Expression.Literal(DataType.decimal(precision, scale),
                        negative ? value.negate() : value));
            }
            BigInteger digits = new BigInteger(text);
            BigInteger value = negative ? digits.negate() : digits;
            if (digits.compareTo(INT_MAX) <= 0) {
                return integer(DataType.INTEGER, value.intValueExact());
            }
            if (digits.compareTo(LONG_MAX) <= 0
                    || value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
                return integer(DataType.BIGINT, value.longValueExact());
            }
            if (Values.inHugeIntRange(value)) {
                return integer(DataType.HUGEINT, value);
            }
            return ExpressionBinder.Bound.typed(new Expression.Literal(DataType.DOUBLE, value.doubleValue()));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new SqlException("Invalid number " + text, position);
        }
    }

    private static ExpressionBinder.Bound integer(DataType type, Object value) {
        return new ExpressionBinder.Bound(new Expression.Literal(type, value), ExpressionBinder.Kind.INTEGER);
    }

    /** Tells whether the value of an integer literal fits into an integral type. */
    static boolean fits(Expression.Literal literal, DataType integral) {
        try {
            Casts.cast(literal.value(), literal.type(), integral);
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    /**
     * Converts a constant to another type, as the binder does for a literal
     * whose type the context decides.
     *
     * @throws SqlException if the value does not convert
     */
    static Expression.Literal convert(Expression.Literal literal, DataType target, Position position) {
        if (!Casts.allowed(literal.type(), target)) {
            throw new SqlException("Cannot cast " + literal.type() + " to " + target, position);
        }
        try {
            return new Expression.Literal(target, Casts.cast(literal.value(), literal.type(), target));
        } catch (RuntimeException e) {
            String value = literal.type() == DataType.VARCHAR ? "string '" + literal.value() + "'"
                    : String.valueOf(literal.value());
            throw new SqlException("Could not convert " + value + " to " + target, position);
        }
    }

    /**
     * Converts a literal to another type as {@link #convert} does, except
     * that a string that does not convert becomes a cast, which fails when
     * it is evaluated: DuckDB converts such a literal only when a row
     * reaches it.
     *
     * @throws SqlException if the types do not cast at all
     */
    static Expression lazily(Expression.Literal literal, DataType target, Position position) {
        try {
            return convert(literal, target, position);
        } catch (SqlException e) {
            if (literal.type() == DataType.VARCHAR && Casts.allowed(DataType.VARCHAR, target)) {
                return new Expression.Cast(literal, target);
            }
            throw e;
        }
    }

    /**
     * Evaluates an expression without columns.
     *
     * @throws SqlException if the evaluation fails
     */
    static Object evaluate(Expression e, Position position) {
        try {
            return Evaluator.evalRow(e, ONE_ROW, 0);
        } catch (RuntimeException x) {
            throw new SqlException(x.getMessage() != null ? x.getMessage() : x.toString(), position);
        }
    }
}
