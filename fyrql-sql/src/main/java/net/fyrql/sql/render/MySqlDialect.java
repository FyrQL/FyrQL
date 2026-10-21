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

import java.util.List;

/**
 * The dialect of MySQL 8. Identifiers are in backticks; a string literal
 * doubles its quotes and its backslashes. {@code HUGEINT} is
 * {@code DECIMAL(39,0)}, {@code VARCHAR} is {@code TEXT}, {@code TIMESTAMP}
 * is {@code DATETIME(6)}, {@code TIMESTAMP WITH TIME ZONE} a {@code DATETIME(6)}
 * in UTC, {@code INTERVAL} is {@code VARCHAR(64)}, {@code UUID} is
 * {@code CHAR(36)}; integers are
 * written plain, since a cast knows only {@code SIGNED}; {@code NaN} and
 * the infinities have no literal. There is no {@code FULL JOIN} and no
 * {@code ILIKE}, which is written as {@code LIKE} over {@code lower}; the
 * null-safe comparison is {@code <=>}, integer division is {@code DIV},
 * concatenation is {@code CONCAT}, {@code string_agg} is
 * {@code GROUP_CONCAT}, and the place of {@code NULL}s in an order is
 * written as a key {@code (c IS NULL)} in front of the column. A
 * {@code VALUES} list writes its rows as {@code ROW(...)}.
 * <p>
 * MySQL compares strings by a collation that ignores the case, so a
 * source evaluates no comparison of strings at all.
 */
public final class MySqlDialect extends StandardDialect {

    public static final MySqlDialect INSTANCE = new MySqlDialect();

    private MySqlDialect() {
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String quoteIdentifier(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    @Override
    String string(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    @Override
    String scalarTypeName(Scalar type) {
        return switch (type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INT";
            case BIGINT -> "BIGINT";
            case HUGEINT -> "DECIMAL(39,0)";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case VARCHAR -> "TEXT";
            case BLOB -> "BLOB";
            case DATE -> "DATE";
            case TIMESTAMP -> "DATETIME(6)";
            case UUID -> "CHAR(36)";
            case TIME -> "TIME(6)";
            case TIMESTAMPTZ -> "DATETIME(6)";
            case INTERVAL -> "VARCHAR(64)";
        };
    }

    /** The name of a type in a {@code CAST}, which MySQL spells differently from the type of a column. */
    private String castTypeName(DataType type) {
        if (type instanceof Decimal) {
            return typeName(type);
        }
        return switch ((Scalar) type) {
            case BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT -> "SIGNED";
            case HUGEINT -> "DECIMAL(39,0)";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case VARCHAR, UUID, INTERVAL -> "CHAR";
            case BLOB -> "BINARY";
            case DATE -> "DATE";
            case TIMESTAMP, TIMESTAMPTZ -> "DATETIME(6)";
            case TIME -> "TIME(6)";
        };
    }

    @Override
    public String typeName(DataType type) {
        if (type instanceof Decimal d && d.precision() > 65) {
            throw new IllegalArgumentException(name() + " has no type for " + type.sqlName());
        }
        return super.typeName(type);
    }

    @Override
    String nullLiteral(DataType type) {
        return "NULL";
    }

    @Override
    String integerLiteral(Object value, Scalar type) {
        return value.toString();
    }

    @Override
    String hugeIntLiteral(Object value) {
        return "CAST(" + value + " AS DECIMAL(39,0))";
    }

    @Override
    String uuidLiteral(java.util.UUID value) {
        return string(value.toString());
    }

    /** An instant as the {@code DATETIME} in UTC that stores it. */
    @Override
    String timestampTzLiteral(long micros) {
        return timestampLiteral(micros);
    }

    @Override
    String intervalLiteral(net.fyrql.core.value.Interval value) {
        return string(value.toString());
    }

    @Override
    public boolean supportsJoin(PlanNode.JoinType type) {
        return type != PlanNode.JoinType.FULL && super.supportsJoin(type);
    }

    @Override
    public String orderKey(String column, boolean ascending, boolean nullsFirst) {
        return "(" + column + " IS NULL) " + (nullsFirst ? "DESC" : "ASC") + ", " + column
                + (ascending ? " ASC" : " DESC");
    }

    @Override
    public String cast(String operand, DataType target, boolean safe) {
        if (safe) {
            throw new IllegalArgumentException(name() + " has no safe cast");
        }
        return "CAST(" + operand + " AS " + castTypeName(target) + ")";
    }

    @Override
    public String like(String string, String pattern, boolean caseInsensitive) {
        return caseInsensitive ? "(lower(" + string + ") LIKE lower(" + pattern + "))"
                : "(" + string + " LIKE " + pattern + ")";
    }

    @Override
    public String isDistinct(String left, String right, boolean negated) {
        return negated ? "(" + left + " <=> " + right + ")" : "(NOT (" + left + " <=> " + right + "))";
    }

    @Override
    public String arithmetic(Expression.ArithmeticOp op, String left, String right) {
        return op == Expression.ArithmeticOp.INT_DIVIDE ? "(" + left + " DIV " + right + ")"
                : super.arithmetic(op, left, right);
    }

    @Override
    public String concat(String left, String right) {
        return "CONCAT(" + left + ", " + right + ")";
    }

    @Override
    public String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        if (function != AggregateFunction.STRING_AGG) {
            return super.aggregate(function, distinct, argument, separator, orderKeys);
        }
        return "GROUP_CONCAT(" + (distinct ? "DISTINCT " : "") + argument
                + (orderKeys.isEmpty() ? "" : " ORDER BY " + String.join(", ", orderKeys)) + " SEPARATOR " + separator
                + ")";
    }

    @Override
    public String valuesRow(List<String> literals) {
        return "ROW(" + String.join(", ", literals) + ")";
    }

    @Override
    boolean storesExactly(DataType type) {
        return exactNumber(type) || type == Scalar.VARCHAR || type == Scalar.DATE || type == Scalar.TIMESTAMP
                || type == Scalar.TIME;
    }

    @Override
    boolean equatesExactly(DataType type) {
        return storesExactly(type) && type != Scalar.VARCHAR;
    }
}
