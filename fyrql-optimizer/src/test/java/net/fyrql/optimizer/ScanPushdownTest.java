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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The negotiation of scans with a memory connector that takes over filters without function calls, and limits. */
class ScanPushdownTest {

    private static final MemoryConnector MEMORY = Tables.builder()
            .pushdown(Capabilities.filtersAndLimit(e -> Expressions.allMatch(e, n -> !(n instanceof Expression.Call))))
            .build();
    private static final Executor EXECUTOR = Tables.executor(MEMORY);
    private static final Optimizer OPTIMIZER = new Optimizer(name -> MEMORY, Optimizer.Options.DEFAULT);

    private static final Expression A_GT_1 = gt(col(Tables.T, 0), lit(1));
    private static final Expression B_NOT_NULL = isNotNull(col(Tables.T, 1));
    private static final Expression ABS_A_GT_0 = gt(call("abs", col(Tables.T, 0)), lit(0));

    private static PlanNode optimize(PlanNode plan) {
        PlanNode optimized = OPTIMIZER.optimize(plan);
        assertEquals(plan.schema(), optimized.schema());
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, optimized)),
                Plans.print(optimized));
        assertEquals(optimized, OPTIMIZER.optimize(optimized), "a second pass changes nothing");
        return optimized;
    }

    private static Scan scanOf(PlanNode node) {
        PlanNode n = node;
        while (!(n instanceof Scan)) {
            n = n.children().get(0);
        }
        return (Scan) n;
    }

    @Test
    void acceptedConjunctsMoveIntoTheScanAndTheRestStays() {
        PlanNode plan = new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T),
                and(and(A_GT_1, ABS_A_GT_0), B_NOT_NULL));
        PlanNode optimized = optimize(plan);
        PlanNode.Filter f = assertInstanceOf(PlanNode.Filter.class, optimized);
        assertEquals(ABS_A_GT_0, f.condition());
        Scan s = assertInstanceOf(Scan.class, f.input());
        assertEquals(List.of(A_GT_1, B_NOT_NULL), s.pushed().filters());
        assertTrue(s.pushed().limit().isEmpty());

        optimized = optimize(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), and(A_GT_1, B_NOT_NULL)));
        assertEquals(List.of(A_GT_1, B_NOT_NULL), assertInstanceOf(Scan.class, optimized).pushed().filters());
    }

    @Test
    void theLimitFollowsTheFilters() {
        PlanNode plan = new PlanNode.Limit(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1), 3, 0);
        PlanNode optimized = optimize(plan);
        Scan s = assertInstanceOf(Scan.class, optimized);
        assertEquals(OptionalLong.of(3), s.pushed().limit());
        assertEquals(3, Tables.rows(EXECUTOR, optimized).size());

        plan = new PlanNode.Limit(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1), 3, 2);
        optimized = optimize(plan);
        PlanNode.Limit l = assertInstanceOf(PlanNode.Limit.class, optimized);
        assertEquals(2, l.offset());
        assertEquals(OptionalLong.of(5), scanOf(optimized).pushed().limit());

        plan = new PlanNode.Limit(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), and(A_GT_1, ABS_A_GT_0)), 3,
                0);
        optimized = optimize(plan);
        assertInstanceOf(PlanNode.Limit.class, optimized);
        assertTrue(scanOf(optimized).pushed().limit().isEmpty(), "a filter stays with the engine");
        assertEquals(List.of(A_GT_1), scanOf(optimized).pushed().filters());
    }

    @Test
    void aSortTheConnectorDeclinesKeepsTheLimitWithTheEngine() {
        PlanNode plan = new PlanNode.Limit(new PlanNode.Sort(
                new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1), List.of(SortKey.asc(2))), 2, 0);
        PlanNode optimized = optimize(plan);
        PlanNode.Limit l = assertInstanceOf(PlanNode.Limit.class, optimized);
        PlanNode.Sort s = assertInstanceOf(PlanNode.Sort.class, l.input());
        Scan scan = assertInstanceOf(Scan.class, s.input());
        assertEquals(List.of(A_GT_1), scan.pushed().filters());
        assertTrue(scan.pushed().order().isEmpty());
        assertTrue(scan.pushed().limit().isEmpty());
    }

    @Test
    void aProjectionBetweenStaysAndMapsTheKeysAbove() {
        PlanNode project = new PlanNode.Project(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1),
                List.of(new NamedExpression("b", col(Tables.T, 1)), new NamedExpression("a", col(Tables.T, 0))));
        PlanNode plan = new PlanNode.Limit(new PlanNode.Sort(project, List.of(SortKey.desc(1))), 4, 0);
        PlanNode optimized = optimize(plan);
        PlanNode.Sort s = assertInstanceOf(PlanNode.Sort.class, assertInstanceOf(PlanNode.Limit.class, optimized)
                .input());
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, s.input());
        assertEquals(List.of(A_GT_1), assertInstanceOf(Scan.class, p.input()).pushed().filters());

        // A projection that computes something lets the limit through, but not a sort on the computed column.
        PlanNode computing = new PlanNode.Project(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1),
                List.of(new NamedExpression("twice", Expressions.mul(col(Tables.T, 0), lit(2)))));
        optimized = optimize(new PlanNode.Limit(computing, 2, 0));
        assertEquals(OptionalLong.of(2), scanOf(optimized).pushed().limit());
        assertInstanceOf(PlanNode.Project.class, optimized);
        optimized = optimize(new PlanNode.Limit(new PlanNode.Sort(computing, List.of(SortKey.asc(0))), 2, 0));
        assertTrue(scanOf(optimized).pushed().limit().isEmpty());
        assertEquals(List.of(A_GT_1), scanOf(optimized).pushed().filters());
    }

    @Test
    void anAggregationIsAskedForButTheMemorySourceDeclinesIt() {
        PlanNode project = new PlanNode.Project(new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1),
                List.of(new NamedExpression("k", col(Tables.T, 3)), new NamedExpression("a", col(Tables.T, 0))));
        PlanNode plan = new PlanNode.Aggregate(project, List.of(0),
                List.of(new AggregateCall(AggregateFunction.SUM, 1, false, "s", null, List.of())));
        PlanNode optimized = optimize(plan);
        PlanNode.Aggregate a = assertInstanceOf(PlanNode.Aggregate.class, optimized);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, a.input());
        Scan s = assertInstanceOf(Scan.class, p.input());
        assertEquals(List.of(A_GT_1), s.pushed().filters());
        assertTrue(s.pushed().aggregation().isEmpty());
    }

    @Test
    void nothingToNegotiateLeavesThePlanAndJoinsAreDescended() {
        PlanNode plan = new PlanNode.Project(Scan.all(Tables.T_REF, Tables.T),
                List.of(new NamedExpression("a", col(Tables.T, 0))));
        assertEquals(plan, ScanPushdown.push(plan, name -> MEMORY));

        // The filter leaves fewer rows of t than u has: t is the right input, which the join builds.
        PlanNode join = new PlanNode.Join(Scan.all(Tables.U_REF, Tables.U),
                new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), A_GT_1), List.of(0), List.of(0),
                PlanNode.JoinType.INNER);
        PlanNode optimized = optimize(join);
        PlanNode.Join j = assertInstanceOf(PlanNode.Join.class, optimized);
        assertTrue(assertInstanceOf(Scan.class, j.left()).pushed().isEmpty());
        assertEquals(List.of(A_GT_1), assertInstanceOf(Scan.class, j.right()).pushed().filters());
    }

    @Test
    void aConnectorThatBreaksTheRulesIsRejected() {
        Connector cheat = new Connector() {
            @Override
            public String name() {
                return MEMORY.name();
            }

            @Override
            public Catalog catalog() {
                return MEMORY.catalog();
            }

            @Override
            public ScanPlan plan(TableRef table, ScanRequest request) {
                ScanPlan plan = MEMORY.plan(table, request);
                // Accepts the limit although a filter stays with the engine.
                return new ScanPlan(plan.table(), plan.output(), plan.columns(), plan.acceptedFilters(),
                        request.limit().isPresent(), false, false, plan.state());
            }

            @Override
            public List<Split> splits(ScanPlan plan) {
                return MEMORY.splits(plan);
            }

            @Override
            public BatchReader open(Split split) {
                return MEMORY.open(split);
            }
        };
        Optimizer optimizer = new Optimizer(name -> cheat, Optimizer.Options.DEFAULT);
        PlanNode plan = new PlanNode.Limit(
                new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), and(A_GT_1, ABS_A_GT_0)), 3, 0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> optimizer.optimize(plan));
        assertTrue(e.getMessage().contains("breaks the rules"), e.getMessage());

        Optimizer off = new Optimizer(name -> cheat, Optimizer.Options.NO_PUSHDOWN);
        assertFalse(off.options().pushdown());
        assertEquals(plan, off.optimize(plan));
    }
}
