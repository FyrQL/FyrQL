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

package net.fyrql.codegen;

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.Keys;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.exec.spi.CompiledFilter;
import net.fyrql.exec.spi.CompiledProjection;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.KeyFunctions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.coalesce;
import static net.fyrql.core.expr.Expressions.div;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.iff;
import static net.fyrql.core.expr.Expressions.ilike;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.intDiv;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNotDistinct;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.le;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.neg;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.rem;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.tryCast;
import static net.fyrql.core.expr.Expressions.when;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaninoCompilerTest {

    static final Schema T = Schema.of(new Schema.Field("b", DataType.BOOLEAN), new Schema.Field("t", DataType.TINYINT),
            new Schema.Field("s", DataType.SMALLINT), new Schema.Field("i", DataType.INTEGER),
            new Schema.Field("l", DataType.BIGINT), new Schema.Field("h", DataType.HUGEINT),
            new Schema.Field("f", DataType.FLOAT), new Schema.Field("d", DataType.DOUBLE),
            new Schema.Field("m", DataType.decimal(9, 2)), new Schema.Field("w", DataType.decimal(30, 4)),
            new Schema.Field("v", DataType.VARCHAR), new Schema.Field("dt", DataType.DATE),
            new Schema.Field("ts", DataType.TIMESTAMP), new Schema.Field("x", DataType.BLOB));

    static Expression c(String name) {
        int i = T.indexOf(name);
        return net.fyrql.core.expr.Expressions.col(T, i);
    }

    /** Expressions with every kind of node over every flat type. */
    static List<Expression> expressions() {
        return List.of(
                add(c("i"), lit(1)), mul(c("l"), c("l")), sub(c("t"), c("s")), intDiv(c("i"), c("t")),
                rem(c("l"), c("i")), div(c("i"), c("d")), add(c("f"), c("f")), mul(c("m"), c("m")),
                add(c("m"), c("m")), rem(c("m"), lit(new BigDecimal("0.07"))), add(c("w"), c("m")),
                mul(c("h"), c("h")), sub(c("h"), c("l")), intDiv(c("d"), c("f")), rem(c("f"), lit(0.0f)),
                neg(c("i")), neg(c("t")), neg(c("m")), neg(c("d")), neg(c("h")), neg(c("w")),
                add(c("dt"), c("t")), sub(c("dt"), c("dt")), sub(c("dt"), lit(30)),
                lt(c("i"), c("d")), eq(c("v"), lit("a")), gt(c("v"), c("v")), le(c("m"), c("i")), eq(c("h"), c("l")),
                ge(c("dt"), c("ts")), ne(c("b"), lit(true)), lt(c("x"), c("x")), eq(c("w"), c("m")),
                and(gt(c("i"), lit(0)), lt(c("l"), lit(10L))), or(isNull(c("v")), eq(c("b"), lit(true))),
                not(c("b")), and(c("b"), gt(div(lit(1), c("i")), lit(0.5))),
                between(c("d"), lit(-1.0), c("i")), between(c("v"), lit("a"), lit("m")),
                in(c("i"), lit(1), lit(2), nullOf(DataType.INTEGER)), in(c("v"), lit("a"), lit("")),
                in(c("m"), c("i"), lit(new BigDecimal("1.5"))),
                caseWhen(List.of(when(gt(c("i"), lit(0)), c("m")), when(c("b"), lit(new BigDecimal("2.25")))),
                        neg(c("m"))),
                iff(c("b"), c("i"), c("l")), coalesce(c("i"), c("l"), lit(7L)), coalesce(c("v"), lit("none")),
                cast(c("v"), DataType.INTEGER), tryCast(c("v"), DataType.INTEGER), cast(c("i"), DataType.VARCHAR),
                cast(c("d"), DataType.decimal(9, 2)), tryCast(c("d"), DataType.TINYINT),
                cast(c("t"), DataType.BIGINT), cast(c("i"), DataType.DOUBLE), cast(c("f"), DataType.DOUBLE),
                cast(c("m"), DataType.decimal(12, 2)), cast(c("dt"), DataType.TIMESTAMP),
                tryCast(c("ts"), DataType.DATE), like(c("v"), lit("a%")), ilike(c("v"), lit("%B%")),
                isDistinct(c("i"), c("l")), isNotDistinct(c("v"), lit("a")), isDistinct(c("d"), lit(Double.NaN)),
                call("abs", c("i")), call("length", c("v")), call("round", c("d"), lit(2)), call("upper", c("v")),
                call("substring", c("v"), lit(2), lit(3)), call("typeof", c("w")), call("concat", c("v"), c("i")),
                call("year", c("dt")), call("greatest", c("i"), c("t")), call("ln", c("d")),
                lit(DataType.HUGEINT, null), lit(new BigDecimal("12.50")), nullOf(DataType.decimal(9, 2)),
                // Literals cast once when the code is generated, and casts of literals that fail for every row.
                sub(lit(1), c("m")), mul(c("w"), sub(lit(1), c("m"))), lt(c("m"), lit(24)), add(c("w"), lit(1)),
                cast(lit("12"), DataType.INTEGER), cast(lit("x"), DataType.INTEGER), tryCast(lit("x"),
                DataType.INTEGER), cast(lit(new BigDecimal("1.25")), DataType.decimal(30, 4)),
                in(c("m"), lit(1), lit(2L)), eq(c("v"), cast(lit(7), DataType.VARCHAR)));
    }

    static List<Batch> batches(long seed, int batchSize) {
        List<Object[]> rows = DataGenerator.withSeed(seed).generate(T, 300);
        List<Batch> batches = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += batchSize) {
            batches.add(Batch.ofRows(T, rows.subList(from, Math.min(rows.size(), from + batchSize))));
        }
        return batches;
    }

    /** The value of a row in a form that tells every difference: doubles by their bits, other values canonical. */
    static Object comparable(Vector v, int row) {
        Object value = v.get(row);
        if (value instanceof Double d) {
            return Double.doubleToLongBits(d);
        }
        if (value instanceof Float f) {
            return Float.floatToIntBits(f);
        }
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        return Keys.canonical(value);
    }

    /** Evaluates with both and fails on a difference; an error counts as the same when both fail alike. */
    static String outcome(Expression e, Batch b, boolean compiled, CompiledProjection p) {
        try {
            Vector v = compiled ? p.evaluate(b) : Evaluator.eval(e, b);
            assertEquals(e.type(), v.type());
            assertEquals(b.rowCount(), v.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.size(); i++) {
                sb.append(v.isNull(i) ? "NULL" : comparable(v, i)).append('|');
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return "error " + ex.getClass().getSimpleName();
        }
    }

    @Test
    void projectionsAgreeWithTheInterpreter() {
        JaninoCompiler compiler = new JaninoCompiler();
        int checked = 0;
        int values = 0;
        for (Expression e : expressions()) {
            CompiledProjection p = compiler.projection(e, T);
            assertNotNull(p, () -> "not compiled: " + e);
            for (int batchSize : new int[] {1, 7, 64}) {
                for (Batch b : batches(e.toString().hashCode(), batchSize)) {
                    String expected = outcome(e, b, false, null);
                    assertEquals(expected, outcome(e, b, true, p), () -> e + "\n" + p.source());
                    checked++;
                    values += expected.startsWith("error") ? 0 : 1;
                }
            }
        }
        assertTrue(values > checked / 2, values + " of " + checked + " batches without an error");
        assertEquals(0, compiler.statistics().failures());
    }

    @Test
    void literalsAreCastWhenTheCodeIsGenerated() {
        JaninoCompiler compiler = new JaninoCompiler();
        assertFalse(compiler.projection(sub(lit(1), c("m")), T).source().contains("Casts.cast("));
        assertFalse(compiler.filter(lt(c("m"), lit(24)), T).source().contains("Casts.cast("));
        // A cast that fails stays in the code and fails for the rows that reach it.
        assertTrue(compiler.projection(cast(lit("x"), DataType.INTEGER), T).source().contains("Casts.cast("));
    }

    @Test
    void filtersAgreeWithTheInterpreter() {
        JaninoCompiler compiler = new JaninoCompiler();
        for (Expression e : expressions()) {
            if (e.type() != DataType.BOOLEAN) {
                continue;
            }
            for (Expression condition : List.of(e, and(e, gt(c("i"), lit(0))), or(e, between(c("l"), lit(0L),
                    div(lit(1), c("i")))), and(or(e, c("b")), between(c("d"), lit(0), lit(1.5))))) {
                CompiledFilter f = compiler.filter(condition, T);
                assertNotNull(f);
                for (Batch b : batches(condition.toString().hashCode(), 7)) {
                    String expected;
                    String actual;
                    try {
                        expected = Arrays.toString(Evaluator.select(condition, b));
                    } catch (RuntimeException ex) {
                        expected = "error " + ex.getClass().getSimpleName();
                    }
                    try {
                        actual = Arrays.toString(f.select(b));
                    } catch (RuntimeException ex) {
                        actual = "error " + ex.getClass().getSimpleName();
                    }
                    assertEquals(expected, actual, () -> condition + "\n" + f.source());
                }
            }
        }
        assertEquals(0, compiler.statistics().failures());
    }

    @Test
    void keysHashAndCompareAsTheInterpreter() {
        JaninoCompiler compiler = new JaninoCompiler();
        for (int k = 0; k < T.size(); k++) {
            for (int[] columns : new int[][] {{k}, {k, (k + 3) % T.size()}}) {
                List<DataType> types = Arrays.stream(columns).mapToObj(T::type).toList();
                KeyFunctions keys = compiler.keys(types);
                assertNotNull(keys, types::toString);
                for (Batch b : batches(k, 50)) {
                    int[] expected = new int[b.rowCount()];
                    int[] actual = new int[b.rowCount()];
                    InterpretedKeys.INSTANCE.hash(b, columns, expected);
                    keys.hash(b, columns, actual);
                    assertArrayEquals(expected, actual, types::toString);
                    for (int x = 0; x < b.rowCount(); x++) {
                        for (int y = 0; y < b.rowCount(); y++) {
                            assertEquals(InterpretedKeys.INSTANCE.equal(b, x, columns, b, y, columns),
                                    keys.equal(b, x, columns, b, y, columns));
                        }
                    }
                }
            }
        }
    }

    @Test
    void cacheServesOtherLiteralsAndUnsupportedExpressionsAreDeclined() {
        JaninoCompiler compiler = new JaninoCompiler();
        CompiledProjection one = compiler.projection(add(c("i"), lit(1)), T);
        CompiledProjection two = compiler.projection(add(c("i"), lit(2)), T);
        assertEquals(one.source(), two.source());
        Batch b = Batch.ofRows(T, DataGenerator.withSeed(1).generate(T, 1));
        Object i = b.column(3).get(0);
        if (i != null && (Integer) i < 1000) {
            assertEquals((Integer) i + 2, two.evaluate(b).get(0));
        }
        CodegenStatistics s = compiler.statistics();
        assertEquals(1, s.compiled());
        assertEquals(1, s.cacheHits());

        Schema nested = Schema.of(new Schema.Field("l", DataType.listOf(DataType.INTEGER)));
        assertNull(compiler.projection(isNull(net.fyrql.core.expr.Expressions.col(nested, 0)), nested));
        Expression big = c("i");
        for (int n = 0; n < JaninoCompiler.MAX_NODES; n++) {
            big = add(big, lit(1));
        }
        assertNull(compiler.projection(big, T));
        assertEquals(2, compiler.statistics().declined());
    }
}
