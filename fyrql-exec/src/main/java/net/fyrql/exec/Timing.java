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

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Measures the own time of the operators on one thread: a stack of
 * frames, one for each operator that works, such as in a call of
 * {@link Operator#next()}. A frame books its time to its operator less the
 * time of the frames opened within it, the time of its inputs, and passes
 * its whole time on to the frame around it. A frame without an operator
 * books nothing, so that time spent waiting, such as for the workers of a
 * segment, counts for no operator.
 */
final class Timing {

    private static final ThreadLocal<Timing> CURRENT = ThreadLocal.withInitial(Timing::new);

    private OperatorCounters.Part[] owners = new OperatorCounters.Part[32];
    private long[] inner = new long[32];
    private int depth;

    private Timing() {
    }

    /** Returns the frames of the current thread. */
    static Timing current() {
        return CURRENT.get();
    }

    /**
     * Opens a frame and returns its start, which {@link #end(long)} takes.
     *
     * @param owner the counters of the operator the time is booked to, {@code null} for none
     */
    long begin(OperatorCounters.Part owner) {
        if (depth == owners.length) {
            owners = Arrays.copyOf(owners, depth * 2);
            inner = Arrays.copyOf(inner, depth * 2);
        }
        owners[depth] = owner;
        inner[depth] = 0;
        depth++;
        return System.nanoTime();
    }

    /** Closes the frame opened last, given its start. */
    void end(long start) {
        long total = System.nanoTime() - start;
        int d = --depth;
        OperatorCounters.Part owner = owners[d];
        owners[d] = null;
        if (owner != null) {
            owner.time(Math.max(0, total - inner[d]));
        }
        if (d > 0) {
            inner[d - 1] += total;
        }
    }

    /**
     * Runs work in a frame of the operators of a label, or in one that counts
     * for no operator, if the context counts.
     *
     * @param label the label of the operators, {@code null} for none
     */
    static <T> T run(QueryContext context, String label, Supplier<T> work) {
        if (!context.counting()) {
            return work.get();
        }
        Timing timing = current();
        long start = timing.begin(label == null ? null : context.operator(label).part());
        try {
            return work.get();
        } finally {
            timing.end(start);
        }
    }

    /** Returns the operator of the frame opened last, or {@code null} if there is none or it has none. */
    OperatorCounters.Part owner() {
        return depth == 0 ? null : owners[depth - 1];
    }
}
