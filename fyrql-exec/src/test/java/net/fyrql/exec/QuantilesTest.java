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

import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.Fraction;
import net.fyrql.core.plan.PlanNode.Fractions;
import net.fyrql.core.type.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The quantiles of DuckDB 1.5.6, as it gives them for these values. */
class QuantilesTest {

    private static final AggregateFunction CONT = AggregateFunction.QUANTILE_CONT;
    private static final AggregateFunction DISC = AggregateFunction.QUANTILE_DISC;

    private static Object quantile(AggregateFunction f, DataType type, Fractions fractions, Object... values) {
        Quantiles q = new Quantiles(type);
        for (Object v : values) {
            q.add(v);
        }
        return q.result(f, fractions);
    }

    private static Fractions decimal(String q) {
        BigDecimal d = new BigDecimal(q);
        return Fractions.of(new Fraction(d.unscaledValue().doubleValue() / Math.pow(10, d.scale()), d));
    }

    private static Fractions dbl(double q) {
        return Fractions.of(new Fraction(q, null));
    }

    @Test
    void continuousQuantilesInterpolateAsDuckDb() {
        assertEquals(19.0, quantile(CONT, DataType.INTEGER, decimal("0.3"), 10, 20, 30, 40));
        assertEquals(30.999999999999996, quantile(CONT, DataType.INTEGER, decimal("0.7"), 10, 20, 30, 40));
        assertEquals(2.25, quantile(CONT, DataType.DOUBLE, decimal("0.25"), 7.0, 1.5, 4.0, 2.5));
        assertEquals(3.25, quantile(CONT, DataType.DOUBLE, decimal("0.5"), 7.0, 1.5, 4.0, 2.5));
        assertEquals(4.75, quantile(CONT, DataType.DOUBLE, new Fractions(List.of(new Fraction(0.25,
                new BigDecimal("0.25"))), false, true), 7.0, 1.5, 4.0, 2.5));
        assertEquals(List.of(2.25, 3.25, 4.75), quantile(CONT, DataType.DOUBLE, new Fractions(List.of(
                new Fraction(0.25, null), new Fraction(0.5, null), new Fraction(0.75, null)), true, false),
                7.0, 1.5, 4.0, 2.5));
        assertEquals(Double.NaN, quantile(CONT, DataType.DOUBLE, decimal("0.5"), Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY));
        assertEquals(2.0, quantile(CONT, DataType.DOUBLE, decimal("0.5"), 1.0, Double.NaN, 2.0));
        assertEquals(9.223372036854776E18, quantile(CONT, DataType.BIGINT, decimal("0.5"), Long.MAX_VALUE,
                Long.MAX_VALUE - 1));
        assertEquals(-1.5, quantile(CONT, DataType.BIGINT, decimal("0.5"), -1L, -2L));
        assertEquals(8.999999999999999E-20, quantile(CONT, DataType.BIGINT, dbl(1e-20), 0L, 1L, 2L, 3L, 4L, 5L, 6L,
                7L, 8L, 9L));
        assertEquals(1.7014118346046923e38, quantile(CONT, DataType.HUGEINT, decimal("0.5"),
                BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), BigInteger.ONE.shiftLeft(127).subtract(
                        BigInteger.TWO)));
        assertEquals(2.25f, quantile(CONT, DataType.FLOAT, decimal("0.5"), 1.5f, 2.25f, 7.1f));
        assertEquals(1.71f, quantile(CONT, DataType.FLOAT, decimal("0.3"), 1.5f, 2.2f));
    }

    @Test
    void decimalsDatesAndTimestampsKeepTheirUnits() {
        DataType d40 = DataType.decimal(4, 0);
        assertEquals(new BigDecimal("1"), quantile(CONT, d40, decimal("0.5"), new BigDecimal("1"),
                new BigDecimal("2")));
        DataType d21 = DataType.decimal(2, 1);
        Object[] values = {new BigDecimal("1.0"), new BigDecimal("2.0"), new BigDecimal("4.0"), new BigDecimal("8.0")};
        assertEquals(new BigDecimal("1.9"), quantile(CONT, d21, decimal("0.333"), values));
        assertEquals(new BigDecimal("1.3"), quantile(CONT, d21, decimal("0.1"), values));
        assertEquals(new BigDecimal("-1.1"), quantile(CONT, DataType.decimal(4, 1), decimal("0.5"),
                new BigDecimal("-1.0"), new BigDecimal("-1.1")));
        assertEquals(new BigDecimal("99999999999999997748809823456034029568"), quantile(CONT, DataType.decimal(38, 0),
                decimal("0.5"), new BigDecimal("99999999999999999999999999999999999999"),
                new BigDecimal("99999999999999999999999999999999999997")));
        // 2026-01-01 and 2026-01-02 as days since 1970, their median the noon between as a timestamp.
        assertEquals(1_767_268_800_000_000L, quantile(CONT, DataType.DATE, decimal("0.5"), 20454, 20455));
        // Half microseconds round away from zero, also before 1970.
        assertEquals(2L, quantile(CONT, DataType.TIMESTAMP, decimal("0.5"), 0L, 3L));
        assertEquals(-315_619_199_999_999L, quantile(CONT, DataType.TIMESTAMP, decimal("0.5"), -315_619_200_000_000L,
                -315_619_199_999_997L));
        assertEquals(1L, quantile(CONT, DataType.TIME, decimal("0.5"), 0L, 1L));
    }

    @Test
    void discreteQuantilesTakeAValueAsDuckDb() {
        Object[] range = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L};
        assertEquals(0L, quantile(DISC, DataType.BIGINT, decimal("0.0"), range));
        assertEquals(2L, quantile(DISC, DataType.BIGINT, dbl(0.25), range));
        assertEquals(2L, quantile(DISC, DataType.BIGINT, dbl(0.3), range));
        assertEquals(2L, quantile(DISC, DataType.BIGINT, decimal("0.3"), range));
        assertEquals(0L, quantile(DISC, DataType.BIGINT, decimal("0.1"), range));
        assertEquals(6L, quantile(DISC, DataType.BIGINT, dbl(0.7), range));
        assertEquals(20, quantile(DISC, DataType.INTEGER, decimal("0.3"), 40, 30, 20, 10));
        assertEquals(30, quantile(DISC, DataType.INTEGER, decimal("0.7"), 40, 30, 20, 10));
        assertEquals(List.of(2L, 7L), quantile(DISC, DataType.BIGINT, new Fractions(List.of(new Fraction(0.75,
                new BigDecimal("0.75")), new Fraction(0.25, new BigDecimal("0.25"))), true, true), range));
        assertEquals("a", quantile(DISC, DataType.VARCHAR, decimal("0.5"), "b", "a"));
        assertEquals(7.0, quantile(DISC, DataType.DOUBLE, new Fractions(List.of(new Fraction(0.25,
                new BigDecimal("0.25"))), false, true), 7.0, 1.5, 4.0, 2.5));
        assertNull(quantile(DISC, DataType.BIGINT, decimal("0.5")));
    }
}
