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

import net.fyrql.core.expr.Expression.ArithmeticOp;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.TypeRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalarOpsTest {

    @Test
    void integralEdges() {
        assertEquals("Overflow in division of -2147483648 / -1", assertThrows(ArithmeticException.class,
                () -> ScalarOps.integral(ArithmeticOp.REMAINDER, Integer.MIN_VALUE, -1, DataType.INTEGER))
                .getMessage());
        assertEquals("Overflow in // of BIGINT", assertThrows(ArithmeticException.class,
                () -> ScalarOps.integral(ArithmeticOp.INT_DIVIDE, Long.MIN_VALUE, -1, DataType.BIGINT)).getMessage());
        assertEquals("Out of range for TINYINT: 200", assertThrows(ArithmeticException.class,
                () -> ScalarOps.integral(ArithmeticOp.ADD, 100, 100, DataType.TINYINT)).getMessage());
        assertEquals("Out of range for INTEGER: 2147483648", assertThrows(ArithmeticException.class,
                () -> ScalarOps.integral(ArithmeticOp.INT_DIVIDE, Integer.MIN_VALUE, -1, DataType.INTEGER))
                .getMessage());
        assertEquals(-1, ScalarOps.integral(ArithmeticOp.REMAINDER, -7, 3, DataType.INTEGER));
        assertEquals(-2, ScalarOps.integral(ArithmeticOp.INT_DIVIDE, -7, 3, DataType.SMALLINT));
        assertTrue(ScalarOps.nullDivisor(ArithmeticOp.INT_DIVIDE, 0L));
        assertTrue(ScalarOps.nullDivisor(ArithmeticOp.REMAINDER, 0L));
        assertFalse(ScalarOps.nullDivisor(ArithmeticOp.ADD, 0L));
        assertEquals((byte) 127, ScalarOps.arithmetic(ArithmeticOp.ADD, (byte) 100, (byte) 27, DataType.TINYINT,
                DataType.TINYINT));
        assertEquals(null, ScalarOps.arithmetic(ArithmeticOp.REMAINDER, 7L, 0L, DataType.BIGINT, DataType.BIGINT));
        assertEquals(-5L, ScalarOps.negate(5, DataType.BIGINT));
        assertEquals("Out of range for SMALLINT: 32768", assertThrows(ArithmeticException.class,
                () -> ScalarOps.negate(Short.MIN_VALUE, DataType.SMALLINT)).getMessage());
    }

    @Test
    void floatingEdges() {
        assertTrue(ScalarOps.nullDivisor(ArithmeticOp.INT_DIVIDE, 0.0));
        assertTrue(ScalarOps.nullDivisor(ArithmeticOp.INT_DIVIDE, -0.0));
        assertFalse(ScalarOps.nullDivisor(ArithmeticOp.REMAINDER, 0.0));
        assertTrue(Double.isNaN(ScalarOps.floating(ArithmeticOp.REMAINDER, 5, 0)));
        assertEquals(Double.POSITIVE_INFINITY, ScalarOps.floating(ArithmeticOp.DIVIDE, 1, 0));
        assertEquals(3.5, ScalarOps.floating(ArithmeticOp.INT_DIVIDE, 7, 2));
        assertEquals(null, ScalarOps.arithmetic(ArithmeticOp.INT_DIVIDE, 1.5f, 0.0f, DataType.FLOAT, DataType.FLOAT));
        assertEquals(0.1f + 0.2f, ScalarOps.arithmetic(ArithmeticOp.ADD, 0.1f, 0.2f, DataType.FLOAT,
                DataType.FLOAT));
    }

    @Test
    void shiftDate() {
        assertEquals(19_710, ScalarOps.shiftDate(ArithmeticOp.ADD, 19_700, 10));
        assertEquals(19_690, ScalarOps.shiftDate(ArithmeticOp.SUBTRACT, 19_700, 10));
        assertThrows(ArithmeticException.class, () -> ScalarOps.shiftDate(ArithmeticOp.ADD, 2_147_483_646L, 1));
    }

    /**
     * The decimal kernel on unscaled longs gives the same values, the same
     * {@code NULL}s and the same errors as the arithmetic on
     * {@code BigDecimal}s, for pairs of types whose result has at most 18
     * digits.
     */
    @Test
    void decimalKernelAgreesWithBigDecimals() {
        SplittableRandom random = new SplittableRandom(20261014);
        List<Decimal[]> pairs = List.of(
                new Decimal[] {DataType.decimal(5, 2), DataType.decimal(5, 2)},
                new Decimal[] {DataType.decimal(18, 2), DataType.decimal(18, 2)},
                new Decimal[] {DataType.decimal(18, 0), DataType.decimal(18, 0)},
                new Decimal[] {DataType.decimal(9, 2), DataType.decimal(9, 3)},
                new Decimal[] {DataType.decimal(10, 3), DataType.decimal(10, 3)},
                new Decimal[] {DataType.decimal(18, 4), DataType.decimal(18, 4)},
                new Decimal[] {DataType.decimal(1, 1), DataType.decimal(1, 0)});
        int checked = 0;
        for (Decimal[] pair : pairs) {
            for (ArithmeticOp op : List.of(ArithmeticOp.ADD, ArithmeticOp.SUBTRACT, ArithmeticOp.MULTIPLY,
                    ArithmeticOp.REMAINDER)) {
                DataType t = switch (op) {
                    case ADD -> TypeRules.add(pair[0], pair[1]);
                    case SUBTRACT -> TypeRules.subtract(pair[0], pair[1]);
                    case MULTIPLY -> TypeRules.multiply(pair[0], pair[1]);
                    default -> TypeRules.remainder(pair[0], pair[1]);
                };
                if (!(t instanceof Decimal rt) || !rt.fitsLong()) {
                    continue;
                }
                boolean scalesFit = op == ArithmeticOp.MULTIPLY ? pair[0].scale() + pair[1].scale() == rt.scale()
                        : pair[0].scale() == rt.scale() && pair[1].scale() == rt.scale();
                if (!scalesFit) {
                    continue;
                }
                for (long x : samples(pair[0], random)) {
                    for (long y : samples(pair[1], random)) {
                        check(op, x, pair[0], y, pair[1], rt);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 1000, "only " + checked + " checked");
    }

    private static void check(ArithmeticOp op, long x, Decimal xt, long y, Decimal yt, Decimal rt) {
        String what = BigDecimal.valueOf(x, xt.scale()) + " " + op.symbol() + " " + BigDecimal.valueOf(y, yt.scale())
                + " as " + rt;
        Object expected;
        try {
            expected = ScalarOps.arithmetic(op, BigDecimal.valueOf(x, xt.scale()), BigDecimal.valueOf(y, yt.scale()),
                    rt, rt);
        } catch (ArithmeticException e) {
            expected = e.getMessage();
        }
        Object actual;
        if (ScalarOps.nullDivisor(op, y)) {
            actual = null;
        } else {
            try {
                actual = BigDecimal.valueOf(ScalarOps.decimal(op, x, y, rt), rt.scale());
            } catch (ArithmeticException e) {
                actual = e.getMessage();
            }
        }
        assertEquals(expected, actual, what);
    }

    private static List<Long> samples(Decimal type, SplittableRandom random) {
        long max = type.maxUnscaled().longValueExact();
        List<Long> values = new ArrayList<>(List.of(0L, 1L, -1L, 7L, max, -max, max / 2, -max / 2, max - 1));
        for (int i = 0; i < 12; i++) {
            values.add(random.nextLong(-max, max + 1));
        }
        return values;
    }
}
