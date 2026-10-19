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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Pushed;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.coalesce;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.div;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.iff;
import static net.fyrql.core.expr.Expressions.ilike;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.intDiv;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNotDistinct;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.neg;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.rem;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.when;

/**
 * A plan with every node and every expression form the renderer knows, over
 * tables of every scalar type but {@code HUGEINT}, for the golden texts of
 * the dialects; not every dialect has a type for a 128-bit integer.
 */
final class FullPlan {

    private FullPlan() {
    }

    static final Schema ORDERS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("amount", DataType.decimal(10, 2)), new Schema.Field("note", DataType.VARCHAR),
            new Schema.Field("paid", DataType.BOOLEAN), new Schema.Field("day", DataType.DATE),
            new Schema.Field("at", DataType.TIMESTAMP), new Schema.Field("weight", DataType.DOUBLE),
            new Schema.Field("tiny", DataType.TINYINT), new Schema.Field("small", DataType.SMALLINT),
            new Schema.Field("big", DataType.BIGINT), new Schema.Field("ratio", DataType.FLOAT),
            new Schema.Field("raw", DataType.BLOB), new Schema.Field("key", DataType.UUID));

    static final Schema CUSTOMERS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("name", DataType.VARCHAR));

    static final TableRef ORDERS_REF = TableRef.of("db", "orders");
    static final TableRef CUSTOMERS_REF = TableRef.of("db", "customers");

    /** The names the tests use for the tables: {@code source_schema_table}. */
    static String tableName(TableRef t) {
        return t.source() + "_" + t.schema() + "_" + t.table();
    }

    /** The conjunction of conditions, nested to the right. */
    private static Expression all(Expression... conditions) {
        Expression result = conditions[conditions.length - 1];
        for (int i = conditions.length - 2; i >= 0; i--) {
            result = and(conditions[i], result);
        }
        return result;
    }

    /** A filter over the orders with every expression form and a literal of every scalar type. */
    static Expression condition() {
        Schema o = ORDERS;
        Expression arithmetic = all(
                gt(add(col(o, 0), lit(1)), sub(col(o, 0), lit(2))),
                lt(mul(col(o, 0), lit(3)), div(col(o, 0), lit(4))),
                ne(intDiv(col(o, 0), lit(5)), rem(col(o, 0), lit(6))),
                ge(neg(col(o, 0)), lit(-7)));
        Expression literals = all(
                eq(col(o, 1), lit(new BigDecimal("1.50"))),
                eq(col(o, 3), lit(true)),
                eq(col(o, 4), lit(DataType.DATE, 1)),
                eq(col(o, 5), lit(DataType.TIMESTAMP, 86_400_000_001L)),
                ne(col(o, 6), lit(2.5)),
                ne(col(o, 7), lit((byte) 1)),
                ne(col(o, 8), lit((short) 2)),
                ne(col(o, 9), lit(3L)),
                ne(col(o, 10), lit(1.5f)),
                ne(col(o, 11), lit(DataType.BLOB, new byte[] {1, 'a', (byte) 0xFF})),
                ne(col(o, 12), lit(DataType.UUID, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))));
        Expression forms = all(
                or(not(isNull(col(o, 2))), isNotNull(col(o, 2))),
                between(col(o, 0), lit(1), lit(9)),
                in(col(o, 0), lit(1), lit(2), lit(3)),
                eq(caseWhen(List.of(when(eq(col(o, 0), lit(1)), lit("one")), when(eq(col(o, 0), lit(2)), lit("two"))),
                        lit("many")), lit("one")),
                eq(iff(col(o, 3), lit(1), lit(0)), lit(1)),
                eq(coalesce(col(o, 2), lit("none"), lit("")), lit("it's")),
                eq(cast(col(o, 0), DataType.VARCHAR), lit("1")),
                like(col(o, 2), lit("a%")),
                ilike(col(o, 2), lit("b_")),
                isDistinct(col(o, 0), lit(1)),
                isNotDistinct(col(o, 2), lit(DataType.VARCHAR, null)),
                eq(call("||", col(o, 2), lit("!")), lit("x!")),
                eq(call("upper", col(o, 2)), lit("A")));
        return all(arithmetic, literals, forms);
    }

    /** The plan: every node once, a pushed scan, and a set operation over values. */
    static PlanNode plan() {
        PlanNode.Scan orders = PlanNode.Scan.all(ORDERS_REF, ORDERS);
        PlanNode.Scan customers = new PlanNode.Scan(CUSTOMERS_REF, CUSTOMERS, List.of(1, 0), new Pushed(
                List.of(gt(col(1, DataType.INTEGER), lit(5))), OptionalLong.of(100), List.of(SortKey.asc(0)),
                Optional.empty()));
        PlanNode filtered = new PlanNode.Filter(orders, condition());
        PlanNode join = new PlanNode.Join(filtered, customers, List.of(0), List.of(1), JoinType.LEFT);
        PlanNode projected = new PlanNode.Project(join, List.of(
                new NamedExpression("name", col(join.schema(), 13)),
                new NamedExpression("amount", col(join.schema(), 1)),
                new NamedExpression("note", col(join.schema(), 2)),
                new NamedExpression("id", col(join.schema(), 0))));
        PlanNode aggregated = new PlanNode.Aggregate(projected, List.of(0), List.of(
                new AggregateCall(AggregateFunction.SUM, 1, false, "total"),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.COUNT, 2, true, "notes"),
                new AggregateCall(AggregateFunction.STRING_AGG, 2, false, "all_notes", ", ",
                        List.of(SortKey.desc(3))),
                new AggregateCall(AggregateFunction.MIN, 3, false, "first"),
                new AggregateCall(AggregateFunction.MAX, 3, false, "last"),
                new AggregateCall(AggregateFunction.AVG, 1, false, "mean")));
        PlanNode sorted = new PlanNode.Sort(aggregated,
                List.of(new SortKey(1, false, true), new SortKey(0, true, false)));
        PlanNode limited = new PlanNode.Limit(sorted, 10, 5);
        Schema out = limited.schema();
        PlanNode values = new PlanNode.Values(out, List.of(
                new Object[] {"x", new BigDecimal("1.00"), 1L, 1L, "a, b", 1, 1, 1.0},
                new Object[] {null, null, null, null, null, null, null, null}));
        PlanNode union = new PlanNode.Union(List.of(limited, values));
        PlanNode except = new PlanNode.SetOperation(union, values, PlanNode.SetOperationKind.EXCEPT, false);
        return new PlanNode.SetOperation(except, new PlanNode.Values(out, List.of()),
                PlanNode.SetOperationKind.INTERSECT, false);
    }
}
