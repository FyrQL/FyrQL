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

package net.fyrql.optimizer;

import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.Filter;
import net.fyrql.core.plan.PlanNode.Join;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Conditions moved down the plan and into join keys, with the same rows as before. */
class PredicatePushdownTest {

    private static final MemoryConnector MEMORY = Tables.builder().build();
    private static final Executor EXECUTOR = Tables.executor(MEMORY);
    private static final Scan T = Scan.all(Tables.T_REF, Tables.T);
    private static final Scan U = Scan.all(Tables.U_REF, Tables.U);

    /** The columns of t, then those of u after them in a join. */
    private static final Expression A = col(Tables.T, 0);
    private static final Expression B = col(Tables.T, 1);
    private static final Expression C = col(Tables.T, 2);
    private static final Expression K = col(Tables.T, 3);
    private static final Expression UA = col(4, DataType.INTEGER);
    private static final Expression UN = col(5, DataType.BIGINT);

    private static PlanNode pushed(PlanNode plan) {
        PlanNode result = PredicatePushdown.push(plan);
        assertEquals(plan.schema().types(), result.schema().types());
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, result)),
                Plans.print(result));
        assertEquals(result, PredicatePushdown.push(result), "a second pass changes nothing");
        return result;
    }

    private static Join join(PlanNode left, PlanNode right, JoinType type, int leftKey, int rightKey) {
        return new Join(left, right, List.of(leftKey), List.of(rightKey), type);
    }

    private static Join cross(PlanNode left, PlanNode right) {
        return new Join(left, right, List.of(), List.of(), JoinType.CROSS);
    }

    @Test
    void anEqualityBetweenTheInputsOfACrossJoinBecomesItsKey() {
        PlanNode plan = new Filter(cross(T, U), and(and(eq(A, UA), gt(C, lit(0.0))), gt(UN, lit(5L))));
        assertEquals(join(new Filter(T, gt(C, lit(0.0))), new Filter(U, gt(col(1, DataType.BIGINT), lit(5L))),
                JoinType.INNER, 0, 0), pushed(plan));

        // Three tables, t twice: each equality goes to the join of its two tables.
        Scan t2 = Scan.all(Tables.T_REF, Tables.T);
        PlanNode three = new Filter(cross(cross(T, U), t2), and(eq(col(9, DataType.INTEGER), K), eq(UA, A)));
        assertEquals(join(join(T, U, JoinType.INNER, 0, 0), t2, JoinType.INNER, 3, 3), pushed(three));

        // A condition over both inputs that is no equality stays above the join, an equality in the ON as well.
        Expression sum = lt(add(A, UA), lit(6));
        PlanNode keyed = new Filter(join(T, U, JoinType.INNER, 0, 0), and(and(sum, eq(UA, A)), eq(K, UA)));
        assertEquals(new Filter(new Join(T, U, List.of(0, 3), List.of(0, 0), JoinType.INNER), sum), pushed(keyed));
    }

    @Test
    void aKeyOfAnotherTypeOrNoColumnIsComputedBelowTheJoin() {
        // INTEGER = BIGINT: the left side is cast, and the restored columns skip the computed key.
        Expression sum = lt(add(A, UA), lit(6));
        PlanNode plan = new Filter(cross(T, U), and(eq(A, UN), sum));
        PlanNode left = Plans.withKeyColumns(T, List.of(cast(A, DataType.BIGINT)));
        PlanNode joined = new Filter(join(left, U, JoinType.INNER, 4, 1), lt(add(A, col(5, DataType.INTEGER)), lit(6)));
        assertEquals(new PlanNode.Project(joined, List.of(new NamedExpression("a", A), new NamedExpression("b", B),
                new NamedExpression("c", C), new NamedExpression("k", K),
                new NamedExpression("a", col(5, DataType.INTEGER)), new NamedExpression("n", col(6, DataType.BIGINT)))),
                pushed(plan));

        // A computed key on the right, and the same one twice.
        Expression plusOne = add(col(0, DataType.INTEGER), lit(1));
        PlanNode computed = new Filter(cross(T, U), and(eq(add(UA, lit(1)), K), eq(K, add(UA, lit(1)))));
        PlanNode right = Plans.withKeyColumns(U, List.of(plusOne));
        PlanNode result = pushed(computed);
        assertEquals(join(T, right, JoinType.INNER, 3, 2), ((PlanNode.Project) result).input());

        // Lists are no keys.
        Schema lists = Schema.of(new Schema.Field("l", DataType.listOf(DataType.INTEGER)));
        PlanNode values = new PlanNode.Values(lists, List.<Object[]>of(new Object[] {List.of(1)}));
        Expression same = eq(col(0, lists.type(0)), col(1, lists.type(0)));
        PlanNode listJoin = new Filter(cross(values, values), same);
        assertEquals(listJoin, pushed(listJoin));
    }

    @Test
    void filtersMergeWithTheirOwnConditionsFirstAndRandomStays() {
        Expression random = lt(call("random"), lit(2.0));
        PlanNode plan = new Filter(new Filter(T, and(random, gt(A, lit(1)))), eq(B, lit("x")));
        assertEquals(new Filter(new Filter(T, and(gt(A, lit(1)), eq(B, lit("x")))), random), pushed(plan));
        PlanNode never = new Filter(new Filter(T, gt(A, lit(1))), lit(false));
        assertEquals(new Filter(T, lit(false)), pushed(never));
    }

    @Test
    void aProjectionPassesConditionsWithItsExpressionsPutIn() {
        PlanNode.Project project = new PlanNode.Project(T, List.of(new NamedExpression("x", add(A, lit(1))),
                new NamedExpression("b", B), new NamedExpression("r", lt(call("random"), lit(2.0)))));
        Expression r = col(2, DataType.BOOLEAN);
        PlanNode plan = new Filter(project, and(gt(col(0, DataType.INTEGER), lit(3)), r));
        assertEquals(new Filter(new PlanNode.Project(new Filter(T, gt(add(A, lit(1)), lit(3))), project.columns()), r),
                pushed(plan));

        // A condition that becomes constant is folded.
        PlanNode.Project one = new PlanNode.Project(T, List.of(new NamedExpression("one", lit(1)),
                new NamedExpression("a", A)));
        assertEquals(one, pushed(new Filter(one, eq(col(0, DataType.INTEGER), lit(1)))));
    }

    @Test
    void outerJoinsPassConditionsToThePreservedInputOnly() {
        Expression onLeft = gt(C, lit(0.0));
        Expression onRight = isNull(UN);
        Expression both = and(onLeft, onRight);
        Filter t = new Filter(T, onLeft);
        Filter u = new Filter(U, isNull(col(1, DataType.BIGINT)));
        assertEquals(join(t, u, JoinType.INNER, 0, 0), pushed(new Filter(join(T, U, JoinType.INNER, 0, 0), both)));
        assertEquals(new Filter(join(t, U, JoinType.LEFT, 0, 0), onRight),
                pushed(new Filter(join(T, U, JoinType.LEFT, 0, 0), both)));
        assertEquals(new Filter(join(T, u, JoinType.RIGHT, 0, 0), onLeft),
                pushed(new Filter(join(T, U, JoinType.RIGHT, 0, 0), both)));
        PlanNode full = new Filter(join(T, U, JoinType.FULL, 0, 0), both);
        assertEquals(full, pushed(full));
        // A full join keeps an equality between its inputs as a filter, too.
        PlanNode fullEquality = new Filter(join(T, U, JoinType.FULL, 0, 0), eq(K, UA));
        assertEquals(fullEquality, pushed(fullEquality));
        for (JoinType type : List.of(JoinType.SEMI, JoinType.ANTI)) {
            assertEquals(join(t, U, type, 0, 0), pushed(new Filter(join(T, U, type, 0, 0), onLeft)));
        }
    }

    @Test
    void theConditionOfAJoinGivesItsInputsWhatTheyCanFilterAndBecomesKeys() {
        Expression onLeft = gt(C, lit(0.0));
        Expression onRight = isNull(UN);
        Expression sum = lt(add(A, UA), lit(6));
        Expression condition = Expressions.conjunction(List.of(onLeft, onRight, eq(K, UA), sum));
        Filter t = new Filter(T, onLeft);
        Filter u = new Filter(U, isNull(col(1, DataType.BIGINT)));
        List<Integer> leftKeys = List.of(0, 3);
        List<Integer> rightKeys = List.of(0, 0);
        assertEquals(new Filter(new Join(t, u, leftKeys, rightKeys, JoinType.INNER), sum),
                pushed(new Join(T, U, List.of(0), List.of(0), JoinType.INNER, condition)));
        for (JoinType type : List.of(JoinType.LEFT, JoinType.SINGLE)) {
            assertEquals(new Join(T, u, leftKeys, rightKeys, type, and(onLeft, sum)),
                    pushed(new Join(T, U, List.of(0), List.of(0), type, condition)), type.name());
        }
        assertEquals(new Join(t, U, leftKeys, rightKeys, JoinType.RIGHT, and(onRight, sum)),
                pushed(new Join(T, U, List.of(0), List.of(0), JoinType.RIGHT, condition)));
        assertEquals(new Join(T, U, leftKeys, rightKeys, JoinType.FULL,
                Expressions.conjunction(List.of(onLeft, onRight, sum))),
                pushed(new Join(T, U, List.of(0), List.of(0), JoinType.FULL, condition)));
        assertEquals(new Join(t, u, leftKeys, rightKeys, JoinType.SEMI, sum),
                pushed(new Join(T, U, List.of(0), List.of(0), JoinType.SEMI, condition)));
        assertEquals(new Join(T, u, leftKeys, rightKeys, JoinType.ANTI, and(onLeft, sum)),
                pushed(new Join(T, U, List.of(0), List.of(0), JoinType.ANTI, condition)));
        // Without keys of its own, a semi join gets them from its condition.
        assertEquals(new Join(T, U, List.of(3), List.of(0), JoinType.SEMI, sum),
                pushed(new Join(T, U, List.of(), List.of(), JoinType.SEMI, and(eq(UA, K), sum))));
        // A mark join keeps its condition whole.
        PlanNode mark = new Join(T, U, List.of(0), List.of(0), JoinType.MARK, condition);
        assertEquals(mark, pushed(mark));
    }

    @Test
    void markAndSingleJoinsPassConditionsOverTheirLeftInput() {
        Expression onLeft = gt(C, lit(0.0));
        Filter t = new Filter(T, onLeft);
        Expression mark = col(4, DataType.BOOLEAN);
        assertEquals(new Filter(join(t, U, JoinType.MARK, 0, 0), mark),
                pushed(new Filter(join(T, U, JoinType.MARK, 0, 0), and(onLeft, mark))));
        Filter small = new Filter(U, lt(col(0, DataType.INTEGER), lit(3)));
        assertEquals(new Filter(join(t, small, JoinType.SINGLE, 0, 0), isNull(UN)),
                pushed(new Filter(join(T, small, JoinType.SINGLE, 0, 0), and(onLeft, isNull(UN)))));
    }

    @Test
    void anAggregationPassesConditionsOverItsGroupColumns() {
        List<AggregateCall> count = List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"));
        PlanNode grouped = new PlanNode.Aggregate(T, List.of(3, 1), count);
        Expression onCount = gt(col(2, DataType.BIGINT), lit(1L));
        PlanNode plan = new Filter(grouped, and(and(gt(col(0, DataType.INTEGER), lit(10)), onCount),
                eq(col(1, DataType.VARCHAR), lit("x"))));
        assertEquals(new Filter(new PlanNode.Aggregate(new Filter(T, and(gt(K, lit(10)), eq(B, lit("x")))),
                List.of(3, 1), count), onCount), pushed(plan));
        // Without groups, an empty input still gives a row: nothing passes.
        PlanNode total = new Filter(new PlanNode.Aggregate(T, List.of(), count), gt(col(0, DataType.BIGINT), lit(0L)));
        assertEquals(total, pushed(total));
    }

    @Test
    void sortsPassUnionsCopyLimitsStop() {
        Expression x = gt(A, lit(1));
        List<SortKey> keys = List.of(SortKey.desc(0));
        assertEquals(new PlanNode.Sort(new Filter(T, x), keys), pushed(new Filter(new PlanNode.Sort(T, keys), x)));
        PlanNode limited = new Filter(new PlanNode.Limit(new PlanNode.Sort(T, keys), 3), x);
        assertEquals(limited, pushed(limited));
        assertEquals(new PlanNode.Union(List.of(new Filter(T, x), new Filter(T, x))),
                pushed(new Filter(new PlanNode.Union(List.of(T, T)), x)));
        Filter onlyX = new Filter(T, eq(B, lit("x")));
        for (PlanNode.SetOperationKind kind : PlanNode.SetOperationKind.values()) {
            assertEquals(new PlanNode.SetOperation(new Filter(T, x), onlyX, kind, false),
                    pushed(new Filter(new PlanNode.SetOperation(T, onlyX, kind, false), x)));
        }
    }

    @Test
    void anUnnestPassesTheConditionsThatDoNotReadItsElements() {
        Schema lists = Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("l", DataType.listOf(DataType.INTEGER)));
        PlanNode values = new PlanNode.Values(lists, List.of(new Object[] {1, List.of(10, 20)},
                new Object[] {2, List.of(10, 30)}, new Object[] {3, List.of()}));
        Expression elements = col(lists, 1);
        Expression onId = gt(col(0, DataType.INTEGER), lit(1));
        Expression onElement = or(gt(col(2, DataType.INTEGER), lit(15)), eq(col(0, DataType.INTEGER), lit(3)));
        PlanNode plan = new Filter(new PlanNode.Unnest(values, elements, "x"), and(onElement, onId));
        assertEquals(new Filter(new PlanNode.Unnest(new Filter(values, onId), elements, "x"), onElement),
                pushed(plan));
    }

    @Test
    void theOptimizerPushesBeforeItPrunes() {
        PlanNode plan = new PlanNode.Project(new Filter(cross(T, U), and(eq(A, UA), gt(C, lit(0.0)))),
                List.of(new NamedExpression("b", B), new NamedExpression("n", UN)));
        Optimizer optimizer = new Optimizer(name -> MEMORY,
                new Optimizer.Options(true, true, true, true, false, true, true));
        PlanNode once = optimizer.optimize(plan);
        assertEquals(once, optimizer.optimize(once));
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, once)));
        Join join = (Join) ((PlanNode.Project) once).input();
        assertEquals(JoinType.INNER, join.type());
        // The memory source here takes over no filter: it stays right above the scan of t, narrowed to a, b and c.
        assertEquals(new Filter(new Scan(Tables.T_REF, Tables.T, List.of(0, 1, 2)), gt(C, lit(0.0))), join.left());
        Optimizer without = new Optimizer(name -> MEMORY,
                new Optimizer.Options(true, true, true, false, false, true, true));
        assertEquals(JoinType.CROSS, ((Join) ((Filter) ((PlanNode.Project) without.optimize(plan)).input())
                .input()).type());
    }
}
