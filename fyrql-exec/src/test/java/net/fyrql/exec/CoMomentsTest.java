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
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoMomentsTest {

    private static double[] state(double[] ys, double[] xs) {
        double[] state = new double[CoMoments.SIZE];
        for (int i = 0; i < ys.length; i++) {
            CoMoments.add(state, 0, i + 1, ys[i], xs[i]);
        }
        return state;
    }

    private static Object result(AggregateFunction function, double[] ys, double[] xs) {
        return CoMoments.value(function, state(ys, xs), 0, ys.length);
    }

    private static double[] of(double... values) {
        return values;
    }

    @Test
    void theResultsAreThoseOfDuckDb() {
        double[] ys = of(2.0, 3.5, 1.0);
        double[] xs = of(1.5, 2.5, 7.0);
        assertEquals(-0.689452620335347, result(AggregateFunction.CORR, ys, xs));
        assertEquals(-1.694444444444444, result(AggregateFunction.COVAR_POP, ys, xs));
        assertEquals(-2.541666666666666, result(AggregateFunction.COVAR_SAMP, ys, xs));
        assertEquals(3L, result(AggregateFunction.REGR_COUNT, ys, xs));
        assertEquals(-0.2961165048543689, result(AggregateFunction.REGR_SLOPE, ys, xs));
        assertEquals(3.2524271844660193, result(AggregateFunction.REGR_INTERCEPT, ys, xs));
        assertEquals(0.4753449156872762, result(AggregateFunction.REGR_R2, ys, xs));
        assertEquals(3.6666666666666665, result(AggregateFunction.REGR_AVGX, ys, xs));
        assertEquals(2.1666666666666665, result(AggregateFunction.REGR_AVGY, ys, xs));
        assertEquals(17.166666666666664, result(AggregateFunction.REGR_SXX, ys, xs));
        assertEquals(3.166666666666667, result(AggregateFunction.REGR_SYY, ys, xs));
        assertEquals(-5.083333333333332, result(AggregateFunction.REGR_SXY, ys, xs));
        double[] is = of(1, 2, 3, 2, 5);
        double[] bs = of(10, 20, 30, 40, 50);
        assertEquals(0.834057656228299, result(AggregateFunction.CORR, is, bs));
        assertEquals(16.0, result(AggregateFunction.COVAR_POP, is, bs));
        assertEquals(30.0, result(AggregateFunction.REGR_AVGX, is, bs));
        assertEquals(0.08, result(AggregateFunction.REGR_SLOPE, is, bs));
        double[] range = new double[1000];
        double[] squares = new double[1000];
        double[] doubled = new double[1000];
        for (int i = 0; i < range.length; i++) {
            range[i] = i;
            squares[i] = (double) i * i;
            doubled[i] = 2.0 * i;
        }
        assertEquals(0.9681853572330257, result(AggregateFunction.CORR, range, squares));
        assertEquals(166833.33333333334, result(AggregateFunction.COVAR_SAMP, range, doubled));
    }

    @Test
    void edgeCasesAreThoseOfDuckDb() {
        double[] none = of();
        for (AggregateFunction f : AggregateFunction.values()) {
            if (f.isCovariance()) {
                assertEquals(f == AggregateFunction.REGR_COUNT ? 0L : null, result(f, none, none), f.name());
            }
        }
        double[] y = of(2.0);
        double[] x = of(1.5);
        assertEquals(Double.NaN, result(AggregateFunction.CORR, y, x));
        assertEquals(0.0, result(AggregateFunction.COVAR_POP, y, x));
        assertNull(result(AggregateFunction.COVAR_SAMP, y, x));
        assertEquals(Double.NaN, result(AggregateFunction.REGR_SLOPE, y, x));
        assertNull(result(AggregateFunction.REGR_INTERCEPT, y, x));
        assertNull(result(AggregateFunction.REGR_R2, y, x));
        assertEquals(0.0, result(AggregateFunction.REGR_SXX, y, x));
        double[] xs = of(1.5, 2.5, 4.0, 7.0);
        double[] ones = of(1.0, 1.0, 1.0, 1.0);
        assertEquals(1.0000000000000002, result(AggregateFunction.CORR, xs, xs));
        assertEquals(1.0000000000000004, result(AggregateFunction.REGR_R2, xs, xs));
        assertEquals(1.0, result(AggregateFunction.REGR_SLOPE, xs, xs));
        assertEquals(Double.NaN, result(AggregateFunction.CORR, ones, xs));
        assertEquals(1.0, result(AggregateFunction.REGR_R2, ones, xs));
        assertNull(result(AggregateFunction.REGR_R2, xs, ones));
        assertEquals(Double.NaN, result(AggregateFunction.REGR_SLOPE, xs, ones));
        assertEquals(Double.NaN, result(AggregateFunction.CORR, of(1e300, 1e300, 1e300), of(1e300, 1e300, 1e300)));
        assertEquals(0.0, result(AggregateFunction.COVAR_POP, of(1e308, 1e308, 1e308), of(1e308, 1e308, 1e308)));
    }

    @Test
    void aVarianceThatIsNotFiniteIsAnError() {
        double[] big = of(1e200, 2e200);
        double[] small = of(1, 2);
        assertEquals("STDDEV_POP for X is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.CORR, small, big)).getMessage());
        assertEquals("STDDEV_POP for Y is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.CORR, big, small)).getMessage());
        assertEquals("VARPOP is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.REGR_SXX, small, big)).getMessage());
        assertEquals("VARPOP(Y) is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.REGR_R2, big, small)).getMessage());
    }

    @Test
    void combinedStatesAreThoseOfAllPairsButForTheLastBits() {
        SplittableRandom random = new SplittableRandom(20261029);
        for (int round = 0; round < 1000; round++) {
            int n = 2 + random.nextInt(200);
            double[] ys = new double[n];
            double[] xs = new double[n];
            double scale = Math.pow(10, random.nextInt(-3, 9));
            for (int i = 0; i < n; i++) {
                xs[i] = random.nextDouble() * scale;
                ys[i] = 3 * xs[i] + random.nextDouble() * scale;
            }
            double[] all = state(ys, xs);
            // The pairs in up to four parts, each combined into the state of those before.
            double[] combined = new double[CoMoments.SIZE];
            long count = 0;
            int from = 0;
            while (from < n) {
                int to = random.nextInt(4) == 0 ? n : Math.min(n, from + 1 + random.nextInt(n));
                double[] part = new double[CoMoments.SIZE];
                for (int i = from; i < to; i++) {
                    CoMoments.add(part, 0, i - from + 1, ys[i], xs[i]);
                }
                CoMoments.combine(combined, 0, count, part, 0, to - from);
                count += to - from;
                from = to;
            }
            String what = "round " + round + ", " + n + " pairs";
            for (AggregateFunction f : AggregateFunction.values()) {
                if (f.isCovariance() && f != AggregateFunction.REGR_COUNT) {
                    double expected = (Double) CoMoments.value(f, all, 0, n);
                    double actual = (Double) CoMoments.value(f, combined, 0, count);
                    assertEquals(expected, actual, Math.abs(expected) * 1e-9, what + ", " + f);
                }
            }
        }
    }
}
