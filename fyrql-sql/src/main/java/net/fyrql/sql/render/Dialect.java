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
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What differs between the SQL dialects the renderer writes: quoting, type
 * names, literals, the spelling of the forms that are not the same
 * everywhere, and which expressions the dialect evaluates exactly as FyrQL
 * does. The default of every spelling is the form of DuckDB, which is also
 * the form of FyrQL.
 * <p>
 * The spellings are one thing and {@link #supports} is another: a dialect
 * renders every expression it has a spelling for, but a connector may only
 * take over a filter or an order that the dialect {@linkplain #supports
 * supports}, because only then the source gives the rows FyrQL would
 * give. A spelling that is not exact, such as {@code LIKE} in a dialect
 * that ignores the case, is documented at the dialect.
 */
public interface Dialect {

    /** Returns the name of the dialect, e.g. {@code duckdb}. */
    String name();

    /** Quotes an identifier. */
    String quoteIdentifier(String name);

    /**
     * Returns the name of a type in the dialect.
     *
     * @throws IllegalArgumentException if the dialect has no such type
     */
    String typeName(DataType type);

    /**
     * Renders a value as a literal whose type is exactly the given one, also
     * for {@code NULL}.
     *
     * @throws IllegalArgumentException if the dialect cannot write the value
     */
    String literal(Object value, DataType type);

    /** Tells whether the dialect can express a join type. */
    boolean supportsJoin(PlanNode.JoinType type);

    /**
     * Returns the keyword of a join type, e.g. {@code LEFT JOIN} or
     * {@code SEMI JOIN}; not for a {@code SEMI} or {@code ANTI} join
     * unless {@link #hasSemiJoinKeywords} says so.
     */
    String joinKeyword(PlanNode.JoinType type);

    /**
     * Tells whether the dialect has keywords for {@code SEMI} and
     * {@code ANTI} joins; otherwise they are written as a filter with
     * {@code [NOT] EXISTS} and a subquery over the right input.
     */
    default boolean hasSemiJoinKeywords() {
        return false;
    }

    /** Renders the name of a scalar function as the dialect spells it. */
    String functionName(String name);

    /** How a dialect keeps the first rows of a flat scan. */
    enum LimitForm {
        /** With the clause {@link #limit} renders at the end of the statement. */
        CLAUSE,
        /** With {@code TOP n} after {@code SELECT}. */
        TOP,
        /** Not in the text: the statement carries the number of rows, which whoever runs it enforces. */
        MAX_ROWS
    }

    /** Returns how the dialect keeps the first rows of a flat scan; the default is a clause. */
    default LimitForm limitForm() {
        return LimitForm.CLAUSE;
    }

    /**
     * Tells whether the literals of a flat scan are bound as parameters
     * instead of written into the text; such a scan is rendered with
     * {@link SqlRenderer#renderScanBound}. The default is no.
     */
    default boolean bindsLiterals() {
        return false;
    }

    /**
     * Returns a condition that holds wherever a filter holds, which the
     * dialect evaluates and a connector may send to the source as a hint:
     * the source then returns at least the rows that satisfy the filter,
     * and the filter is evaluated again above it. The default is the filter
     * itself where the dialect {@linkplain #supports supports} it, and
     * nothing otherwise.
     */
    default Optional<Expression> hint(Expression filter) {
        return supports(filter) ? Optional.of(filter) : Optional.empty();
    }

    /** Renders the clause that keeps {@code count} rows after {@code offset}, e.g. {@code LIMIT 10 OFFSET 5}. */
    default String limit(long count, long offset) {
        return "LIMIT " + count + (offset > 0 ? " OFFSET " + offset : "");
    }

    /** Renders one key of an {@code ORDER BY} over a rendered column, with the direction and the place of {@code NULL}s. */
    default String orderKey(String column, boolean ascending, boolean nullsFirst) {
        return column + (ascending ? " ASC" : " DESC") + (nullsFirst ? " NULLS FIRST" : " NULLS LAST");
    }

    /**
     * Renders a cast of a rendered operand; a safe cast gives {@code NULL}
     * where the value does not convert.
     *
     * @throws IllegalArgumentException if the dialect has no safe cast
     */
    default String cast(String operand, DataType target, boolean safe) {
        return (safe ? "TRY_CAST(" : "CAST(") + operand + " AS " + typeName(target) + ")";
    }

    /** Renders a pattern match over rendered operands, with or without regard to the case. */
    default String like(String string, String pattern, boolean caseInsensitive) {
        return "(" + string + (caseInsensitive ? " ILIKE " : " LIKE ") + pattern + ")";
    }

    /** Renders the null-safe comparison of rendered operands, {@code IS [NOT] DISTINCT FROM}. */
    default String isDistinct(String left, String right, boolean negated) {
        return "(" + left + (negated ? " IS NOT DISTINCT FROM " : " IS DISTINCT FROM ") + right + ")";
    }

    /** Renders an arithmetic operation over rendered operands. */
    default String arithmetic(Expression.ArithmeticOp op, String left, String right) {
        return "(" + left + " " + op.symbol() + " " + right + ")";
    }

    /** Renders the concatenation of two rendered strings. */
    default String concat(String left, String right) {
        return "(" + left + " || " + right + ")";
    }

    /** Renders {@code coalesce} over rendered operands. */
    default String coalesce(List<String> operands) {
        return "coalesce(" + String.join(", ", operands) + ")";
    }

    /**
     * Renders an aggregate call.
     *
     * @param function the function
     * @param distinct whether only distinct values count
     * @param argument the rendered argument, {@code *} for the count of rows
     * @param separator the rendered separator literal of {@code string_agg}, else {@code null}
     * @param orderKeys the rendered keys of the order of the values, empty if none
     * @throws IllegalArgumentException if the dialect has no such aggregate
     */
    default String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        StringBuilder b = new StringBuilder(function.sqlName()).append('(').append(distinct ? "DISTINCT " : "")
                .append(argument);
        if (separator != null) {
            b.append(", ").append(separator);
        }
        if (!orderKeys.isEmpty()) {
            b.append(" ORDER BY ").append(String.join(", ", orderKeys));
        }
        return b.append(')').toString();
    }

    /**
     * Renders a call of a window function other than an aggregate over
     * rendered arguments, e.g. {@code lag(t.c1, t.c2 IGNORE NULLS)}.
     *
     * @throws IllegalArgumentException if the dialect has no such function
     */
    default String windowFunction(PlanNode.WindowFunction function, List<String> arguments, boolean ignoreNulls) {
        return function.sqlName() + "(" + String.join(", ", arguments) + (ignoreNulls ? " IGNORE NULLS)" : ")");
    }

    /**
     * Renders the frame of a window, e.g.
     * {@code ROWS BETWEEN 1 PRECEDING AND CURRENT ROW}, with an offset as a
     * literal of its type and an interval as {@code INTERVAL '3' DAY}.
     *
     * @throws IllegalArgumentException if the dialect cannot write the frame
     */
    default String frame(PlanNode.Frame frame) {
        return frame.sql(b -> b.unit() == null ? literal(b.offset().value(), b.offset().type())
                : "INTERVAL '" + b.offset().value() + "' " + b.unit());
    }

    /**
     * Renders a rendered call of a window function or an aggregate over a
     * window: {@code call [FILTER (WHERE filter)] OVER ([PARTITION BY ...]
     * [ORDER BY ...] [frame])}.
     *
     * @param call the rendered call
     * @param filter the rendered condition of the rows an aggregate takes, or {@code null} for all
     * @param partition the rendered columns of the partitions
     * @param orderKeys the rendered keys of the order within a partition
     * @param frame the rendered frame, or {@code null} for a function that uses none
     * @throws IllegalArgumentException if the dialect cannot write window functions
     */
    default String window(String call, String filter, List<String> partition, List<String> orderKeys, String frame) {
        List<String> parts = new ArrayList<>();
        if (!partition.isEmpty()) {
            parts.add("PARTITION BY " + String.join(", ", partition));
        }
        if (!orderKeys.isEmpty()) {
            parts.add("ORDER BY " + String.join(", ", orderKeys));
        }
        if (frame != null) {
            parts.add(frame);
        }
        return call + (filter == null ? "" : " FILTER (WHERE " + filter + ")") + " OVER (" + String.join(" ", parts)
                + ")";
    }

    /**
     * Renders the keyword of a set operation, e.g. {@code EXCEPT ALL}.
     *
     * @throws IllegalArgumentException if the dialect has no such operation
     */
    default String setOperation(PlanNode.SetOperationKind kind, boolean all) {
        return kind + (all ? " ALL" : "");
    }

    /**
     * Renders the call in a select list that gives one row per element of
     * a rendered list, and none for an empty or {@code NULL} list.
     */
    default String unnest(String list) {
        return "unnest(" + list + ")";
    }

    /** Renders one row of a {@code VALUES} list from rendered literals. */
    default String valuesRow(List<String> literals) {
        return "(" + String.join(", ", literals) + ")";
    }

    /** Renders the alias of a {@code VALUES} list with the given number of columns, e.g. {@code t0(c0, c1)}. */
    default String valuesAlias(String alias, int width) {
        StringBuilder b = new StringBuilder(alias).append('(');
        for (int i = 0; i < width; i++) {
            b.append(i > 0 ? ", " : "").append(SqlRenderer.column(i));
        }
        return b.append(')').toString();
    }

    /** Renders the reference to a column of a {@code VALUES} list under its alias. */
    default String valuesColumn(String alias, int index) {
        return alias + "." + SqlRenderer.column(index);
    }

    /**
     * Tells whether the dialect evaluates an expression exactly as FyrQL
     * does, so that a connector may hand it over as a filter. The default
     * is yes for everything; the other dialects are deliberately
     * conservative.
     */
    default boolean supports(Expression expression) {
        return true;
    }

    /**
     * Tells whether the dialect orders rows by the keys exactly as FyrQL
     * does, so that a connector may hand the order over.
     *
     * @param keys the keys, over the columns of the schema
     * @param schema the schema of the rows
     */
    default boolean supportsOrder(List<SortKey> keys, Schema schema) {
        return true;
    }

    /**
     * Tells whether the dialect groups and aggregates exactly as FyrQL
     * does, as {@link SqlRenderer#renderScan} writes the aggregation, so
     * that a connector may hand it over.
     *
     * @param aggregation the groups and calls, over the columns of the schema
     * @param schema the schema of the rows
     */
    default boolean supportsAggregation(PlanNode.Aggregation aggregation, Schema schema) {
        return true;
    }

    /**
     * Tells whether the dialect computes the calls of a window exactly as
     * FyrQL does, so that a connector may hand them over.
     */
    default boolean supportsWindow(PlanNode.Window window) {
        return true;
    }
}
