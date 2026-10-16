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
import net.fyrql.connector.Batches;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.Join;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of plans offered to a source that runs them as a whole. */
class JoinPushdownTest {

    private static final Executor PLAIN = Tables.executor(Tables.builder().build());
    private static final Scan T = Scan.all(Tables.T_REF, Tables.T);
    private static final Scan U = Scan.all(Tables.U_REF, Tables.U);
    private static final MemoryConnector OTHER = MemoryConnector.builder("other")
            .table("v", Tables.U, List.<Object[]>of(new Object[] {1, 2L}, new Object[] {3, 4L})).build();

    /**
     * A memory source that runs the parts of plans a predicate accepts with
     * an executor of its own, and keeps the parts offered to it.
     */
    private static final class RunningSource implements Connector {
        private final MemoryConnector memory = Tables.builder().build();
        private final Executor executor = Tables.executor(memory);
        private final Predicate<PlanNode> accepts;
        private final Function<PlanNode, Schema> output;
        private final List<PlanNode> offered = new ArrayList<>();

        RunningSource(Predicate<PlanNode> accepts, Function<PlanNode, Schema> output) {
            this.accepts = accepts;
            this.output = output;
        }

        RunningSource(Predicate<PlanNode> accepts) {
            this(accepts, PlanNode::schema);
        }

        @Override
        public String name() {
            return memory.name();
        }

        @Override
        public Catalog catalog() {
            return memory.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return memory.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return memory.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return memory.open(split);
        }

        @Override
        public Optional<RemotePlan> planRemote(PlanNode plan) {
            offered.add(plan);
            return accepts.test(plan) ? Optional.of(new RemotePlan(output.apply(plan), "run", plan)) : Optional.empty();
        }

        @Override
        public BatchReader openRemote(RemotePlan plan) {
            return Batches.reader(executor.execute((PlanNode) plan.state()));
        }
    }

    private static Optimizer optimizer(Connector source, Optimizer.Options options) {
        return new Optimizer(name -> name.equals(OTHER.name()) ? OTHER : source, options);
    }

    /** Optimizes a plan and checks that it keeps its rows and that a second pass changes nothing. */
    private static PlanNode optimize(RunningSource source, PlanNode plan) {
        Optimizer optimizer = optimizer(source, Optimizer.Options.DEFAULT);
        PlanNode optimized = optimizer.optimize(plan);
        List<PlanNode> offered = List.copyOf(source.offered);
        assertEquals(plan.schema().types(), optimized.schema().types());
        // The source is asked again when a part is opened.
        assertEquals(Tables.sorted(Tables.rows(PLAIN, plan)),
                Tables.sorted(Tables.rows(Tables.executor(source), optimized)), Plans.print(optimized));
        source.offered.clear();
        assertEquals(optimized, optimizer.optimize(optimized), "a second pass changes nothing");
        assertEquals(List.of(), source.offered, "a second pass offers nothing");
        source.offered.addAll(offered);
        return optimized;
    }

    private static List<PlanNode.Remote> remotes(PlanNode plan) {
        List<PlanNode.Remote> result = new ArrayList<>();
        for (PlanNode n : Plans.nodes(plan)) {
            if (n instanceof PlanNode.Remote r) {
                result.add(r);
            }
        }
        return result;
    }

