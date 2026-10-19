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

package net.fyrql;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The lines that {@link FyrQL#explain} prints for the kinds of nodes. */
class ExplainTest {

    @Test
    void anUnnestNamesItsListAndItsColumn() {
        try (FyrQL engine = FyrQL.builder().build()) {
            String sql = "SELECT id, x FROM (VALUES (1, [10, 20])) v(id, l), unnest(v.l) AS u(x)";
            assertEquals("""
                    Project #0 AS id, #2 AS x
                      Unnest #1 AS x
                        Values 1 row (col0 INTEGER, col1 INTEGER[])
                    """, engine.explain(sql));
            assertEquals(List.of(List.of(1, 10), List.of(1, 20)),
                    engine.rows(sql).stream().map(List::of).toList());
        }
    }

    @Test
    void aWindowShowsItsPartitionItsOrderAndItsCalls() {
        try (FyrQL engine = FyrQL.builder().build()) {
            Schema s = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("o", DataType.INTEGER),
                    new Schema.Field("x", DataType.BIGINT), new Schema.Field("f", DataType.BOOLEAN),
                    new Schema.Field("d", DataType.DATE));
            PlanNode values = new PlanNode.Values(s, List.of(new Object[] {1, 2, 3L, true, 0},
                    new Object[] {2, 3, 4L, false, 1}));
            PlanNode.Frame rows = new PlanNode.Frame(PlanNode.FrameUnit.ROWS,
                    PlanNode.FrameBound.of(PlanNode.BoundKind.PRECEDING, new Expression.Literal(DataType.BIGINT, 1L)),
                    PlanNode.FrameBound.of(PlanNode.BoundKind.CURRENT_ROW), PlanNode.FrameExclusion.TIES);
            PlanNode.Frame days = new PlanNode.Frame(PlanNode.FrameUnit.RANGE, new PlanNode.FrameBound(
                    PlanNode.BoundKind.PRECEDING, new Expression.Literal(DataType.BIGINT, 2L),
                    PlanNode.IntervalUnit.DAY), PlanNode.FrameBound.of(PlanNode.BoundKind.CURRENT_ROW),
                    PlanNode.FrameExclusion.NO_OTHERS);
            PlanNode first = new PlanNode.Window(values, List.of(0), List.of(PlanNode.SortKey.desc(1)), List.of(
                    PlanNode.WindowCall.of(PlanNode.WindowFunction.ROW_NUMBER, List.of(), "rn"),
                    new PlanNode.WindowCall(PlanNode.WindowFunction.AGGREGATE, new PlanNode.AggregateCall(
                            PlanNode.AggregateFunction.SUM, 2, false, "s"), List.of(), false, 3, rows, "s"),
                    new PlanNode.WindowCall(PlanNode.WindowFunction.LAG, null, List.of(2), true, -1,
                            PlanNode.Frame.DEFAULT, "prev")));
            PlanNode second = new PlanNode.Window(first, List.of(), List.of(PlanNode.SortKey.asc(4)), List.of(
                    PlanNode.WindowCall.of(new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1,
                            false, "n"), days),
                    PlanNode.WindowCall.of(new PlanNode.AggregateCall(PlanNode.AggregateFunction.MAX, 2, false,
                            "max(x)"), PlanNode.Frame.DEFAULT)));
            PlanNode plan = new PlanNode.Project(new PlanNode.Filter(second,
                    Expressions.gt(Expressions.col(0, DataType.INTEGER), Expressions.lit(1))), List.of(
                    new PlanNode.NamedExpression("k", Expressions.col(0, DataType.INTEGER)),
                    new PlanNode.NamedExpression("rn", Expressions.col(5, DataType.BIGINT)),
                    new PlanNode.NamedExpression("n", Expressions.col(8, DataType.BIGINT)),
                    new PlanNode.NamedExpression("m", Expressions.col(9, DataType.BIGINT))));
            assertEquals("""
                    Window PARTITION BY #0 ORDER BY #1 DESC: row_number() AS rn, sum(#2) FILTER (WHERE #3) \
                    ROWS BETWEEN 1 PRECEDING AND CURRENT ROW EXCLUDE TIES AS s, lag(#2 IGNORE NULLS) AS prev
                      Values 2 rows (k INTEGER, o INTEGER, x BIGINT, f BOOLEAN, d DATE)
                    """, engine.explain(first));
            // The filter stays above the window without partition columns, whose calls see all rows, and the calls
            // nothing reads are gone.
            assertEquals("""
                    Project #0 AS k, #5 AS rn, #6 AS n, #7 AS m
                      Filter (#0 > CAST(1 AS INTEGER))
                        Window ORDER BY #4: count(*) RANGE BETWEEN INTERVAL 2 DAY PRECEDING AND CURRENT ROW AS n, \
                    max(#2) AS "max(x)"
                          Window PARTITION BY #0 ORDER BY #1 DESC: row_number() AS rn
                            Values 2 rows (k INTEGER, o INTEGER, x BIGINT, f BOOLEAN, d DATE)
                    """, engine.explain(plan));
            // Two days back from the second date reach the first.
            assertEquals(List.of(List.of(2, 1L, 2L, 4L)), engine.rows(plan).stream().map(List::of).toList());
        }
    }

    @Test
    void constantsAreFoldedAndTheConditionAllBranchesShareBecomesTheJoinKey() {
        try (FyrQL engine = FyrQL.builder().build()) {
            String sql = """
                    SELECT count(*) FROM (VALUES (1, 'a'), (2, 'b')) p(k, brand), (VALUES (1, 3), (2, 25)) l(k, q)
                    WHERE (p.k = l.k AND p.brand = 'a' AND l.q <= 1 + 10)
                       OR (l.k = 7 - 5 AND p.k = l.k AND p.brand = 'b' AND l.q >= 2 * 10)""";
            assertEquals("""
                    Project #0 AS "count_star()"
                      Aggregate count(*) AS "count_star()"
                        Filter (((#1 = 'a') AND (#3 <= CAST(11 AS INTEGER))) OR (((#2 = CAST(2 AS INTEGER)) \
                    AND (#1 = 'b')) AND (#3 >= CAST(20 AS INTEGER))))
                          Join INNER ON l#0 = r#0
                            Values 2 rows (col0 INTEGER, col1 VARCHAR)
                            Values 2 rows (col0 INTEGER, col1 INTEGER)
                    """, engine.explain(sql));
            assertEquals(List.of(List.of(2L)), engine.rows(sql).stream().map(List::of).toList());
        }
    }

    @Test
    void aJoinShowsItsConditionAfterItsKeys() {
        try (FyrQL engine = FyrQL.builder().build()) {
            Schema p = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("brand", DataType.VARCHAR));
            Schema l = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("q", DataType.INTEGER));
            PlanNode plan = new PlanNode.Join(
                    new PlanNode.Values(p, List.of(new Object[] {1, "a"}, new Object[] {2, "b"})),
                    new PlanNode.Values(l, List.of(new Object[] {1, 0}, new Object[] {2, 25})),
                    List.of(0), List.of(0), PlanNode.JoinType.SEMI,
                    Expressions.lt(Expressions.col(0, DataType.INTEGER), Expressions.col(3, DataType.INTEGER)));
            assertEquals("""
                    Join SEMI ON l#0 = r#0 AND (l#0 < r#1)
                      Values 2 rows (k INTEGER, brand VARCHAR)
                      Values 2 rows (k INTEGER, q INTEGER)
                    """, engine.explain(plan));
            assertEquals(List.of(List.of(2, "b")), engine.rows(plan).stream().map(List::of).toList());
        }
    }
}
