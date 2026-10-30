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
 * The state behind {@code corr}, {@code covar_pop}, {@code covar_samp} and
 * the {@code regr_} functions over pairs (y, x), computed as DuckDB 1.5.6
 * computes them, so that the results of one thread are the same to the
 * bit. Each group has {@value #SIZE} doubles in an array, from
 * {@code SIZE * group} on: the means of x and y and their co-moment after
 * Schubert and Gertz, updated pair by pair as DuckDB's covariance does; the
 * mean and the sum of squared differences of x and of y after Welford, as
 * its standard deviation does; and the sums of x and y, as its averages do.
 * The means of the co-moment and of Welford are the same until two states
 * combine, each after the formula of its own state in DuckDB. The count of
 * the pairs is kept apart. DuckDB keeps only the parts each function needs;
 * the other parts do not change the result.
 */
final class CoMoments {

    /** The doubles of a group. */
    static final int SIZE = 9;

    private static final int MEAN_X = 0;
    private static final int MEAN_Y = 1;
    private static final int CO_MOMENT = 2;
    private static final int WELFORD_X = 3;
    private static final int WELFORD_Y = 5;
    private static final int SUM_X = 7;
    private static final int SUM_Y = 8;

    private CoMoments() {
    }

    /**
     * Adds a pair to the state of a group.
     *
     * @param n the count of the pairs of the group with this one
     */
    static void add(double[] state, int group, long n, double y, double x) {
        int i = SIZE * group;
        double count = n;
        double dx = x - state[i + MEAN_X];
        double meanX = state[i + MEAN_X] + dx / count;
        double dy = y - state[i + MEAN_Y];
        double meanY = state[i + MEAN_Y] + dy / count;
        state[i + CO_MOMENT] = state[i + CO_MOMENT] + dx * (y - meanY);
        state[i + MEAN_X] = meanX;
        state[i + MEAN_Y] = meanY;
        welford(state, i + WELFORD_X, n, x);
        welford(state, i + WELFORD_Y, n, y);
        state[i + SUM_X] += x;
        state[i + SUM_Y] += y;
    }

    private static void welford(double[] state, int i, long n, double x) {
        double mean = state[i];
        double newMean = mean + (x - mean) / n;
        state[i + 1] = state[i + 1] + (x - newMean) * (x - mean);
        state[i] = newMean;
    }

    /**
     * Adds the state of a group of other pairs to the state of a group; the
     * caller adds the counts.
     *
     * @param n the count of the pairs of the group
     * @param otherN the count of the other pairs
     */
    static void combine(double[] state, int group, long n, double[] other, int otherGroup, long otherN) {
        int i = SIZE * group;
        int j = SIZE * otherGroup;
        if (otherN == 0) {
            return;
        }
        if (n == 0) {
            System.arraycopy(other, j, state, i, SIZE);
            return;
        }
        double target = n;
        double source = otherN;
        double total = n + otherN;
        double meanX = (source * other[j + MEAN_X] + target * state[i + MEAN_X]) / total;
        double meanY = (source * other[j + MEAN_Y] + target * state[i + MEAN_Y]) / total;
        double deltaX = state[i + MEAN_X] - other[j + MEAN_X];
        double deltaY = state[i + MEAN_Y] - other[j + MEAN_Y];
        state[i + CO_MOMENT] = other[j + CO_MOMENT] + state[i + CO_MOMENT]
                + deltaX * deltaY * source * target / total;
        state[i + MEAN_X] = meanX;
        state[i + MEAN_Y] = meanY;
        combineWelford(state, i + WELFORD_X, other, j + WELFORD_X, source, target, total);
        combineWelford(state, i + WELFORD_Y, other, j + WELFORD_Y, source, target, total);
        state[i + SUM_X] += other[j + SUM_X];
        state[i + SUM_Y] += other[j + SUM_Y];
    }

    private static void combineWelford(double[] state, int i, double[] other, int j, double source, double target,
            double total) {
        double delta = other[j] - state[i];
        double mean = Math.fma(source / total, delta, state[i]);
        state[i + 1] = other[j + 1] + state[i + 1] + delta * delta * source * target / total;
        state[i] = mean;
    }

    /**
     * Returns the result of a function over the state of a group of
     * {@code n} pairs, boxed: {@code NULL} as {@code null}, the count of
     * {@code regr_count} as a {@code Long}, otherwise a {@code Double}.
     *
     * @throws ArithmeticException if a variance the result divides by is not finite
     */
    static Object value(AggregateFunction function, double[] state, int group, long n) {
        if (function == AggregateFunction.REGR_COUNT) {
            return n;
        }
        if (n == 0 || function == AggregateFunction.COVAR_SAMP && n < 2) {
            return null;
        }
        int i = SIZE * group;
        double count = n;
        return switch (function) {
            case COVAR_POP -> state[i + CO_MOMENT] / count;
            case COVAR_SAMP -> state[i + CO_MOMENT] / (double) (n - 1);
            case CORR -> correlation(state, i, n);
            case REGR_AVGX -> state[i + SUM_X] / count;
            case REGR_AVGY -> state[i + SUM_Y] / count;
            case REGR_SLOPE -> slope(state, i, n);
            case REGR_INTERCEPT -> {
                double slope = slope(state, i, n);
                yield Double.isNaN(slope) ? null
                        : state[i + SUM_Y] / count - slope * (state[i + SUM_X] / count);
            }
            case REGR_R2 -> {
                double varX = variance(state, i + WELFORD_X, n, "VARPOP(X)");
                if (varX == 0) {
                    yield null;
                }
                double varY = variance(state, i + WELFORD_Y, n, "VARPOP(Y)");
                if (varY == 0) {
                    yield 1.0;
                }
                double r = correlation(state, i, n);
                yield r * r;
            }
            case REGR_SXX -> count * variance(state, i + WELFORD_X, n, "VARPOP");
            case REGR_SYY -> count * variance(state, i + WELFORD_Y, n, "VARPOP");
            case REGR_SXY -> count * (state[i + CO_MOMENT] / count);
            default -> throw new IllegalArgumentException("No co-moments for " + function);
        };
    }

    private static double correlation(double[] state, int i, long n) {
        double cov = state[i + CO_MOMENT] / n;
        double stdX = n > 1 ? Math.sqrt(state[i + WELFORD_X + 1] / n) : 0;
        if (!Double.isFinite(stdX)) {
            throw new ArithmeticException("STDDEV_POP for X is out of range!");
        }
        double stdY = n > 1 ? Math.sqrt(state[i + WELFORD_Y + 1] / n) : 0;
        if (!Double.isFinite(stdY)) {
            throw new ArithmeticException("STDDEV_POP for Y is out of range!");
        }
        return stdX * stdY != 0 ? cov / (stdX * stdY) : Double.NaN;
    }

    private static double slope(double[] state, int i, long n) {
        double cov = state[i + CO_MOMENT] / n;
        double var = variance(state, i + WELFORD_X, n, "VARPOP");
        return var != 0 ? cov / var : Double.NaN;
    }

    /** The population variance of one of the two Welford parts at {@code i}. */
    private static double variance(double[] state, int i, long n, String what) {
        double var = n > 1 ? state[i + 1] / n : 0;
        if (!Double.isFinite(var)) {
            throw new ArithmeticException(what + " is out of range!");
        }
        return var;
    }
}