    /** The groups of k with the number of matches of t.a in u, for t.a > 1. */
    private static PlanNode grouped() {
        Join join = new Join(new PlanNode.Filter(T, gt(col(Tables.T, 0), lit(1))), U, List.of(0), List.of(0),
                JoinType.INNER);
        return new PlanNode.Aggregate(join, List.of(3), List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1,
                false, "n"), new AggregateCall(AggregateFunction.SUM, 5, false, "total")));
    }

    @Test
    void aJoinOfTablesOfOneSourceIsRunByThatSource() {
        RunningSource source = new RunningSource(p -> true);
        PlanNode optimized = optimize(source, grouped());
        PlanNode.Remote remote = assertInstanceOf(PlanNode.Remote.class, optimized);
        assertEquals(Tables.SOURCE, remote.source());
        assertEquals("run", remote.description());
        // The join order asks for the join, the pushdown for the whole part.
        assertEquals(2, source.offered.size());
        assertInstanceOf(Join.class, source.offered.get(0));
        assertInstanceOf(PlanNode.Aggregate.class, source.offered.get(1));
        assertEquals(2, Plans.scans(optimized).size(), "the scans of the part are scans of the plan");
        assertTrue(Plans.print(optimized).startsWith("Remote mem run\n"), Plans.print(optimized));
    }

    @Test
    void theLargestPartIsOfferedFirstAndThePartsBelowWhenTheSourceDeclines() {
        RunningSource source = new RunningSource(p -> p instanceof Join);
        PlanNode plan = new PlanNode.Limit(new PlanNode.Sort(grouped(), List.of(SortKey.desc(1), SortKey.asc(0))), 2);
        PlanNode optimized = optimize(source, plan);
        // After the join order has asked for the join.
        assertInstanceOf(Join.class, source.offered.get(0));
        assertInstanceOf(PlanNode.Limit.class, source.offered.get(1));
        assertInstanceOf(Join.class, source.offered.get(source.offered.size() - 1));
        assertInstanceOf(PlanNode.Limit.class, optimized);
        List<PlanNode.Remote> remotes = remotes(optimized);
        assertEquals(1, remotes.size(), Plans.print(optimized));
        assertInstanceOf(Join.class, remotes.get(0).logical());
    }

    @Test
    void theConditionsASourceDeclinesStandAboveTheAntiJoinItRuns() {
        // The source evaluates t.a > 1 but not t.a < 5; NOT EXISTS (u.a = t.a) drops a row by t.a alone.
        Expression declined = lt(col(Tables.T, 0), lit(5));
        RunningSource source = new RunningSource(p -> Plans.nodes(p).stream().noneMatch(n -> n instanceof
                PlanNode.Filter f && Expressions.conjuncts(f.condition()).contains(declined)));
        PlanNode plan = new Join(new PlanNode.Filter(T, and(gt(col(Tables.T, 0), lit(1)), declined)), U, List.of(0),
                List.of(0), JoinType.ANTI);
        PlanNode optimized = optimize(source, plan);
        PlanNode.Filter above = assertInstanceOf(PlanNode.Filter.class, optimized, Plans.print(optimized));
        assertEquals(declined, above.condition());
        PlanNode.Remote remote = assertInstanceOf(PlanNode.Remote.class, above.input());
        Join anti = assertInstanceOf(Join.class, remote.logical());
        assertEquals(JoinType.ANTI, anti.type());
        assertEquals(gt(col(Tables.T, 0), lit(1)), assertInstanceOf(PlanNode.Filter.class, anti.left()).condition());
    }

    @Test
    void aJoinOfTwoSourcesIsOfferedToNeither() {
        RunningSource source = new RunningSource(p -> true);
        Scan v = Scan.all(TableRef.of(OTHER.name(), "v"), Tables.U);
        PlanNode plan = new Join(T, v, List.of(0), List.of(0), JoinType.LEFT);
        PlanNode optimized = optimizer(source, Optimizer.Options.DEFAULT).optimize(plan);
        assertEquals(List.of(), source.offered);
        assertEquals(List.of(), remotes(optimized));
    }

    @Test
    void aPartWithoutJoinIsLeftToTheScans() {
        RunningSource source = new RunningSource(p -> true);
        optimize(source, new PlanNode.Filter(T, gt(col(Tables.T, 0), lit(1))));
        assertEquals(List.of(), source.offered);
    }

    @Test
    void withoutPushdownNothingIsOffered() {
        RunningSource source = new RunningSource(p -> true);
        PlanNode optimized = optimizer(source, Optimizer.Options.NO_PUSHDOWN).optimize(grouped());
        assertEquals(List.of(), source.offered);
        assertEquals(List.of(), remotes(optimized));
    }

    @Test
    void aSourceThatPlansOtherColumnsBreaksTheRules() {
        RunningSource source = new RunningSource(p -> true,
                p -> Schema.of(new Schema.Field("x", DataType.VARCHAR)));
        Optimizer optimizer = optimizer(source, Optimizer.Options.DEFAULT);
        assertThrows(IllegalStateException.class, () -> optimizer.optimize(grouped()));
    }
}
