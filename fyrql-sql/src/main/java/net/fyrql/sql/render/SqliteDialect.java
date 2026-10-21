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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.util.List;

/**
 * The dialect of SQLite, whose columns have an affinity instead of a type:
 * the declared names are those of FyrQL, so that a catalog reads them
 * back, except that {@code INTEGER} is written as {@code INT4}, since SQLite
 * reads {@code INTEGER} as 64 bits; a value is written as a plain number or
 * string, without a cast. {@code BOOLEAN} is {@code 1} or {@code 0}; dates and timestamps are
 * strings; a decimal is stored as a floating-point number, so a decimal
 * with more than 15 digits is not exact; {@code HUGEINT}, {@code NaN} and
 * the infinities have no literal. {@code LIKE} ignores the case of ASCII
 * letters, so the two forms of the match are written alike; {@code EXCEPT}
 * and {@code INTERSECT} have no {@code ALL}, and {@code group_concat}
 * takes no separator with {@code DISTINCT}. A {@code VALUES} list names
 * its columns {@code column1}, {@code column2}, ...
 * <p>
 * A source evaluates comparisons of integers and booleans, and of strings,
 * which SQLite compares byte by byte as FyrQL does; decimals, dates and
 * timestamps are left to FyrQL, since SQLite does not store them as such.
 * It groups and counts, and takes the minimum and maximum of integers, but
 * does not sum: SQLite has no exact type for a sum beyond {@code BIGINT}.
 */
public final class SqliteDialect extends StandardDialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    String scalarTypeName(Scalar type) {
        if (type == Scalar.HUGEINT) {
            throw new IllegalArgumentException(name() + " has no type for HUGEINT");
        }
        // A column declared INTEGER holds 64 bits in SQLite, and a catalog reads it as BIGINT.
        return switch (type) {
            case INTEGER -> "INT4";
            case TIMESTAMPTZ -> "TIMESTAMP";
            case INTERVAL -> "VARCHAR";
            default -> type.sqlName();
        };
    }

    @Override
    String nullLiteral(DataType type) {
        return "NULL";
    }

    @Override
    String booleanLiteral(boolean value) {
        return value ? "1" : "0";
    }

    @Override
    String integerLiteral(Object value, Scalar type) {
        return value.toString();
    }

    @Override
    String hugeIntLiteral(Object value) {
        throw new IllegalArgumentException(name() + " cannot write a HUGEINT");
    }

    @Override
    String decimalLiteral(BigDecimal value, Decimal type) {
        return value.toPlainString();
    }

    @Override
    String floatingLiteral(String digits, Scalar type) {
        return digits;
    }

    @Override
    String dateLiteral(int days) {
        return string(Conversions.formatDate(days));
    }

    @Override
    String timestampLiteral(long micros) {
        return string(Conversions.formatTimestamp(micros));
    }

    @Override
    String uuidLiteral(java.util.UUID value) {
        return string(value.toString());
    }

    @Override
    String timeLiteral(long micros) {
        return string(Conversions.formatTime(micros));
    }

    /** An instant as the text of its timestamp in UTC. */
    @Override
    String timestampTzLiteral(long micros) {
        return timestampLiteral(micros);
    }

    @Override
    String intervalLiteral(net.fyrql.core.value.Interval value) {
        return string(value.toString());
    }

    @Override
    public String like(String string, String pattern, boolean caseInsensitive) {
        return "(" + string + " LIKE " + pattern + ")";
    }

    @Override
    public String isDistinct(String left, String right, boolean negated) {
        return "(" + left + (negated ? " IS " : " IS NOT ") + right + ")";
    }

    @Override
    public String arithmetic(Expression.ArithmeticOp op, String left, String right) {
        return super.arithmetic(op == Expression.ArithmeticOp.INT_DIVIDE ? Expression.ArithmeticOp.DIVIDE : op, left,
                right);
    }

    @Override
    String aggregateName(AggregateFunction function) {
        return switch (function) {
            case STRING_AGG -> "group_concat";
            case ANY_VALUE -> throw new IllegalArgumentException(name() + " has no any_value aggregate");
            default -> super.aggregateName(function);
        };
    }

    @Override
    public String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        if (function == AggregateFunction.STRING_AGG && distinct && !separator.equals("','")) {
            throw new IllegalArgumentException(name() + " has no separator for group_concat(DISTINCT ...)");
        }
        return super.aggregate(function, distinct, argument,
                function == AggregateFunction.STRING_AGG && distinct ? null : separator, orderKeys);
    }

    @Override
    public String setOperation(PlanNode.SetOperationKind kind, boolean all) {
        if (all) {
            throw new IllegalArgumentException(name() + " has no " + kind + " ALL");
        }
        return super.setOperation(kind, all);
    }

    @Override
    public String valuesAlias(String alias, int width) {
        return alias;
    }

    @Override
    public String valuesColumn(String alias, int index) {
        return alias + ".column" + (index + 1);
    }

    @Override
    boolean storesExactly(DataType type) {
        return type == Scalar.BOOLEAN || type == Scalar.TINYINT || type == Scalar.SMALLINT || type == Scalar.INTEGER
                || type == Scalar.BIGINT || type == Scalar.VARCHAR;
    }

    @Override
    boolean ordersExactly(DataType type) {
        return storesExactly(type);
    }

    @Override
    boolean sumsIntegers() {
        return false;
    }
}
