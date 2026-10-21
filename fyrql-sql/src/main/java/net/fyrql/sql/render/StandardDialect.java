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
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the dialects of the SQL databases have in common: identifiers in
 * double quotes, strings in single quotes with the quote doubled, typed
 * literals through {@code CAST}, no nested types, no safe cast, no semi or
 * anti join, and a conservative answer to {@link #supports}: only columns,
 * literals, comparisons, {@code AND}, {@code OR}, {@code NOT},
 * {@code IS NULL}, {@code BETWEEN}, {@code IN} over literals and
 * {@code IS DISTINCT FROM}, over the types the dialect stores and compares
 * as FyrQL does. Strings are compared with {@code =} only where the
 * dialect compares them byte by byte, and ordered only where it orders
 * them byte by byte; floating-point values are never compared in the
 * source, since their formatting and rounding differ. An aggregation is
 * just as conservative, see {@link #supportsAggregation}; a window function
 * is not rendered at all.
 */
abstract class StandardDialect implements Dialect {

    @Override
    public String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** A string literal; the quote is doubled. */
    String string(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** The name of a scalar type. */
    abstract String scalarTypeName(Scalar type);

    /** The name of a decimal type. */
    String decimalTypeName(int precision, int scale) {
        return "DECIMAL(" + precision + "," + scale + ")";
    }

    @Override
    public String typeName(DataType type) {
        if (type instanceof Scalar s) {
            return scalarTypeName(s);
        }
        if (type instanceof Decimal d) {
            return decimalTypeName(d.precision(), d.scale());
        }
        throw new IllegalArgumentException(name() + " has no type for " + type.sqlName());
    }

    /** The {@code NULL} of a type. */
    String nullLiteral(DataType type) {
        return "CAST(NULL AS " + typeName(type) + ")";
    }

    String booleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    /** An integer literal of a type up to {@code BIGINT}. */
    String integerLiteral(Object value, Scalar type) {
        return "CAST(" + value + " AS " + typeName(type) + ")";
    }

    String hugeIntLiteral(Object value) {
        return "CAST(" + value + " AS " + typeName(Scalar.HUGEINT) + ")";
    }

    String decimalLiteral(BigDecimal value, Decimal type) {
        return "CAST(" + string(value.toPlainString()) + " AS " + typeName(type) + ")";
    }

    /** A finite {@code FLOAT} or {@code DOUBLE}. */
    String floatingLiteral(String digits, Scalar type) {
        return "CAST(" + string(digits) + " AS " + typeName(type) + ")";
    }

    /** {@code NaN} or an infinity; the digits are {@code nan}, {@code inf} or {@code -inf}. */
    String nonFiniteLiteral(String digits, Scalar type) {
        throw new IllegalArgumentException(name() + " cannot write " + digits + " as " + typeName(type));
    }

    String blobLiteral(byte[] value) {
        StringBuilder b = new StringBuilder("X'");
        for (byte x : value) {
            b.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(x & 0xF, 16)));
        }
        return b.append('\'').toString();
    }

    String dateLiteral(int days) {
        return "DATE " + string(Conversions.formatDate(days));
    }

    String timestampLiteral(long micros) {
        return "TIMESTAMP " + string(Conversions.formatTimestamp(micros));
    }

    String uuidLiteral(java.util.UUID value) {
        return "CAST(" + string(value.toString()) + " AS " + typeName(Scalar.UUID) + ")";
    }

    String timeLiteral(long micros) {
        return "TIME " + string(Conversions.formatTime(micros));
    }

    /** An instant, written in UTC with its offset, so that the time zone of the database plays no part. */
    String timestampTzLiteral(long micros) {
        return "TIMESTAMP WITH TIME ZONE " + string(Conversions.formatTimestamp(micros) + "+00:00");
    }

    String intervalLiteral(net.fyrql.core.value.Interval value) {
        throw new IllegalArgumentException(name() + " cannot write an INTERVAL");
    }

    @Override
    public String literal(Object value, DataType type) {
        if (value == null) {
            return nullLiteral(type);
        }
        if (type instanceof Decimal d) {
            return decimalLiteral((BigDecimal) value, d);
        }
        if (!(type instanceof Scalar s)) {
            throw new IllegalArgumentException(name() + " cannot write a value of type " + type.sqlName());
        }
        return switch (s) {
            case BOOLEAN -> booleanLiteral((Boolean) value);
            case TINYINT, SMALLINT, INTEGER, BIGINT -> integerLiteral(value, s);
            case HUGEINT -> hugeIntLiteral(value);
            case FLOAT -> floating(Conversions.formatFloat((Float) value), Float.isFinite((Float) value), s);
            case DOUBLE -> floating(Conversions.formatDouble((Double) value), Double.isFinite((Double) value), s);
            case VARCHAR -> string((String) value);
            case BLOB -> blobLiteral((byte[]) value);
            case DATE -> dateLiteral((Integer) value);
            case TIMESTAMP -> timestampLiteral((Long) value);
            case UUID -> uuidLiteral((java.util.UUID) value);
            case TIME -> timeLiteral((Long) value);
            case TIMESTAMPTZ -> timestampTzLiteral((Long) value);
            case INTERVAL -> intervalLiteral((net.fyrql.core.value.Interval) value);
        };
    }

    private String floating(String digits, boolean finite, Scalar type) {
        return finite ? floatingLiteral(digits, type) : nonFiniteLiteral(digits, type);
    }

    @Override
    public boolean supportsJoin(PlanNode.JoinType type) {
        return switch (type) {
            case INNER, LEFT, RIGHT, FULL, SEMI, ANTI, CROSS -> true;
            case MARK, SINGLE -> false;
        };
    }

    @Override
    public String joinKeyword(PlanNode.JoinType type) {
        if (!supportsJoin(type)) {
            throw new IllegalArgumentException(name() + " has no " + type + " join");
        }
        return switch (type) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
            case CROSS -> "CROSS JOIN";
            case SEMI, ANTI -> throw new IllegalArgumentException(name() + " writes a " + type + " join with EXISTS");
            case MARK, SINGLE -> throw new IllegalStateException();
        };
    }

    @Override
    public String functionName(String name) {
        return name;
    }

    @Override
    public String cast(String operand, DataType target, boolean safe) {
        if (safe) {
            throw new IllegalArgumentException(name() + " has no safe cast");
        }
        return "CAST(" + operand + " AS " + typeName(target) + ")";
    }

    /**
     * The name of an aggregate function, with the spelling of
     * {@code string_agg} left to {@link #aggregate}; a list, first, last
     * or boolean aggregate has no exact counterpart in the dialects.
     */
    String aggregateName(AggregateFunction function) {
        return switch (function) {
            case COUNT_STAR, COUNT, SUM, AVG, MIN, MAX, STRING_AGG -> function.sqlName();
            case ANY_VALUE -> "any_value";
            case LIST, FIRST, LAST, BOOL_AND, BOOL_OR -> throw new IllegalArgumentException(
                    name() + " has no " + function.sqlName() + " aggregate");
        };
    }

    @Override
    public String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        String name = aggregateName(function);
        StringBuilder b = new StringBuilder(name).append('(').append(distinct ? "DISTINCT " : "").append(argument);
        if (separator != null) {
            b.append(", ").append(separator);
        }
        if (!orderKeys.isEmpty()) {
            b.append(" ORDER BY ").append(String.join(", ", orderKeys));
        }
        return b.append(')').toString();
    }

    /**
     * Refuses: the databases differ from DuckDB and from each other in the
     * frames, {@code IGNORE NULLS}, {@code FILTER} and the place of
     * {@code NULL}s, and FyrQL computes windows itself.
     */
    @Override
    public String window(String call, String filter, List<String> partition, List<String> orderKeys, String frame) {
        throw new IllegalArgumentException(name() + " cannot render a window function");
    }

    /** No: FyrQL computes windows itself. */
    @Override
    public boolean supportsWindow(PlanNode.Window window) {
        return false;
    }

    /** Tells whether a column or a literal of the type may appear in a filter the source evaluates. */
    abstract boolean storesExactly(DataType type);

    /** Tells whether the dialect compares values of the type for equality as FyrQL does. */
    boolean equatesExactly(DataType type) {
        return storesExactly(type);
    }

    /** Tells whether the dialect orders values of the type as FyrQL does. */
    boolean ordersExactly(DataType type) {
        return storesExactly(type) && type != Scalar.VARCHAR;
    }

    /** Tells whether a type is an integer up to {@code BIGINT}, {@code BOOLEAN} or {@code DECIMAL}. */
    static boolean exactNumber(DataType type) {
        return type instanceof Decimal || type == Scalar.BOOLEAN || type == Scalar.TINYINT || type == Scalar.SMALLINT
                || type == Scalar.INTEGER || type == Scalar.BIGINT;
    }

    @Override
    public boolean supports(Expression expression) {
        return Expressions.allMatch(expression, this::supportsNode);
    }

    private boolean supportsNode(Expression e) {
        return switch (e) {
            case Expression.Column c -> storesExactly(c.type());
            case Expression.Literal l -> storesExactly(l.type());
            case Expression.Comparison c -> switch (c.op()) {
                case EQ, NE -> equatesExactly(c.left().type());
                case LT, LE, GT, GE -> ordersExactly(c.left().type());
            };
            case Expression.Between b -> ordersExactly(b.value().type());
            case Expression.In in -> equatesExactly(in.value().type())
                    && in.list().stream().allMatch(x -> x instanceof Expression.Literal);
            case Expression.IsDistinct d -> equatesExactly(d.left().type());
            case Expression.And a -> true;
            case Expression.Or o -> true;
            case Expression.Not n -> true;
            case Expression.IsNull n -> true;
            default -> false;
        };
    }

    @Override
    public boolean supportsOrder(List<SortKey> keys, Schema schema) {
        for (SortKey k : keys) {
            if (!ordersExactly(schema.type(k.column()))) {
                return false;
            }
        }
        return true;
    }

    /** Tells whether the dialect sums integers exactly once the renderer casts the sum to {@code HUGEINT}. */
    boolean sumsIntegers() {
        return true;
    }

    /** Tells whether a type is an integer from {@code TINYINT} to {@code BIGINT}. */
    static boolean integer(DataType type) {
        return type == Scalar.TINYINT || type == Scalar.SMALLINT || type == Scalar.INTEGER || type == Scalar.BIGINT;
    }

    /**
     * Groups by columns the dialect compares for equality as FyrQL does,
     * and computes {@code count(*)} and {@code count} of any column,
     * {@code min} and {@code max} of integers, decimals, dates and
     * timestamps the dialect orders as FyrQL does, and {@code sum} of
     * integers where the dialect {@linkplain #sumsIntegers sums them};
     * nothing {@code DISTINCT}, and not an aggregation without groups and
     * calls, which has no flat statement.
     */
    @Override
    public boolean supportsAggregation(PlanNode.Aggregation aggregation, Schema schema) {
        if (aggregation.groupColumns().isEmpty() && aggregation.calls().isEmpty()) {
            return false;
        }
        for (int g : aggregation.groupColumns()) {
            if (!equatesExactly(schema.type(g))) {
                return false;
            }
        }
        for (PlanNode.AggregateCall call : aggregation.calls()) {
            if (call.distinct() || !call.orderBy().isEmpty() || !aggregatesExactly(call, schema)) {
                return false;
            }
        }
        return true;
    }

    private boolean aggregatesExactly(PlanNode.AggregateCall call, Schema schema) {
        return switch (call.function()) {
            case COUNT_STAR, COUNT -> true;
            case MIN, MAX -> {
                DataType t = schema.type(call.column());
                yield (integer(t) || t instanceof Decimal || t == Scalar.DATE || t == Scalar.TIMESTAMP
                        || t == Scalar.TIME || t == Scalar.TIMESTAMPTZ) && ordersExactly(t);
            }
            case SUM -> sumsIntegers() && integer(schema.type(call.column()));
            default -> false;
        };
    }
}
