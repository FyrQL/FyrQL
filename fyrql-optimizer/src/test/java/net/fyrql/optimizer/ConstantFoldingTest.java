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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.date;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.iff;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Constant expressions evaluated once, the logic around them simplified, with the same rows as before. */
class ConstantFoldingTest {

    private static final MemoryConnector MEMORY = Tables.builder().build();
    private static final Executor EXECUTOR = Tables.executor(MEMORY);
    private static final Scan T = Scan.all(Tables.T_REF, Tables.T);

    private static final Expression A = col(Tables.T, 0);
    private static final Expression B = col(Tables.T, 1);
    private static final Expression C = col(Tables.T, 2);
    private static final Expression K = col(Tables.T, 3);
    private static final Expression TRUE = lit(true);
    private static final Expression FALSE = lit(false);
    private static final Expression NULL = nullOf(DataType.BOOLEAN);

    private static void assertSameRows(PlanNode plan, PlanNode folded) {
        assertEquals(plan.schema(), folded.schema());
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, folded)),
                Plans.print(folded));
    }

    @Test
    void constantSubexpressionsBecomeLiteralsOfTheirType() {
        assertEquals(lit(3L), ConstantFolding.fold(add(lit(1), lit(2L))));
        assertEquals(gt(A, lit(6)), ConstantFolding.fold(gt(A, mul(lit(2), add(lit(1), lit(2))))));
        assertEquals(lit(12), ConstantFolding.fold(cast(lit("12"), DataType.INTEGER)));
        assertEquals(date(7), ConstantFolding.fold(sub(date(10), lit(3))));
        assertEquals(nullOf(DataType.INTEGER), ConstantFolding.fold(add(lit(1), nullOf(DataType.INTEGER))));
        assertEquals(lit("ab"), ConstantFolding.fold(call("concat", lit("a"), lit("b"))));
        // Fails when it runs, for the rows that reach it.
        Expression failing = cast(lit("x"), DataType.INTEGER);
        assertSame(failing, ConstantFolding.fold(failing));
        Expression branch = caseWhen(List.of(when(gt(A, lit(0)), failing)), lit(1));
        assertSame(branch, ConstantFolding.fold(branch));
        // Neither random nor a column is a constant.
        Expression random = gt(call("random"), lit(0.5));
        assertSame(random, ConstantFolding.fold(random));
        Expression column = add(A, lit(1));
        assertSame(column, ConstantFolding.fold(column));
    }

    @Test
    void andOrAndNotLoseTheirConstantOperands() {
        Expression x = gt(A, lit(1));
        Expression y = lt(C, lit(0.0));
        assertEquals(x, ConstantFolding.fold(and(TRUE, x)));
        assertEquals(x, ConstantFolding.fold(and(x, gt(lit(2), lit(1)))));
        assertEquals(FALSE, ConstantFolding.fold(and(x, FALSE)));
        assertEquals(FALSE, ConstantFolding.fold(and(FALSE, x)));
        assertEquals(x, ConstantFolding.fold(or(FALSE, x)));
        assertEquals(TRUE, ConstantFolding.fold(or(x, TRUE)));
        assertEquals(or(x, y), ConstantFolding.fold(or(or(x, FALSE), y)));
        assertEquals(x, ConstantFolding.fold(not(not(x))));
        assertEquals(FALSE, ConstantFolding.fold(not(TRUE)));
        // NULL decides nothing without the other operand.
        assertEquals(and(x, NULL), ConstantFolding.fold(and(x, NULL)));
        assertEquals(or(NULL, x), ConstantFolding.fold(or(NULL, x)));
        assertEquals(NULL, ConstantFolding.fold(or(NULL, NULL)));
        assertEquals(TRUE, ConstantFolding.fold(or(NULL, TRUE)));
    }

    @Test
    void caseAndIfDropTheBranchesThatAreNeverTaken() {
        Expression x = gt(A, lit(1));
        // BIGINT from the dropped first branch: the new last branch is cast to it.
        Expression c = caseWhen(List.of(when(FALSE, lit(1L)), when(x, lit(2)), when(TRUE, lit(3)), when(x, lit(4))),
                lit(5));
        assertEquals(DataType.BIGINT, c.type());
        assertEquals(caseWhen(List.of(when(x, lit(2))), lit(3L)), ConstantFolding.fold(c));
        assertEquals(caseWhen(List.of(when(x, lit(2))), cast(A, DataType.BIGINT)),
                ConstantFolding.fold(caseWhen(List.of(when(FALSE, lit(1L)), when(x, lit(2))), A)));
        assertEquals(lit(5L), ConstantFolding.fold(caseWhen(List.of(when(NULL, lit(1L)), when(FALSE, lit(2))),
                lit(5))));
        assertEquals(lit(2L), ConstantFolding.fold(caseWhen(List.of(when(TRUE, lit(2))), lit(5L))));
        Expression kept = caseWhen(List.of(when(x, lit(2))), lit(5));
        assertSame(kept, ConstantFolding.fold(kept));
        assertEquals(A, ConstantFolding.fold(iff(TRUE, A, K)));
        assertEquals(K, ConstantFolding.fold(iff(NULL, A, K)));
        assertEquals(cast(A, DataType.BIGINT), ConstantFolding.fold(iff(FALSE, lit(1L), A)));
    }

    @Test
    void aConjunctOfEveryBranchOfAnOrIsTakenOutInFront() {
        Expression key = eq(A, K);
        Expression a1 = gt(C, lit(1.0));
        Expression b1 = eq(B, lit("x"));
        Expression a2 = lt(C, lit(0.0));
        Expression a3 = isNull(B);
        // As in TPC-H Q19: the join condition in each of three branches.
        Expression q19 = or(or(and(and(key, a1), b1), and(a2, key)), and(key, and(a3, key)));
        assertEquals(and(key, or(or(and(a1, b1), a2), a3)), ConstantFolding.fold(q19));
        // Two conjuncts in common, in the order of the first branch.
        Expression two = or(and(and(key, b1), a1), and(and(b1, a2), key));
        assertEquals(and(and(key, b1), or(a1, a2)), ConstantFolding.fold(two));
        // A branch that is the common part alone makes the OR that part.
        assertEquals(key, ConstantFolding.fold(or(and(key, a1), key)));
        // A conversion may fail, so a comparison of an INTEGER with a DOUBLE stays where it is.
        Expression converted = gt(C, A);
        Expression mixed = or(and(converted, a2), and(converted, b1));
        assertEquals(mixed, ConstantFolding.fold(mixed));
        Expression list = in(B, lit("x"), lit("y"));
        assertEquals(and(list, or(a1, a2)), ConstantFolding.fold(or(and(list, a1), and(list, a2))));
        assertTrue(ConstantFolding.cannotFail(and(key, list)));
        assertFalse(ConstantFolding.cannotFail(gt(add(A, lit(1)), K)));
        assertFalse(ConstantFolding.cannotFail(in(A, lit(1L))));

        // The same rows, with NULLs in both branches.
        for (Expression condition : List.of(q19, two, mixed, or(and(key, a1), key), or(and(list, a1), and(list, a2)),
                or(and(eq(A, lit(3)), isNull(C)), and(eq(A, lit(3)), not(isNull(C)))))) {
            PlanNode plan = new PlanNode.Filter(T, condition);
            assertSameRows(plan, ConstantFolding.fold(plan));
        }
    }

    @Test
    void plansFoldTheirExpressionsAndLoseFiltersThatAreTrue() {
        PlanNode filter = new PlanNode.Filter(T, and(gt(A, add(lit(1), lit(1))), gt(lit(2), lit(1))));
        PlanNode project = new PlanNode.Project(filter, List.of(new NamedExpression("x", add(A, mul(lit(2), lit(3)))),
                new NamedExpression("y", iff(TRUE, B, lit("none")))));
        PlanNode folded = ConstantFolding.fold(project);
        PlanNode expected = new PlanNode.Project(new PlanNode.Filter(T, gt(A, lit(2))), List.of(
                new NamedExpression("x", add(A, lit(6))), new NamedExpression("y", B)));
        assertEquals(expected, folded);
        assertSameRows(project, folded);
        assertSame(folded, ConstantFolding.fold(folded));

        assertSame(T, ConstantFolding.fold(new PlanNode.Filter(T, or(gt(A, lit(1)), lt(lit(1), lit(2))))));
        PlanNode never = new PlanNode.Filter(T, and(gt(A, lit(1)), lt(lit(2), lit(1))));
        assertEquals(new PlanNode.Filter(T, FALSE), ConstantFolding.fold(never));
        assertSameRows(never, ConstantFolding.fold(never));
        // Joins and their inputs, by identity.
        PlanNode join = new PlanNode.Join(new PlanNode.Filter(T, TRUE), T, List.of(0), List.of(0),
                PlanNode.JoinType.INNER);
        PlanNode.Join j = (PlanNode.Join) ConstantFolding.fold(join);
        assertSame(T, j.left());
        assertSame(T, j.right());
        Optimizer optimizer = new Optimizer(name -> MEMORY, Optimizer.Options.DEFAULT);
        PlanNode once = optimizer.optimize(project);
        assertEquals(once, optimizer.optimize(once));
    }
}
