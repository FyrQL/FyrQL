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

import net.fyrql.connector.Capabilities;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Pushed;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scans narrowed to the columns in use, with the same rows as before. */
class ColumnPruningTest {

    private static final MemoryConnector MEMORY = Tables.builder().build();
    private static final Executor EXECUTOR = Tables.executor(MEMORY);
    /** The same tables in a source that reads fields inside columns. */
    private static final MemoryConnector FIELDS = Tables.builder().pushdown(Capabilities.NONE.withFields(true))
            .build();
    private static final Executor FIELDS_EXECUTOR = Tables.executor(FIELDS);

    private static void assertSameRows(PlanNode plan, PlanNode pruned) {
        assertEquals(plan.schema(), pruned.schema());
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, pruned)),
                Plans.print(pruned));
    }

    @Test
    void scansKeepOnlyTheColumnsInUse() {
        PlanNode plan = new PlanNode.Project(
                new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), gt(col(Tables.T, 0), lit(1))),
                List.of(new NamedExpression("b", col(Tables.T, 1)), new NamedExpression("k", col(Tables.T, 3))));
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, pruned);
        PlanNode.Filter f = assertInstanceOf(PlanNode.Filter.class, p.input());
        Scan s = assertInstanceOf(Scan.class, f.input());
        assertEquals(List.of(0, 1, 3), s.columns());
        assertEquals(gt(col(s.schema(), 0), lit(1)), f.condition());
        assertEquals(List.of(new NamedExpression("b", col(s.schema(), 1)), new NamedExpression("k", col(s.schema(), 2))),
                p.columns());
        assertSameRows(plan, pruned);
    }

    @Test
    void joinsKeepTheirKeysAndAggregatesDropUnusedCalls() {
        PlanNode join = new PlanNode.Join(Scan.all(Tables.T_REF, Tables.T), Scan.all(Tables.U_REF, Tables.U),
                List.of(0), List.of(0), JoinType.LEFT);
        PlanNode plan = new PlanNode.Project(join, List.of(new NamedExpression("n", col(join.schema(), 5))));
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, pruned);
        PlanNode.Join j = assertInstanceOf(PlanNode.Join.class, p.input());
        assertEquals(List.of(0), assertInstanceOf(Scan.class, j.left()).columns());
        assertEquals(List.of(0, 1), assertInstanceOf(Scan.class, j.right()).columns());
        assertEquals(List.of(0), j.leftKeys());
        assertEquals(List.of(0), j.rightKeys());
        assertEquals(List.of(new NamedExpression("n", col(j.schema(), 2))), p.columns());
        assertSameRows(plan, pruned);

        PlanNode aggregate = new PlanNode.Aggregate(Scan.all(Tables.T_REF, Tables.T), List.of(3), List.of(
                new AggregateCall(AggregateFunction.SUM, 0, false, "s", null, List.of()),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "c", null, List.of()),
                new AggregateCall(AggregateFunction.MAX, 2, false, "m", null, List.of())));
        plan = new PlanNode.Project(aggregate, List.of(new NamedExpression("c", col(aggregate.schema(), 2)),
                new NamedExpression("k", col(aggregate.schema(), 0))));
        pruned = ColumnPruning.prune(plan);
        PlanNode.Aggregate a = assertInstanceOf(PlanNode.Aggregate.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input());
        assertEquals(List.of(0), a.groupColumns());
        assertEquals(1, a.aggregates().size());
        assertEquals("c", a.aggregates().get(0).name());
        assertEquals(List.of(3), assertInstanceOf(Scan.class, a.input()).columns());
        assertSameRows(plan, pruned);
    }

    @Test
    void joinsKeepTheColumnsOfTheirConditionAndAMarkJoinItsMark() {
        Scan t = Scan.all(Tables.T_REF, Tables.T);
        Scan u = Scan.all(Tables.U_REF, Tables.U);
        Schema pair = Tables.T.concat(Tables.U);
        PlanNode mark = new PlanNode.Join(t, u, List.of(0), List.of(0), JoinType.MARK, gt(col(pair, 5), lit(5L)));
        PlanNode plan = new PlanNode.Project(mark, List.of(new NamedExpression("b", col(mark.schema(), 1)),
                new NamedExpression("m", col(mark.schema(), 4))));
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, pruned);
        PlanNode.Join j = assertInstanceOf(PlanNode.Join.class, p.input());
        assertEquals(List.of(0, 1), assertInstanceOf(Scan.class, j.left()).columns());
        assertEquals(List.of(0, 1), assertInstanceOf(Scan.class, j.right()).columns());
        assertEquals(gt(col(3, DataType.BIGINT), lit(5L)), j.condition());
        assertEquals(List.of(new NamedExpression("b", col(j.schema(), 1)), new NamedExpression("m",
                col(j.schema(), 2))), p.columns());
        assertSameRows(plan, pruned);

        PlanNode semi = new PlanNode.Join(t, u, List.of(), List.of(), JoinType.SEMI,
                eq(col(pair, 3), col(pair, 4)));
        plan = new PlanNode.Project(semi, List.of(new NamedExpression("a", col(semi.schema(), 0))));
        pruned = ColumnPruning.prune(plan);
        j = assertInstanceOf(PlanNode.Join.class, assertInstanceOf(PlanNode.Project.class, pruned).input());
        assertEquals(List.of(0, 3), assertInstanceOf(Scan.class, j.left()).columns());
        assertEquals(List.of(0), assertInstanceOf(Scan.class, j.right()).columns());
        assertEquals(eq(col(1, DataType.INTEGER), col(2, DataType.INTEGER)), j.condition());
        assertSameRows(plan, pruned);
    }

    @Test
    void sortKeysStayAndSetOperationInputsKeepTheSameColumns() {
        PlanNode sorted = new PlanNode.Sort(Scan.all(Tables.T_REF, Tables.T), List.of(SortKey.desc(2)));
        PlanNode plan = new PlanNode.Limit(new PlanNode.Project(sorted,
                List.of(new NamedExpression("b", col(Tables.T, 1)))), 3, 1);
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Sort s = assertInstanceOf(PlanNode.Sort.class,
                assertInstanceOf(PlanNode.Project.class, assertInstanceOf(PlanNode.Limit.class, pruned).input())
                        .input());
        assertEquals(List.of(SortKey.desc(1)), s.keys());
        assertEquals(List.of(1, 2), assertInstanceOf(Scan.class, s.input()).columns());
        assertEquals(Tables.rows(EXECUTOR, plan).size(), Tables.rows(EXECUTOR, pruned).size());
        assertEquals(plan.schema(), pruned.schema());

        PlanNode union = new PlanNode.Union(List.of(Scan.all(Tables.T_REF, Tables.T),
                new PlanNode.Project(Scan.all(Tables.T_REF, Tables.T), List.of(
                        new NamedExpression("a", col(Tables.T, 0)), new NamedExpression("b", col(Tables.T, 1)),
                        new NamedExpression("c", col(Tables.T, 2)), new NamedExpression("k", col(Tables.T, 3))))));
        plan = new PlanNode.Project(union, List.of(new NamedExpression("k", col(union.schema(), 3))));
        pruned = ColumnPruning.prune(plan);
        PlanNode.Union u = assertInstanceOf(PlanNode.Union.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input());
        for (PlanNode in : u.inputs()) {
            assertEquals(1, in.schema().size(), Plans.print(in));
        }
        assertSameRows(plan, pruned);

        PlanNode except = new PlanNode.SetOperation(Scan.all(Tables.T_REF, Tables.T),
                new PlanNode.Filter(Scan.all(Tables.T_REF, Tables.T), gt(col(Tables.T, 0), lit(3))),
                PlanNode.SetOperationKind.EXCEPT, false);
        plan = new PlanNode.Project(except, List.of(new NamedExpression("a", col(Tables.T, 0))));
        pruned = ColumnPruning.prune(plan);
        PlanNode.SetOperation e = assertInstanceOf(PlanNode.SetOperation.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input());
        assertEquals(4, e.left().schema().size(), "the rows are compared whole");
        assertEquals(4, e.right().schema().size());
        assertSameRows(plan, pruned);
    }

    @Test
    void anUnnestKeepsTheColumnsOfItsListAndItsElements() {
        PlanNode.Unnest unnest = new PlanNode.Unnest(Scan.all(Tables.T_REF, Tables.T),
                call("list_value", col(Tables.T, 0), col(Tables.T, 3)), "x");
        PlanNode plan = new PlanNode.Project(unnest, List.of(new NamedExpression("b", col(unnest.schema(), 1)),
                new NamedExpression("x", col(unnest.schema(), 4))));
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Unnest u = assertInstanceOf(PlanNode.Unnest.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input());
        Scan s = assertInstanceOf(Scan.class, u.input());
        assertEquals(List.of(0, 1, 3), s.columns());
        assertEquals(call("list_value", col(s.schema(), 0), col(s.schema(), 2)), u.list());
        assertEquals(List.of(new NamedExpression("b", col(u.schema(), 1)), new NamedExpression("x", col(u.schema(), 3))),
                ((PlanNode.Project) pruned).columns());
        assertSameRows(plan, pruned);
    }

    @Test
    void aWindowKeepsTheCallsInUseAndTheColumnsTheyRead() {
        AggregateCall count = new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n");
        PlanNode.Window window = new PlanNode.Window(Scan.all(Tables.T_REF, Tables.T), List.of(3),
                List.of(SortKey.asc(2)), List.of(PlanNode.WindowCall.of(PlanNode.WindowFunction.FIRST_VALUE,
                        List.of(2), "f"), PlanNode.WindowCall.of(new AggregateCall(AggregateFunction.MAX, 0, false,
                        "m"), PlanNode.Frame.DEFAULT), PlanNode.WindowCall.of(count, PlanNode.Frame.DEFAULT)));
        PlanNode plan = new PlanNode.Project(window, List.of(new NamedExpression("b", col(window.schema(), 1)),
                new NamedExpression("f", col(window.schema(), 4)), new NamedExpression("n", col(window.schema(), 6))));
        PlanNode pruned = ColumnPruning.prune(plan);
        PlanNode.Window w = assertInstanceOf(PlanNode.Window.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input());
        // The maximum of a is not in use: the call goes, and with it the column it alone reads.
        assertEquals(List.of(1, 2, 3), assertInstanceOf(Scan.class, w.input()).columns());
        assertEquals(List.of(2), w.partition());
        assertEquals(List.of(SortKey.asc(1)), w.order());
        assertEquals(List.of(PlanNode.WindowCall.of(PlanNode.WindowFunction.FIRST_VALUE, List.of(1), "f"),
                PlanNode.WindowCall.of(count, PlanNode.Frame.DEFAULT)), w.calls());
        assertEquals(List.of(new NamedExpression("b", col(w.schema(), 0)), new NamedExpression("f", col(w.schema(), 3)),
                new NamedExpression("n", col(w.schema(), 4))), ((PlanNode.Project) pruned).columns());
        assertSameRows(plan, pruned);
    }

    @Test
    void aWindowWithoutACallInUseGoesAway() {
        PlanNode.Window window = new PlanNode.Window(Scan.all(Tables.T_REF, Tables.T), List.of(3),
                List.of(SortKey.asc(0)), List.of(PlanNode.WindowCall.of(PlanNode.WindowFunction.RANK, List.of(),
                        "r")));
        PlanNode plan = new PlanNode.Project(window, List.of(new NamedExpression("b", col(window.schema(), 1))));
        PlanNode pruned = ColumnPruning.prune(plan);
        Scan s = assertInstanceOf(Scan.class, assertInstanceOf(PlanNode.Project.class, pruned).input());
        assertEquals(List.of(1), s.columns());
        assertEquals(List.of(new NamedExpression("b", col(s.schema(), 0))), ((PlanNode.Project) pruned).columns());
        assertSameRows(plan, pruned);
    }

    @Test
    void theRootKeepsItsColumnsAndANegotiatedScanIsLeftAlone() {
        Scan all = Scan.all(Tables.T_REF, Tables.T);
        assertEquals(all, ColumnPruning.prune(all));

        Scan negotiated = new Scan(Tables.T_REF, Tables.T, List.of(0, 1), new Pushed(
                List.of(gt(col(0, DataType.INTEGER), lit(1))), OptionalLong.empty(), List.of(), Optional.empty()));
        PlanNode plan = new PlanNode.Project(negotiated, List.of(new NamedExpression("b", col(negotiated.schema(), 1))));
        PlanNode pruned = ColumnPruning.prune(plan);
        assertSame(negotiated, assertInstanceOf(PlanNode.Project.class, pruned).input());

        // Nothing in use below the projection: one column stays, so that the rows are counted.
        plan = new PlanNode.Project(all, List.of(new NamedExpression("one", lit(1))));
        pruned = ColumnPruning.prune(plan);
        assertEquals(List.of(0), assertInstanceOf(Scan.class, assertInstanceOf(PlanNode.Project.class, pruned)
                .input()).columns());
        assertSameRows(plan, pruned);
    }

    private static Expression field(Expression struct, String... path) {
        return Expressions.structField(struct, List.of(path));
    }

    /** Prunes with fields; the rows stay the same, and a second pass changes nothing. */
    private static PlanNode pruneFields(PlanNode plan) {
        PlanNode pruned = ColumnPruning.prune(plan, name -> FIELDS);
        assertEquals(plan.schema().types(), pruned.schema().types());
        assertEquals(Tables.sorted(Tables.rows(FIELDS_EXECUTOR, plan)),
                Tables.sorted(Tables.rows(FIELDS_EXECUTOR, pruned)), Plans.print(pruned));
        assertEquals(pruned, ColumnPruning.prune(pruned, name -> FIELDS), "a second pass changes nothing");
        return pruned;
    }

    private static List<String> names(PlanNode node) {
        return node.schema().fields().stream().map(Schema.Field::name).toList();
    }

    @Test
    void aScanReadsOnlyTheFieldsInUseWhereItsConnectorReadsFields() {
        Expression s = col(Tables.P, 1);
        PlanNode plan = new PlanNode.Project(
                new PlanNode.Filter(Scan.all(Tables.P_REF, Tables.P), eq(field(s, "b"), lit("p"))),
                List.of(new NamedExpression("a", field(s, "a")), new NamedExpression("x", field(s, "c", "x"))));
        PlanNode pruned = pruneFields(plan);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, pruned);
        PlanNode.Filter f = assertInstanceOf(PlanNode.Filter.class, p.input());
        Scan scan = assertInstanceOf(Scan.class, f.input());
        assertEquals(List.of(1, 1, 1), scan.columns());
        assertEquals(List.of(List.of("a"), List.of("c", "x"), List.of("b")), scan.fields());
        assertEquals(List.of("s.a", "s.c.x", "s.b"), names(scan));
        assertEquals(eq(col(scan.schema(), 2), lit("p")), f.condition());
        assertEquals(List.of(new NamedExpression("a", col(scan.schema(), 0)),
                new NamedExpression("x", col(scan.schema(), 1))), p.columns());

        // A connector that reads whole columns gets the whole column, and the fields are taken out above it.
        pruned = ColumnPruning.prune(plan, name -> MEMORY);
        assertEquals(ColumnPruning.prune(plan), pruned);
        scan = assertInstanceOf(Scan.class, assertInstanceOf(PlanNode.Filter.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input()).input());
        assertEquals(List.of(1), scan.columns());
        assertFalse(scan.readsFields());
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, pruned)));
    }

    @Test
    void anOuterFieldOrTheWholeColumnIsReadInsteadOfTheFieldsInsideIt() {
        Expression s = col(Tables.P, 1);
        PlanNode plan = new PlanNode.Project(Scan.all(Tables.P_REF, Tables.P), List.of(
                new NamedExpression("x", field(s, "c", "x")), new NamedExpression("c", field(s, "c")),
                new NamedExpression("y", field(s, "c", "y"))));
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, pruneFields(plan));
        Scan scan = assertInstanceOf(Scan.class, p.input());
        assertEquals(List.of(List.of("c")), scan.fields());
        Expression c = col(scan.schema(), 0);
        assertEquals(List.of(new NamedExpression("x", field(c, "x")), new NamedExpression("c", c),
                new NamedExpression("y", field(c, "y"))), p.columns());

        plan = new PlanNode.Project(Scan.all(Tables.P_REF, Tables.P), List.of(
                new NamedExpression("a", field(s, "a")), new NamedExpression("s", s)));
        p = assertInstanceOf(PlanNode.Project.class, pruneFields(plan));
        scan = assertInstanceOf(Scan.class, p.input());
        assertEquals(List.of(1), scan.columns());
        assertFalse(scan.readsFields());
        assertEquals(List.of(new NamedExpression("a", field(col(scan.schema(), 0), "a")),
                new NamedExpression("s", col(scan.schema(), 0))), p.columns());
    }

    @Test
    void aProjectionPassesTheFieldsInUseOnToItsInputAndJoinKeysAreUsedWhole() {
        PlanNode inner = new PlanNode.Project(Scan.all(Tables.P_REF, Tables.P), List.of(
                new NamedExpression("id", col(Tables.P, 0)), new NamedExpression("t", col(Tables.P, 1)),
                new NamedExpression("c", field(col(Tables.P, 1), "c"))));
        PlanNode plan = new PlanNode.Project(new PlanNode.Filter(inner, gt(col(inner.schema(), 0), lit(1))),
                List.of(new NamedExpression("a", field(col(inner.schema(), 1), "a")),
                        new NamedExpression("y", field(col(inner.schema(), 2), "y"))));
        PlanNode pruned = pruneFields(plan);
        PlanNode.Project in = assertInstanceOf(PlanNode.Project.class, assertInstanceOf(PlanNode.Filter.class,
                assertInstanceOf(PlanNode.Project.class, pruned).input()).input());
        assertEquals(List.of("id", "t.a", "c.y"), names(in));
        Scan scan = assertInstanceOf(Scan.class, in.input());
        assertEquals(List.of(0, 1, 1), scan.columns());
        assertEquals(List.of(List.of(), List.of("a"), List.of("c", "y")), scan.fields());

        PlanNode join = new PlanNode.Join(Scan.all(Tables.P_REF, Tables.P), Scan.all(Tables.P_REF, Tables.P),
                List.of(1), List.of(1), JoinType.INNER);
        plan = new PlanNode.Project(join, List.of(new NamedExpression("a", field(col(join.schema(), 1), "a")),
                new NamedExpression("b", field(col(join.schema(), 3), "b"))));
        PlanNode.Join j = assertInstanceOf(PlanNode.Join.class,
                assertInstanceOf(PlanNode.Project.class, pruneFields(plan)).input());
        assertFalse(assertInstanceOf(Scan.class, j.left()).readsFields());
        assertFalse(assertInstanceOf(Scan.class, j.right()).readsFields());
    }

    @Test
    void aConnectorThatNoLongerReadsAFieldItReadBeforeIsRejected() {
        // The scan reads the field a and the whole column, of which the field b is in use.
        Scan scan = new Scan(Tables.P_REF, Tables.P, List.of(1, 1), List.of(List.of("a"), List.of()), Pushed.NONE);
        PlanNode plan = new PlanNode.Project(scan, List.of(new NamedExpression("a", col(scan.schema(), 0)),
                new NamedExpression("b", field(col(scan.schema(), 1), "b"))));
        Scan fields = assertInstanceOf(Scan.class,
                assertInstanceOf(PlanNode.Project.class, pruneFields(plan)).input());
        assertEquals(List.of(List.of("a"), List.of("b")), fields.fields());
        // Without fields to read anew, the connector is not asked again.
        PlanNode read = ColumnPruning.prune(plan, name -> FIELDS);
        assertEquals(read, ColumnPruning.prune(read, name -> MEMORY));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ColumnPruning.prune(plan, name -> MEMORY));
        assertTrue(e.getMessage().contains("breaks the rules of the scan negotiation"), e.getMessage());
    }
}
