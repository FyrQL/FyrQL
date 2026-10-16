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

package net.fyrql.core.expr;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.div;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.ilike;
import static net.fyrql.core.expr.Expressions.intDiv;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNotDistinct;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.neg;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.rem;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.tryCast;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.ThreeValuedLogicTest.eval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arithmetic, comparison and function semantics measured against DuckDB 1.5.6. */
class EvaluatorTest {

    @Test
    void integralArithmeticKeepsTheTypeAndChecksOverflow() {
        assertEquals((byte) 3, eval(add(lit((byte) 1), lit((byte) 2))));
        assertThrows(ArithmeticException.class, () -> eval(add(lit((byte) 127), lit((byte) 1))));
        assertThrows(ArithmeticException.class, () -> eval(sub(lit(Long.MIN_VALUE), lit(1L))));
        assertThrows(ArithmeticException.class, () -> eval(neg(lit(Integer.MIN_VALUE))));
        assertEquals(3, eval(intDiv(lit(7), lit(2))));
        assertEquals(-3, eval(intDiv(lit(-7), lit(2))));
        assertEquals(1, eval(rem(lit(7), lit(3))));
        assertEquals(-1, eval(rem(lit(-7), lit(3))));
        assertNull(eval(intDiv(lit(1), lit(0))));
        assertNull(eval(rem(lit(1), lit(0))));
        assertEquals(3.5, eval(div(lit(7), lit(2))));
        assertEquals(Double.POSITIVE_INFINITY, eval(div(lit(1), lit(0))));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                eval(add(lit(DataType.HUGEINT, BigInteger.valueOf(Long.MAX_VALUE)), lit(DataType.HUGEINT, BigInteger.ONE))));
        assertThrows(ArithmeticException.class,
                () -> eval(mul(lit(DataType.HUGEINT, BigInteger.ONE.shiftLeft(100)), lit(DataType.HUGEINT, BigInteger.ONE.shiftLeft(100)))));
    }

    @Test
    void decimals() {
        Expression a = lit(new BigDecimal("1.50"));
        Expression b = lit(new BigDecimal("2.5"));
        assertEquals(DataType.decimal(3, 2), a.type());
        assertEquals(new BigDecimal("4.00"), eval(add(a, b)));
        assertEquals(new BigDecimal("3.750"), eval(mul(a, b)));
        assertEquals(0.6, eval(div(a, b)));
        assertEquals(new BigDecimal("1.50"), eval(rem(a, b)));
        assertEquals(0.6, eval(intDiv(a, b)), "// on decimals is the plain division, as in DuckDB");
        assertEquals(new BigDecimal("-1.50"), eval(neg(a)));
        assertThrows(ArithmeticException.class, () -> eval(mul(lit(DataType.decimal(18, 0), new BigDecimal("999999999999999999")),
                lit(DataType.decimal(18, 0), new BigDecimal("999999999999999999")))));
    }

    @Test
    void floatingPointComparisonsFollowTheTotalOrder() {
        assertEquals(true, eval(eq(lit(Double.NaN), lit(Double.NaN))));
        assertEquals(true, eval(gt(lit(Double.NaN), lit(1e308))));
        assertEquals(true, eval(eq(lit(-0.0), lit(0.0))));
        assertEquals(true, eval(gt(lit(2), lit(1.5))));
        assertEquals(true, eval(eq(lit(1), lit(new BigDecimal("1.00")))));
    }

    @Test
    void stringsAndLike() {
        assertEquals(true, eval(like(lit("hello"), lit("h%o"))));
        assertEquals(true, eval(like(lit("h\uD83D\uDE00o"), lit("h_o"))));
        assertEquals(false, eval(like(lit("hello"), lit("h_o"))));
        assertEquals(true, eval(like(lit(""), lit("%"))));
        assertEquals(false, eval(like(lit("a"), lit(""))));
        assertNull(eval(like(nullOf(DataType.VARCHAR), lit("%"))));
        assertEquals(1L, eval(call("length", lit("\uD83D\uDE00"))));
        assertEquals("HELLO", eval(call("upper", lit("hello"))));
        assertEquals("ab", eval(call("concat", lit("a"), nullOf(DataType.VARCHAR), lit("b"))));
        assertNull(eval(call("||", lit("a"), nullOf(DataType.VARCHAR))));
        assertEquals("ell", eval(call("substring", lit("hello"), lit(2), lit(3))));
        assertEquals("h", eval(call("substring", lit("hello"), lit(0), lit(2))));
        assertEquals("llo", eval(call("substring", lit("hello"), lit(3))));
        assertEquals("", eval(call("substring", lit("hello"), lit(9), lit(2))));
        assertEquals("a1", eval(call("concat", lit("a"), lit(1))));
        assertEquals(3.0, eval(call("round", lit(2.5))));
        assertEquals(-3.0, eval(call("round", lit(-2.5))));
        assertEquals(1.23, eval(call("round", lit(1.2345), lit(2))));
        assertEquals(new BigDecimal("3"), eval(call("round", lit(new BigDecimal("2.5")))));
        assertEquals(5, eval(call("abs", lit(-5))));
        assertThrows(ArithmeticException.class, () -> eval(call("abs", lit(Integer.MIN_VALUE))));
    }

    @Test
    void numericFunctions() {
        assertEquals(2.0, eval(call("ceil", lit(1.5))));
        assertEquals(1.0, eval(call("ceil", lit(1))), "ceil of an integer is a DOUBLE");
        assertEquals(new BigDecimal("2"), eval(call("ceil", lit(new BigDecimal("1.50")))));
        assertEquals(new BigDecimal("-1"), eval(call("ceil", lit(new BigDecimal("-1.50")))));
        assertEquals(-2.0, eval(call("floor", lit(-1.5))));
        assertEquals(1, eval(call("trunc", lit(1))), "trunc keeps an integer");
        assertEquals(-1.0, eval(call("trunc", lit(-1.9))));
        assertEquals(1.0f, eval(call("trunc", lit(1.9f))));
        assertEquals(new BigDecimal("-1"), eval(call("trunc", lit(new BigDecimal("-1.99")))));
        assertEquals(1300, eval(call("round", lit(1250), lit(-2))));
        assertEquals(1250, eval(call("round", lit(1250), lit(1))));
        assertEquals((byte) 100, eval(call("round", lit((byte) 127), lit(-2))));
        assertThrows(ArithmeticException.class, () -> eval(call("round", lit((byte) -128), lit(-1))));
        assertEquals(1200.0, eval(call("round", lit(1234.5), lit(-2))));
        assertEquals(2.0f, eval(call("round", lit(1.5f))));
        assertEquals((byte) -1, eval(call("sign", lit(-2.5))));
        assertEquals((byte) 0, eval(call("sign", lit(Double.NaN))));
        assertEquals((byte) 1, eval(call("sign", lit(new BigDecimal("0.01")))));
        assertEquals(2.0, eval(call("sqrt", lit(4))));
        assertEquals(-0.0, eval(call("sqrt", lit(-0.0))));
        assertThrows(ArithmeticException.class, () -> eval(call("sqrt", lit(-1))));
        assertThrows(ArithmeticException.class, () -> eval(call("ln", lit(0))));
        assertEquals(Double.NaN, eval(call("ln", lit(Double.NaN))));
        assertEquals(Double.POSITIVE_INFINITY, eval(call("ln", lit(Double.POSITIVE_INFINITY))));
        assertEquals(3.0, eval(call("log2", lit(8))));
        assertEquals(2.0, eval(call("log", lit(100))));
        assertEquals(Double.POSITIVE_INFINITY, eval(call("exp", lit(1000))));
        assertEquals(8.0, eval(call("power", lit(2), lit(3))));
        assertEquals(Double.POSITIVE_INFINITY, eval(call("pow", lit(0), lit(-1))));
        assertEquals(Math.PI, eval(call("pi")));
        assertEquals(new BigDecimal("2.5"), eval(call("greatest", lit(1), lit(new BigDecimal("2.5")))));
        assertEquals(1, eval(call("greatest", lit(1), nullOf(DataType.INTEGER))));
        assertNull(eval(call("least", nullOf(DataType.INTEGER), nullOf(DataType.INTEGER))));
        assertEquals("a", eval(call("greatest", lit("a"), lit("B"))));
        assertEquals(Double.NaN, eval(call("greatest", lit(Double.NaN), lit(1.0))));
        assertEquals(1.0, eval(call("least", lit(Double.NaN), lit(1.0))));
        assertEquals(-0.0, eval(call("greatest", lit(-0.0), lit(0.0))));
        assertEquals("INTEGER", eval(call("typeof", lit(1))));
        assertEquals("DECIMAL(2,1)", eval(call("typeof", nullOf(DataType.decimal(2, 1)))));
    }

    @Test
    void stringFunctions() {
        assertEquals("ello", eval(call("substring", lit("hello"), lit(2))));
        assertEquals("o", eval(call("substring", lit("hello"), lit(-1))));
        assertEquals("o", eval(call("substring", lit("hello"), lit(-1), lit(3))));
        assertEquals("hello", eval(call("substring", lit("hello"), lit(0))));
        assertEquals("h", eval(call("substr", lit("hello"), lit(2), lit(-1))));
        assertEquals("l", eval(call("substring", lit("hello"), lit(-2), lit(-1))));
        assertThrows(ArithmeticException.class, () -> eval(call("substring", lit("hello"), lit(1), lit(Long.MAX_VALUE))));
        assertEquals("a", eval(call("trim", lit(" a "))));
        assertEquals("\t\na\n", eval(call("trim", lit("\t\na\n"))), "trim without characters removes only spaces");
        assertEquals("a", eval(call("trim", lit("xyaxy"), lit("xy"))));
        assertEquals("ax", eval(call("ltrim", lit("xax"), lit("x"))));
        assertEquals("xa", eval(call("rtrim", lit("xax"), lit("x"))));
        assertEquals(" a ", eval(call("trim", lit(" a "), lit(""))));
        assertEquals("", eval(call("trim", lit("aa"), lit("a"))));
        assertEquals("ba", eval(call("replace", lit("aaa"), lit("aa"), lit("b"))));
        assertEquals("aaa", eval(call("replace", lit("aaa"), lit(""), lit("b"))));
        assertEquals(true, eval(call("contains", lit("abc"), lit(""))));
        assertEquals(true, eval(call("starts_with", lit("abc"), lit("ab"))));
        assertEquals(false, eval(call("suffix", lit("abc"), lit("ab"))));
        assertEquals(2L, eval(call("strpos", lit("\u00E9b"), lit("b"))));
        assertEquals(0L, eval(call("instr", lit("abc"), lit("x"))));
        assertEquals(1L, eval(call("strpos", lit(""), lit(""))));
        assertEquals("hel", eval(call("left", lit("hello"), lit(-2))));
        assertEquals("", eval(call("left", lit("hello"), lit(-10))));
        assertEquals("llo", eval(call("right", lit("hello"), lit(-2))));
        assertEquals("hello", eval(call("right", lit("hello"), lit(10))));
        assertEquals("h\u00E9", eval(call("left", lit("h\u00E9llo"), lit((byte) 2))));
        assertEquals("ababab", eval(call("repeat", lit("ab"), lit(3))));
        assertEquals("", eval(call("repeat", lit("ab"), lit(-1))));
        assertEquals("\uD834\uDD1E\u00E9a", eval(call("reverse", lit("a\u00E9\uD834\uDD1E"))));
        assertEquals("xyxab", eval(call("lpad", lit("ab"), lit(5), lit("xy"))));
        assertEquals("a", eval(call("lpad", lit("ab"), lit(1), lit("x"))));
        assertEquals("", eval(call("rpad", lit("ab"), lit(-1), lit("x"))));
        assertEquals("abxyx", eval(call("rpad", lit("ab"), lit(5), lit("xy"))));
        assertThrows(IllegalArgumentException.class, () -> eval(call("lpad", lit("ab"), lit(5), lit(""))));
        assertEquals("b", eval(call("split_part", lit("a,b,c"), lit(","), lit(2))));
        assertEquals("c", eval(call("split_part", lit("a,b,c"), lit(","), lit(-1))));
        assertEquals("", eval(call("split_part", lit("a,b,c"), lit(","), lit(0))));
        assertEquals("", eval(call("split_part", lit("a,b,c"), lit(","), lit(4))));
        assertEquals("b", eval(call("split_part", lit("abc"), lit(""), lit(2))));
        assertEquals("", eval(call("split_part", lit("a,,c"), lit(","), lit(2))));
        assertEquals("a,b", eval(call("concat_ws", lit(","), lit("a"), nullOf(DataType.VARCHAR), lit("b"))));
        assertNull(eval(call("concat_ws", nullOf(DataType.VARCHAR), lit("a"))));
        assertEquals("1,2.5", eval(call("concat_ws", lit(","), lit(1), lit(2.5))));
        assertEquals("12", eval(call("||", lit(1), lit(2))));
        assertEquals("\u1E9E", eval(call("upper", lit("\u00DF"))));
        assertEquals("\u00DF", eval(call("lower", lit("\u1E9E"))));
        assertEquals(3L, eval(call("len", lit("abc"))));
    }

    @Test
    void castsAndErrors() {
        assertEquals(2, eval(cast(lit(1.7), DataType.INTEGER)));
        assertThrows(ConversionException.class, () -> eval(cast(lit("x"), DataType.INTEGER)));
        assertEquals("1.5", eval(cast(lit(1.5), DataType.VARCHAR)));
        assertThrows(IllegalArgumentException.class, () -> cast(lit(1), DataType.listOf(DataType.INTEGER)));
        assertNull(eval(tryCast(lit("x"), DataType.INTEGER)));
        assertNull(eval(tryCast(lit(300), DataType.TINYINT)));
        assertEquals((byte) 3, eval(tryCast(lit("3"), DataType.TINYINT)));
    }

    @Test
    void ilikeAndIsDistinct() {
        assertEquals(true, eval(ilike(lit("Hello"), lit("h%O"))));
        assertEquals(false, eval(like(lit("Hello"), lit("h%O"))));
        assertEquals(false, eval(isDistinct(nullOf(DataType.INTEGER), nullOf(DataType.INTEGER))));
        assertEquals(true, eval(isDistinct(nullOf(DataType.INTEGER), lit(1))));
        assertEquals(true, eval(isDistinct(lit(1), lit(2L))));
        assertEquals(true, eval(isNotDistinct(lit(1), lit(1.0))));
        assertEquals(true, eval(isNotDistinct(nullOf(DataType.VARCHAR), nullOf(DataType.VARCHAR))));
    }

    @Test
    void vectorsWithNulls() {
        Schema schema = Schema.of(DataType.INTEGER, DataType.VARCHAR);
        Batch batch = Batch.ofRows(schema, List.of(new Object[] {1, "a"}, new Object[] {null, "b"},
                new Object[] {3, null}, new Object[] {Integer.MAX_VALUE, "d"}));
        Vector sum = Evaluator.eval(add(col(schema, 0), lit(1L)), batch);
        assertEquals(DataType.BIGINT, sum.type());
        assertEquals(Arrays.asList(2L, null, 4L, (long) Integer.MAX_VALUE + 1),
                Arrays.asList(sum.get(0), sum.get(1), sum.get(2), sum.get(3)));
        assertThrows(ArithmeticException.class, () -> Evaluator.eval(add(col(schema, 0), lit(1)), batch));
        Vector s = Evaluator.eval(call("concat", col(schema, 1), lit("!")), batch);
        assertEquals("!", s.get(2));
        Vector c = Evaluator.eval(gt(col(schema, 0), lit(2)), batch);
        assertTrue(c.isNull(1));
        assertEquals(true, c.get(2));
        // The error in the untaken branch of a CASE stays invisible.
        Vector safe = Evaluator.eval(Expressions.caseWhen(List.of(Expressions.when(
                Expressions.isNotNull(col(schema, 0)), add(col(schema, 0), lit(0)))), lit(-1)), batch);
        assertEquals(-1, safe.get(1));
        assertEquals(Integer.MAX_VALUE, safe.get(3));
    }

    @Test
    void aLiteralIsCastOnceAndFailsOnlyWithRows() {
        Schema s = Schema.of(DataType.decimal(9, 2));
        Batch rows = Batch.ofRows(s, List.of(new Object[] {new BigDecimal("0.25")}, new Object[] {null}));
        Vector v = Evaluator.eval(sub(lit(1), col(s, 0)), rows);
        assertEquals(Arrays.asList(new BigDecimal("0.75"), null), Arrays.asList(v.get(0), v.get(1)));
        Vector c = Evaluator.eval(cast(lit("12"), DataType.decimal(4, 1)), rows);
        assertEquals(Arrays.asList(new BigDecimal("12.0"), new BigDecimal("12.0")), Arrays.asList(c.get(0), c.get(1)));
        assertNull(Evaluator.eval(tryCast(lit("x"), DataType.INTEGER), rows).get(1));
        assertThrows(ConversionException.class, () -> Evaluator.eval(cast(lit("x"), DataType.INTEGER), rows));
        assertThrows(ConversionException.class, () -> Evaluator.eval(add(col(s, 0), cast(lit("x"),
                DataType.INTEGER)), rows));
        Batch none = Batch.empty(s);
        assertEquals(0, Evaluator.eval(cast(lit("x"), DataType.INTEGER), none).size());
        assertEquals(0, Evaluator.eval(add(col(s, 0), cast(lit("x"), DataType.INTEGER)), none).size());
    }
}
