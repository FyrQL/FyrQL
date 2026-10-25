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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.Plans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A common table runs its definition once, and its scans read the rows as
 * often as they are scanned: the same rows as the plan with the definition
 * in place of each scan, in memory and spilled, on one thread and on
 * several; the budget is given back and the file is deleted.
 */
class CommonTableTest {

    @TempDir
    Path temp;

    private final SplitTables source = new SplitTables();

    /** The rows of a plan as text, sorted. */
    private static List<String> sorted(List<Object[]> rows) {
        return rows.stream().map(Arrays::toString).sorted().toList();
    }

    /** Puts the definition of each common table in place of its scans. */
    private static PlanNode inline(PlanNode node) {
        if (node instanceof PlanNode.CommonTable t) {
            return inline(t.input());
        }
        if (node instanceof PlanNode.CommonTableScan s) {
            return s.columns().equals(PlanNode.CommonTableScan.all(s.id(), s.name(), s.definition()).columns())
                    ? inline(s.definition()) : new PlanNode.Project(inline(s.definition()), s.columns().stream()
                            .map(c -> new PlanNode.NamedExpression(s.definition().schema().name(c),
                                    new net.fyrql.core.expr.Expression.Column(c, s.definition().schema().type(c))))
                            .toList());
        }
        return Plans.withChildren(node, node.children().stream().map(CommonTableTest::inline).toList());
    }

    /** Runs a plan; the budget is given back and no file is left. */
    private List<Object[]> run(PlanNode plan, MemoryBudget budget, int threads, SpillDirectory spill) {
        Executor executor = new Executor(source, budget, 64, Codegen.off(), threads, true, spill);
        try {
            return executor.rows(plan);
        } finally {
            assertEquals(0, budget.used(), "the budget is given back");
            assertNoFilesLeft(spill);
        }
    }

    private static void assertNoFilesLeft(SpillDirectory spill) {
        Path dir = spill == null ? null : spill.path();
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.toList(), "the file is deleted");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The groups of the numbers by k, with their counts and sums, read three times and joined by k. */
    private PlanNode groups() {
        PlanNode.Scan numbers = source.table("numbers", 400, 0, 350, 250);
        PlanNode definition = new PlanNode.Aggregate(numbers, List.of(1),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n", null, List.of()),
                        new AggregateCall(AggregateFunction.SUM, 0, false, "s", null, List.of())));
        PlanNode first = PlanNode.CommonTableScan.all(1, "c", definition);
        PlanNode second = new PlanNode.CommonTableScan(1, "c", List.of(0, 2), definition);
        PlanNode third = new PlanNode.CommonTableScan(1, "c", List.of(1, 0), definition);
        PlanNode join = new PlanNode.Join(new PlanNode.Join(first, second, List.of(0), List.of(0), JoinType.INNER),
                third, List.of(0), List.of(1), JoinType.INNER);
        return new PlanNode.CommonTable(1, "c", definition, join);
    }

    @Test
    void threeScansReadTheRowsOfOneRunInMemory() {
        PlanNode plan = groups();
        List<String> expected = sorted(run(inline(plan), MemoryBudget.unlimited(), 1, null));
        assertEquals(5, expected.size());
        for (int threads : new int[] {1, 4}) {
            MemoryBudget budget = new MemoryBudget(1 << 20);
            assertEquals(expected, sorted(run(plan, budget, threads, null)), "threads " + threads);
        }
    }

    /** The numbers read three times, one after the other. */
    private PlanNode numbersThrice() {
        PlanNode.Scan numbers = source.table("numbers", 400, 0, 350, 250);
        List<PlanNode> scans = List.of(PlanNode.CommonTableScan.all(1, "c", numbers),
                PlanNode.CommonTableScan.all(1, "c", numbers), PlanNode.CommonTableScan.all(1, "c", numbers));
        return new PlanNode.CommonTable(1, "c", numbers, new PlanNode.Union(scans));
    }

    @Test
    void rowsOverTheBudgetGoToAFileThatEveryScanReads() {
        PlanNode plan = numbersThrice();
        List<Object[]> expected = run(inline(plan), MemoryBudget.unlimited(), 1, null);
        assertEquals(3000, expected.size());
        for (int threads : new int[] {1, 4}) {
            try (SpillDirectory spill = new SpillDirectory(temp)) {
                MemoryBudget budget = new MemoryBudget(2_000);
                List<Object[]> rows = run(plan, budget, threads, spill);
                // The order of the scans is kept, and that of the rows of each.
                assertEquals(sorted(expected), sorted(rows), "threads " + threads);
                assertEquals(expected.stream().map(Arrays::toString).toList(),
                        rows.stream().map(Arrays::toString).toList(), "threads " + threads);
                assertEquals(1, spill.statistics().parts());
                assertTrue(spill.statistics().bytesRead() >= 3 * spill.statistics().bytesWritten() * 9 / 10,
                        spill.statistics().toString());
            }
        }
    }

    @Test
    void withoutADirectoryToSpillToTheBudgetRefuses() {
        MemoryBudget budget = new MemoryBudget(2_000);
        QueryException e = assertThrows(QueryException.class, () -> run(numbersThrice(), budget, 1, null));
        assertTrue(e.getMessage().contains("CommonTable#0"), e.getMessage());
    }

    @Test
    void aCanceledQueryStopsAndGivesTheBudgetBack() {
        MemoryBudget budget = new MemoryBudget(1 << 20);
        QueryContext context = new QueryContext();
        Executor executor = new Executor(source, budget, 64, Codegen.off(), 1, true, null);
        Operator op = executor.open(numbersThrice(), node -> Double.NaN, context);
        assertNotNull(op.next());
        assertTrue(budget.used() > 0, "the rows are kept");
        context.cancel();
        assertThrows(QueryCancelledException.class, op::next);
        op.close();
        assertEquals(0, budget.used());
    }
}
