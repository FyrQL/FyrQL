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

package net.fyrql.core.expr;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.coalesce;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.iff;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreeValuedLogicTest {

    private static final Expression T = lit(true);
    private static final Expression F = lit(false);
    private static final Expression N = nullOf(DataType.BOOLEAN);

    /** An expression that throws when it is evaluated: an overflow. */
    private static final Expression THROWS = eq(sub(lit(Integer.MIN_VALUE), lit(1)), lit(0));

    /** A batch of one row without columns. */
    static final Batch ONE_ROW = Batch.ofRows(new Schema(List.of()), List.<Object[]>of(new Object[0]));

    static Object eval(Expression e) {
        return Evaluator.eval(e, ONE_ROW).get(0);
    }

    @Test
    void andTruthTable() {
        assertEquals(true, eval(and(T, T)));
        assertEquals(false, eval(and(T, F)));
        assertNull(eval(and(T, N)));
        assertEquals(false, eval(and(F, T)));
        assertEquals(false, eval(and(F, F)));
        assertEquals(false, eval(and(F, N)));
        assertNull(eval(and(N, T)));
        assertEquals(false, eval(and(N, F)));
        assertNull(eval(and(N, N)));
    }

    @Test
    void orTruthTable() {
        assertEquals(true, eval(or(T, T)));
        assertEquals(true, eval(or(T, F)));
        assertEquals(true, eval(or(T, N)));
        assertEquals(true, eval(or(F, T)));
        assertEquals(false, eval(or(F, F)));
        assertNull(eval(or(F, N)));
        assertEquals(true, eval(or(N, T)));
        assertNull(eval(or(N, F)));
        assertNull(eval(or(N, N)));
    }

    @Test
    void notAndIsNull() {
        assertEquals(false, eval(not(T)));
        assertEquals(true, eval(not(F)));
        assertNull(eval(not(N)));
        assertEquals(true, eval(isNull(N)));
        assertEquals(false, eval(isNull(T)));
        assertEquals(false, eval(isNotNull(nullOf(DataType.INTEGER))));
        assertEquals(true, eval(isNotNull(lit(0))));
    }

    @Test
    void comparisonsWithNull() {
        assertNull(eval(eq(nullOf(DataType.INTEGER), lit(1))));
        assertNull(eval(eq(nullOf(DataType.VARCHAR), nullOf(DataType.VARCHAR))));
    }

    @Test
    void rightOperandsAndBranchesAreEvaluatedOnlyWhereNeeded() {
        assertEquals(false, eval(and(F, THROWS)));
        assertEquals(true, eval(or(T, THROWS)));
        assertThrows(ArithmeticException.class, () -> eval(and(T, THROWS)));
        assertThrows(ArithmeticException.class, () -> eval(and(N, THROWS)));
        assertThrows(ArithmeticException.class, () -> eval(and(THROWS, F)));
        assertEquals(1, eval(caseWhen(List.of(when(T, lit(1))), sub(lit(Integer.MIN_VALUE), lit(1)))));
        assertEquals(2, eval(iff(F, sub(lit(Integer.MIN_VALUE), lit(1)), lit(2))));
        assertEquals(3, eval(coalesce(lit(3), sub(lit(Integer.MIN_VALUE), lit(1)))));
    }

    @Test
    void betweenAndIn() {
        assertEquals(true, eval(between(lit(5), lit(1), lit(10))));
        assertEquals(false, eval(between(lit(5), nullOf(DataType.INTEGER), lit(4))));
        assertNull(eval(between(lit(5), nullOf(DataType.INTEGER), lit(10))));
        assertNull(eval(between(nullOf(DataType.INTEGER), lit(1), lit(10))));
        assertEquals(true, eval(between(lit(5), lit(1L), lit(10.0))));
        assertEquals(true, eval(in(lit(2), lit(1), lit(2))));
        assertEquals(false, eval(in(lit(3), lit(1), lit(2))));
        assertNull(eval(in(lit(3), lit(1), nullOf(DataType.INTEGER))));
        assertEquals(true, eval(in(lit(1), lit(1), nullOf(DataType.INTEGER))));
        assertNull(eval(in(nullOf(DataType.INTEGER), lit(1))));
    }

    @Test
    void caseAndCoalesce() {
        assertEquals(2, eval(caseWhen(List.of(when(F, lit(1)), when(N, lit(3))), lit(2))));
        assertEquals(1, eval(caseWhen(List.of(when(T, lit(1))), lit(2))));
        assertEquals(2, eval(iff(N, lit(1), lit(2))));
        assertEquals(5L, eval(coalesce(nullOf(DataType.INTEGER), lit(5L))));
        assertNull(eval(coalesce(nullOf(DataType.INTEGER), nullOf(DataType.INTEGER))));
    }
}
