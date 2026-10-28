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

/** The standard deviations and variances give the doubles of DuckDB 1.5.6 and its errors. */
class MomentsTest {

    private static double[] moments(double... values) {
        double[] m = new double[2];
        for (int i = 0; i < values.length; i++) {
            Moments.add(m, 0, i + 1, values[i]);
        }
        return m;
    }

    private static Double result(AggregateFunction function, double... values) {
        return Moments.isNull(function, values.length) ? null
                : Moments.value(function, values.length, moments(values)[1]);
    }

    @Test
    void theResultsAreThoseOfDuckDb() {
        assertEquals(1.5275252316519465, result(AggregateFunction.STDDEV_SAMP, 1, 2, 4));
        assertEquals(1.5555555555555554, result(AggregateFunction.VAR_POP, 1, 2, 4));
        assertEquals(0.09999999999999998, result(AggregateFunction.STDDEV_SAMP, 0.1, 0.2, 0.3));
        double[] range = new double[1_000_000];
        for (int i = 0; i < range.length; i++) {
            range[i] = i;
        }
        assertEquals(8.333341666666666E10, result(AggregateFunction.VAR_SAMP, range));
    }

    @Test
    void fewValuesGiveNullOrZero() {
        for (AggregateFunction f : new AggregateFunction[] {AggregateFunction.STDDEV_SAMP, AggregateFunction.STDDEV_POP,
                AggregateFunction.VAR_SAMP, AggregateFunction.VAR_POP}) {
            assertNull(result(f), f.name());
            boolean sample = f == AggregateFunction.STDDEV_SAMP || f == AggregateFunction.VAR_SAMP;
            assertEquals(sample ? null : 0.0, result(f, 7.5), f.name());
            // Equal values differ by nothing, exactly.
            assertEquals(0.0, result(f, 0.1, 0.1, 0.1, 0.1), f.name());
            assertEquals(0.0, result(f, 1e300, 1e300), f.name());
        }
        assertEquals(0.5, result(AggregateFunction.VAR_SAMP, 1, 2));
        assertEquals(0.5, result(AggregateFunction.STDDEV_POP, 1, 2));
    }

    @Test
    void aResultThatIsNotFiniteIsAnError() {
        assertEquals("VARPOP is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.VAR_POP, 1, Double.NaN)).getMessage());
        assertEquals("VARSAMP is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.VAR_SAMP, 1, Double.POSITIVE_INFINITY)).getMessage());
        assertEquals("STDDEV_POP is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.STDDEV_POP, 1e200, 2e200)).getMessage());
        assertEquals("STDDEV_SAMP is out of range!", assertThrows(ArithmeticException.class,
                () -> result(AggregateFunction.STDDEV_SAMP, 1e200, 2e200)).getMessage());
    }

    @Test
    void combinedMomentsAreThoseOfAllValuesButForTheLastBits() {
        SplittableRandom random = new SplittableRandom(20261026);
        for (int round = 0; round < 1000; round++) {
            int n = random.nextInt(200);
            double[] values = new double[n];
            double scale = Math.pow(10, random.nextInt(-3, 12));
            double offset = random.nextBoolean() ? 0 : scale * 100;
            for (int i = 0; i < n; i++) {
                values[i] = offset + random.nextDouble() * scale;
            }
            double[] all = moments(values);
            // The values in up to four parts, each combined into the moments of those before.
            double[] combined = new double[2];
            long count = 0;
            int from = 0;
            while (from < n) {
                int to = random.nextInt(4) == 0 ? n : Math.min(n, from + 1 + random.nextInt(n));
                double[] part = new double[2];
                for (int i = from; i < to; i++) {
                    Moments.add(part, 0, i - from + 1, values[i]);
                }
                Moments.combine(combined, 0, count, part, 0, to - from);
                count += to - from;
                from = to;
            }
            String what = "round " + round + ", " + n + " values";
            assertEquals(all[0], combined[0], Math.abs(all[0]) * 1e-12, what);
            assertEquals(all[1], combined[1], Math.abs(all[1]) * 1e-9, what);
        }
    }

    @Test
    void equalValuesCombineToExactlyZero() {
        SplittableRandom random = new SplittableRandom(4711);
        for (double x : new double[] {5.86, 0.1, 1e300, -3.7, 1.0 / 3}) {
            for (int round = 0; round < 200; round++) {
                // Parts of random sizes, each combined into the moments of those before, as threads hand them in.
                double[] combined = new double[2];
                long count = 0;
                int parts = 1 + random.nextInt(12);
                for (int p = 0; p < parts; p++) {
                    int size = 1 + random.nextInt(50);
                    double[] part = new double[2];
                    for (int i = 0; i < size; i++) {
                        Moments.add(part, 0, i + 1, x);
                    }
                    Moments.combine(combined, 0, count, part, 0, size);
                    count += size;
                }
                String what = x + ", round " + round + ", " + parts + " parts";
                assertEquals(x, combined[0], what);
                assertEquals(0.0, combined[1], what);
            }
        }
    }
}
