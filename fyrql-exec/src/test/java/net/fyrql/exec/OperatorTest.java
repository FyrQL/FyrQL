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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.JoinTable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.ne;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTest {

    private static final Schema ORDERS = Schema.of(new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("customer", DataType.INTEGER), new Schema.Field("amount", DataType.decimal(10, 2)));
    private static final Schema CUSTOMERS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("name", DataType.VARCHAR));

    private final Map<TableRef, List<Batch>> tables = new HashMap<>();

    private Executor executor() {
        return new Executor(scan -> {
            List<Batch> batches = tables.get(scan.table());
            List<Batch> projected = new ArrayList<>();
            for (Batch b : batches) {
                projected.add(b.project(scan.columns()));
            }
            return Batches.reader(projected);
        }, MemoryBudget.unlimited(), 3);
    }

    private PlanNode.Scan table(String name, Schema schema, List<Object[]> rows, int batchSize) {
        TableRef ref = TableRef.of("t", name);
        tables.put(ref, Batches.split(Batch.ofRows(schema, rows), batchSize));
        return PlanNode.Scan.all(ref, schema);
    }

    private PlanNode.Scan orders() {
        return table("orders", ORDERS, List.of(
                row(1, 10, "5.00"), row(2, 20, "7.50"), row(3, 10, null), row(4, null, "1.25"), row(5, 30, "2.00")),
                2);
    }

    private PlanNode.Scan customers() {
        return table("customers", CUSTOMERS, List.of(
                new Object[] {10, "ten"}, new Object[] {20, "twenty"}, new Object[] {null, "none"},
                new Object[] {40, "forty"}, new Object[] {10, "ten again"}), 2);
    }

    private static Object[] row(int id, Integer customer, String amount) {
        return new Object[] {id, customer, amount == null ? null : new BigDecimal(amount)};
    }

    private static List<List<Object>> rows(List<Object[]> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Arrays.asList(r));
        }
        return result;
    }

    @Test
    void filterProjectLimit() {
        PlanNode plan = new PlanNode.Limit(new PlanNode.Project(
                new PlanNode.Filter(orders(), gt(col(ORDERS, 1), lit(10))),
                List.of(new NamedExpression("id2", add(col(ORDERS, 0), lit(100))))), 1, 1);
        assertEquals(List.of(List.of(105)), rows(executor().rows(plan)));
        assertEquals(DataType.INTEGER, plan.schema().type(0));
    }

    @Test
    void joinTypes() {
        PlanNode.Scan o = orders();
        PlanNode.Scan c = customers();
        Executor x = executor();
        PlanNode inner = new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.INNER);
        assertEquals(List.of(
                List.of(1, 10, new BigDecimal("5.00"), 10, "ten"),
                List.of(1, 10, new BigDecimal("5.00"), 10, "ten again"),
                List.of(2, 20, new BigDecimal("7.50"), 20, "twenty"),
                Arrays.asList(3, 10, null, 10, "ten"),
                Arrays.asList(3, 10, null, 10, "ten again")), rows(x.rows(inner)));
        PlanNode left = new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.LEFT);
        assertEquals(7, x.rows(left).size());
        assertTrue(rows(x.rows(left)).contains(Arrays.asList(4, null, new BigDecimal("1.25"), null, null)));
        PlanNode right = new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.RIGHT);
        List<List<Object>> rightRows = rows(x.rows(right));
        assertEquals(7, rightRows.size());
        assertTrue(rightRows.contains(Arrays.asList(null, null, null, null, "none")));
        assertTrue(rightRows.contains(Arrays.asList(null, null, null, 40, "forty")));
        assertEquals(9, x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.FULL)).size());
        assertEquals(List.of(1, 2, 3), rows(x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.SEMI)))
                .stream().map(r -> r.get(0)).toList());
        assertEquals(List.of(4, 5), rows(x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.ANTI)))
                .stream().map(r -> r.get(0)).toList());
        assertEquals(25, x.rows(new PlanNode.Join(o, c, List.of(), List.of(), JoinType.CROSS)).size());
        assertEquals(2, new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.SEMI).schema().size() - 1);
    }

    @Test
    void aJoinWithoutKeysProbesInStepsOfBoundedPairs() {
        Schema p = Schema.of(new Schema.Field("v", DataType.INTEGER));
        Schema b = Schema.of(new Schema.Field("w", DataType.INTEGER));
        List<Object[]> probeRows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            probeRows.add(new Object[] {i});
        }
        List<Object[]> buildRows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            buildRows.add(new Object[] {i});
        }
        // One probe batch of 3,000 rows against 1,000 build rows: three million pairs.
        PlanNode.Scan probe = table("p", p, probeRows, 3000);
        PlanNode.Scan build = table("b", b, buildRows, 3000);
        Executor x = executor();
        long rows = 0;
        int largest = 0;
        try (Operator op = x.open(new PlanNode.Join(probe, build, List.of(), List.of(), JoinType.CROSS))) {
            for (Batch batch = op.next(); batch != null; batch = op.next()) {
                rows += batch.rowCount();
                largest = Math.max(largest, batch.rowCount());
            }
        }
        assertEquals(3_000_000L, rows);
        assertTrue(largest <= JoinTable.MAX_PAIRS, largest + " rows in a batch");

        Schema pair = p.concat(b);
        List<Object[]> single = x.rows(new PlanNode.Join(probe, build, List.of(), List.of(), JoinType.SINGLE,
                eq(col(pair, 0), col(pair, 1))));
        assertEquals(3000, single.size());
        for (int i = 0; i < 3000; i++) {
            assertEquals(Arrays.asList(i, i < 1000 ? i : null), Arrays.asList(single.get(i)));
        }
        List<Object[]> mark = x.rows(new PlanNode.Join(probe, build, List.of(), List.of(), JoinType.MARK,
                eq(col(pair, 0), col(pair, 1))));
        assertEquals(3000, mark.size());
        for (int i = 0; i < 3000; i++) {
            assertEquals(Arrays.asList(i, i < 1000), Arrays.asList(mark.get(i)));
        }
    }

    private static final Schema PAIR = ORDERS.concat(CUSTOMERS);

    /** The ids of the orders and the column after the columns of the orders. */
    private static List<List<Object>> firstAndAfterOrders(List<Object[]> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Arrays.asList(r[0], r[3]));
        }
        return result;
    }

    @Test
    void markJoinHasTheThreeValuesOfIn() {
        PlanNode.Scan o = orders();
        PlanNode.Scan c = customers();
        Executor x = executor();
        PlanNode withNull = new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.MARK);
        assertEquals(List.of("id", "customer", "amount", PlanNode.Join.MARK_COLUMN), withNull.schema().fields()
                .stream().map(Schema.Field::name).toList());
        assertTrue(withNull.schema().nullable(3));
        // A right row with a NULL key: no match is unknown.
        assertEquals(List.of(List.of(1, true), List.of(2, true), List.of(3, true), Arrays.asList(4, null),
                Arrays.asList(5, null)), firstAndAfterOrders(x.rows(withNull)));
        PlanNode known = new PlanNode.Filter(c, isNotNull(col(CUSTOMERS, 0)));
        assertEquals(List.of(List.of(1, true), List.of(2, true), List.of(3, true), Arrays.asList(4, null),
                List.of(5, false)), firstAndAfterOrders(x.rows(new PlanNode.Join(o, known, List.of(1), List.of(0),
                JoinType.MARK))));
        // Nothing on the right: false, even for a NULL key.
        PlanNode none = new PlanNode.Filter(c, lit(false));
        assertEquals(List.of(List.of(1, false), List.of(2, false), List.of(3, false), List.of(4, false),
                List.of(5, false)), firstAndAfterOrders(x.rows(new PlanNode.Join(o, none, List.of(1), List.of(0),
                JoinType.MARK))));
        // Without keys: whether there is a right row at all, or one for which the condition holds.
        assertEquals(List.of(true, true, true, true, true), x.rows(new PlanNode.Join(o, c, List.of(), List.of(),
                JoinType.MARK)).stream().map(r -> r[3]).toList());
        assertEquals(List.of(false, false, false, false, false), x.rows(new PlanNode.Join(o, none, List.of(),
                List.of(), JoinType.MARK)).stream().map(r -> r[3]).toList());
        PlanNode below = new PlanNode.Join(o, c, List.of(), List.of(), JoinType.MARK,
                lt(col(PAIR, 1), col(PAIR, 3)));
        assertEquals(List.of(true, true, true, false, true), x.rows(below).stream().map(r -> r[3]).toList());
        // A condition besides the keys.
        PlanNode twenty = new PlanNode.Join(o, known, List.of(1), List.of(0), JoinType.MARK,
                eq(col(PAIR, 4), lit("twenty")));
        assertEquals(List.of(List.of(1, false), List.of(2, true), List.of(3, false), Arrays.asList(4, null),
                List.of(5, false)), firstAndAfterOrders(x.rows(twenty)));
    }

    @Test
    void singleJoinFailsOnMoreThanOneMatch() {
        PlanNode.Scan o = orders();
        PlanNode.Scan c = customers();
        Executor x = executor();
        QueryException twice = assertThrows(QueryException.class,
                () -> x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.SINGLE)));
        assertTrue(twice.getMessage().contains(HashJoinOperator.MORE_THAN_ONE_ROW), twice.getMessage());
        PlanNode once = new PlanNode.Filter(c, ne(col(CUSTOMERS, 1), lit("ten again")));
        PlanNode single = new PlanNode.Join(o, once, List.of(1), List.of(0), JoinType.SINGLE);
        assertTrue(single.schema().nullable(4));
        assertEquals(List.of(List.of(1, "ten"), List.of(2, "twenty"), List.of(3, "ten"), Arrays.asList(4, null),
                Arrays.asList(5, null)), x.rows(single).stream().map(r -> Arrays.asList(r[0], r[4])).toList());
        // Without keys, the one right row goes with every left row, none gives NULLs, and two are an error.
        PlanNode forty = new PlanNode.Filter(c, eq(col(CUSTOMERS, 1), lit("forty")));
        assertEquals(List.of("forty", "forty", "forty", "forty", "forty"), x.rows(new PlanNode.Join(o, forty,
                List.of(), List.of(), JoinType.SINGLE)).stream().map(r -> r[4]).toList());
        PlanNode none = new PlanNode.Filter(c, lit(false));
        assertEquals(Arrays.asList(null, null, null, null, null), x.rows(new PlanNode.Join(o, none, List.of(),
                List.of(), JoinType.SINGLE)).stream().map(r -> r[4]).toList());
        assertThrows(QueryException.class, () -> x.rows(new PlanNode.Join(o, once, List.of(), List.of(),
                JoinType.SINGLE)));
        // The condition decides which rows match.
        PlanNode again = new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.SINGLE,
                eq(col(PAIR, 4), lit("ten again")));
        assertEquals(Arrays.asList("ten again", null, "ten again", null, null),
                x.rows(again).stream().map(r -> r[4]).toList());
    }

    @Test
    void conditionsBesidesTheKeys() {
        PlanNode.Scan o = orders();
        PlanNode.Scan c = customers();
        Executor x = executor();
        Expression notTen = ne(col(PAIR, 4), lit("ten"));
        assertEquals(List.of(List.of(1, "ten again"), List.of(2, "twenty"), List.of(3, "ten again")),
                x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.INNER, notTen)).stream()
                        .map(r -> Arrays.asList(r[0], r[4])).toList());
        assertEquals(List.of(List.of(1, "ten again"), List.of(2, "twenty"), List.of(3, "ten again"),
                Arrays.asList(4, null), Arrays.asList(5, null)),
                x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.LEFT, notTen)).stream()
                        .map(r -> Arrays.asList(r[0], r[4])).toList());
        List<List<Object>> right = x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.RIGHT, notTen))
                .stream().map(r -> Arrays.asList(r[0], r[4])).toList();
        assertEquals(6, right.size());
        assertTrue(right.contains(Arrays.asList(null, "ten")), right.toString());
        assertEquals(8, x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.FULL, notTen)).size());
        Expression twenty = eq(col(PAIR, 4), lit("twenty"));
        assertEquals(List.of(2), x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.SEMI, twenty))
                .stream().map(r -> r[0]).toList());
        assertEquals(List.of(1, 3, 4, 5), x.rows(new PlanNode.Join(o, c, List.of(1), List.of(0), JoinType.ANTI,
                twenty)).stream().map(r -> r[0]).toList());
        // Without keys, the condition alone; a NULL in it matches nothing.
        Expression below = lt(col(PAIR, 1), col(PAIR, 3));
        assertEquals(List.of(1, 2, 3, 5), x.rows(new PlanNode.Join(o, c, List.of(), List.of(), JoinType.SEMI, below))
                .stream().map(r -> r[0]).toList());
        assertEquals(List.of(4), x.rows(new PlanNode.Join(o, c, List.of(), List.of(), JoinType.ANTI, below))
                .stream().map(r -> r[0]).toList());
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Join(o, c, List.of(), List.of(),
                JoinType.INNER, below));
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Join(o, c, List.of(), List.of(),
                JoinType.CROSS, below));
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Join(o, c, List.of(1), List.of(0),
                JoinType.SEMI, col(PAIR, 0)));
    }

    @Test
    void aggregates() {
        PlanNode plan = new PlanNode.Aggregate(orders(), List.of(1), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.COUNT, 2, false, "amounts"),
                new AggregateCall(AggregateFunction.SUM, 2, false, "total"),
                new AggregateCall(AggregateFunction.SUM, 0, false, "ids"),
                new AggregateCall(AggregateFunction.AVG, 0, false, "avg_id"),
                new AggregateCall(AggregateFunction.MIN, 2, false, "min"),
                new AggregateCall(AggregateFunction.MAX, 0, true, "max_id"),
                new AggregateCall(AggregateFunction.AVG, 2, false, "avg_amount"),
                new AggregateCall(AggregateFunction.MIN, 0, false, "min_id")));
        assertEquals(DataType.HUGEINT, plan.schema().type(4));
        assertEquals(DataType.decimal(38, 2), plan.schema().type(3));
        List<List<Object>> result = rows(executor().rows(plan));
        assertEquals(4, result.size());
        assertEquals(Arrays.asList(10, 2L, 1L, new BigDecimal("5.00"), BigInteger.valueOf(4), 2.0,
                new BigDecimal("5.00"), 3, 5.0, 1), result.get(0));
        assertEquals(Arrays.asList(null, 1L, 1L, new BigDecimal("1.25"), BigInteger.valueOf(4), 4.0,
                new BigDecimal("1.25"), 4, 1.25, 4), result.get(2));
        // No groups and no rows: one row with count 0 and NULL sums.
        PlanNode empty = new PlanNode.Aggregate(new PlanNode.Filter(orders(), lit(false)), List.of(), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 0, false, "s")));
        assertEquals(List.of(Arrays.asList(0L, null)), rows(executor().rows(empty)));
        // DISTINCT counts each value once.
        PlanNode distinct = new PlanNode.Aggregate(orders(), List.of(), List.of(
                new AggregateCall(AggregateFunction.COUNT, 1, true, "customers")));
        assertEquals(List.of(List.of(3L)), rows(executor().rows(distinct)));
    }

    @Test
    void orderedAggregates() {
        PlanNode plan = new PlanNode.Aggregate(orders(), List.of(), List.of(
                new AggregateCall(AggregateFunction.STRING_AGG, 2, false, "amounts").withSeparator(";")
                        .withOrderBy(List.of(SortKey.desc(0))),
                new AggregateCall(AggregateFunction.LIST, 1, false, "customers").withOrderBy(List.of(SortKey.asc(0))),
                new AggregateCall(AggregateFunction.LIST, 1, true, "distinct")
                        .withOrderBy(List.of(new SortKey(1, true, true))),
                new AggregateCall(AggregateFunction.FIRST, 2, false, "first").withOrderBy(List.of(SortKey.desc(0))),
                new AggregateCall(AggregateFunction.LAST, 1, false, "last").withOrderBy(List.of(SortKey.asc(0))),
                new AggregateCall(AggregateFunction.FIRST, 1, false, "first_null")
                        .withOrderBy(List.of(new SortKey(1, true, true))),
                new AggregateCall(AggregateFunction.ANY_VALUE, 1, false, "any")
                        .withOrderBy(List.of(new SortKey(1, true, true))),
                new AggregateCall(AggregateFunction.STRING_AGG, 1, false, "no_separator").withSeparator(null)));
        assertEquals(DataType.listOf(DataType.INTEGER), plan.schema().type(1));
        assertEquals(List.of(Arrays.asList("2.00;1.25;7.50;5.00", Arrays.asList(10, 20, 10, null, 30),
                Arrays.asList(null, 10, 20, 30), new BigDecimal("2.00"), 30, null, 10, null)),
                rows(executor().rows(plan)));
        assertThrows(IllegalArgumentException.class, () -> new AggregateCall(AggregateFunction.LIST, 1, true, "x")
                .withOrderBy(List.of(SortKey.asc(0))), "a DISTINCT aggregate is ordered only by its argument");
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Aggregate(orders(), List.of(), List.of(
                new AggregateCall(AggregateFunction.BOOL_AND, 1, false, "x"))));
        assertEquals(AggregateFunction.LIST, AggregateFunction.of("array_agg"));
        assertEquals(AggregateFunction.STRING_AGG, AggregateFunction.of("GROUP_CONCAT"));
        assertEquals(AggregateFunction.COUNT, AggregateFunction.of("count"));
        assertThrows(IllegalArgumentException.class, () -> AggregateFunction.of("count_star"));
    }

    @Test
    void sortUnionAndNulls() {
        PlanNode.Scan o = orders();
        PlanNode plan = new PlanNode.Sort(new PlanNode.Union(List.of(o, o)),
                List.of(new SortKey(1, false, true), SortKey.asc(0)));
        List<Object> customers = rows(executor().rows(plan)).stream().map(r -> r.get(1)).toList();
        assertEquals(Arrays.asList(null, null, 30, 30, 20, 20, 10, 10, 10, 10), customers);
        PlanNode byAmount = new PlanNode.Sort(o, List.of(SortKey.desc(2)));
        List<Object> ids = rows(executor().rows(byAmount)).stream().map(r -> r.get(0)).toList();
        assertEquals(List.of(2, 1, 5, 4, 3), ids, "NULL last with DESC, as in DuckDB");
    }

    @Test
    void valuesAndSetOperations() {
        Schema s = Schema.of(DataType.INTEGER, DataType.VARCHAR);
        PlanNode l = new PlanNode.Values(s, List.of(row(1, "a"), row(1, "a"), row(2, "b"), row(null, null),
                row(3, "c"), row(1, "a")));
        PlanNode r = new PlanNode.Values(s, List.of(row(1, "a"), row(null, null), row(4, "d"), row(1, "a")));
        Executor x = executor();
        assertEquals(List.of(Arrays.asList(1, "a"), Arrays.asList(1, "a"), Arrays.asList(2, "b"), Arrays.asList(null, null),
                Arrays.asList(3, "c"), Arrays.asList(1, "a")), rows(x.rows(l)));
        assertEquals(List.of(Arrays.asList(2, "b"), Arrays.asList(3, "c")),
                rows(x.rows(new PlanNode.SetOperation(l, r, PlanNode.SetOperationKind.EXCEPT, false))));
        assertEquals(List.of(Arrays.asList(2, "b"), Arrays.asList(3, "c"), Arrays.asList(1, "a")),
                rows(x.rows(new PlanNode.SetOperation(l, r, PlanNode.SetOperationKind.EXCEPT, true))));
        assertEquals(List.of(Arrays.asList(1, "a"), Arrays.asList(null, null)),
                rows(x.rows(new PlanNode.SetOperation(l, r, PlanNode.SetOperationKind.INTERSECT, false))));
        assertEquals(List.of(Arrays.asList(1, "a"), Arrays.asList(1, "a"), Arrays.asList(null, null)),
                rows(x.rows(new PlanNode.SetOperation(l, r, PlanNode.SetOperationKind.INTERSECT, true))));
        assertEquals(List.of(), rows(x.rows(new PlanNode.Values(s, List.of()))));
        assertEquals(List.of(List.of(0L)), rows(x.rows(new PlanNode.Aggregate(new PlanNode.Values(s, List.of()),
                List.of(), List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"))))),
                "an empty VALUES counts to zero");
    }

    private static Object[] row(Integer i, String s) {
        return new Object[] {i, s};
    }

    @Test
    void errorsNameTheOperatorAndTheBudgetHolds() {
        PlanNode.Scan o = orders();
        PlanNode overflow = new PlanNode.Project(o, List.of(new NamedExpression("x",
                add(col(ORDERS, 0), lit(Integer.MAX_VALUE)))));
        QueryException e = assertThrows(QueryException.class, () -> executor().rows(overflow));
        assertEquals("Project#0", e.operator());
        assertTrue(e.getCause() instanceof ArithmeticException);
        Executor tight = new Executor(scan -> Batches.reader(tables.get(scan.table())), new MemoryBudget(64), 3);
        QueryException budget = assertThrows(QueryException.class,
                () -> tight.rows(new PlanNode.Sort(o, List.of(SortKey.asc(0)))));
        assertTrue(budget.getMessage().contains("Memory budget"));
    }
}
