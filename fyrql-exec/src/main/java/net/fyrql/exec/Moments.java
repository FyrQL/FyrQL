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

/**
 * The moments behind {@code stddev_samp}, {@code stddev_pop},
 * {@code var_samp} and {@code var_pop}, computed as DuckDB 1.5.6 computes
 * them, so that the results of one thread are the same to the bit: per
 * group the mean of the values and the sum of their squared differences
 * from it, updated value by value after Welford and combined after Chan.
 * Unlike DuckDB, two equal means combine to that mean exactly, so that
 * equal values have a sum of exactly 0 however threads split them.
 * The moments of a group are two doubles in an array, the mean at
 * {@code 2 * group} and the sum at {@code 2 * group + 1}; the count is
 * kept apart.
 */
final class Moments {

    private Moments() {
    }

    /**
     * Adds a value to the moments of a group.
     *
     * @param n the count of the values of the group with this one
     */
    static void add(double[] moments, int group, long n, double x) {
        int i = 2 * group;
        double mean = moments[i];
        double newMean = mean + (x - mean) / n;
        moments[i + 1] += (x - newMean) * (x - mean);
        moments[i] = newMean;
    }

    /**
     * Adds the moments of a group of other values to those of a group; the
     * caller adds the counts.
     *
     * @param n the count of the values of the group
     * @param otherN the count of the other values
     */
    static void combine(double[] moments, int group, long n, double[] other, int otherGroup, long otherN) {
        int i = 2 * group;
        int j = 2 * otherGroup;
        if (otherN == 0) {
            return;
        }
        if (n == 0) {
            moments[i] = other[j];
            moments[i + 1] = other[j + 1];
            return;
        }
        double target = n;
        double source = otherN;
        double total = n + otherN;
        double delta = other[j] - moments[i];
        // DuckDB's mean of two equal means is not always that mean again; then the next combination finds a difference.
        double mean = delta == 0 ? moments[i] : (source * other[j] + target * moments[i]) / total;
        moments[i + 1] = other[j + 1] + moments[i + 1] + delta * delta * source * target / total;
        moments[i] = mean;
    }

    /** Tells whether the result over a count of values is {@code NULL}: over none, or over one for a sample. */
    static boolean isNull(AggregateFunction function, long n) {
        return n == 0
                || n == 1 && (function == AggregateFunction.STDDEV_SAMP || function == AggregateFunction.VAR_SAMP);
    }

    /**
     * Returns the result over a count of values that is not {@link #isNull}.
     *
     * @param m2 the sum of the squared differences from the mean
     * @throws ArithmeticException if the result is not finite
     */
    static double value(AggregateFunction function, long n, double m2) {
        double result = switch (function) {
            case VAR_SAMP -> m2 / (n - 1);
            case VAR_POP -> n > 1 ? m2 / n : 0;
            case STDDEV_SAMP -> Math.sqrt(m2 / (n - 1));
            case STDDEV_POP -> n > 1 ? Math.sqrt(m2 / n) : 0;
            default -> throw new IllegalArgumentException("No moments for " + function);
        };
        if (!Double.isFinite(result)) {
            throw new ArithmeticException(switch (function) {
                case VAR_SAMP -> "VARSAMP";
                case VAR_POP -> "VARPOP";
                default -> function.name();
            } + " is out of range!");
        }
        return result;
    }
}
