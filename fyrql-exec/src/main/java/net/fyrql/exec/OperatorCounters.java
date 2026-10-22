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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the operators of one label in a query did: the copies of an
 * operator on the workers of a segment share the label and these counters.
 * Each copy counts its batches and time in a {@link Part} of its own,
 * without atomics, since the workers would contend for shared ones with
 * every batch; the parts are summed when the counters are read.
 */
final class OperatorCounters {

    private final List<Part> parts = new ArrayList<>();
    private final Set<Long> threads = ConcurrentHashMap.newKeySet();
    private final LongAdder spilledParts = new LongAdder();
    private final LongAdder spilledBytes = new LongAdder();
    private final LongAdder readBack = new LongAdder();

    /**
     * What one copy of an operator counts, or the work done for the operator
     * on one thread: written by one thread at a time, read when the counters
     * are summed, which sees all once the query is done.
     */
    static final class Part {
        private final OperatorCounters counters;
        private long rows;
        private long batches;
        private long nanos;
        private Thread thread;

        private Part(OperatorCounters counters) {
            this.counters = counters;
        }

        /** Returns the counters of the label of the operator. */
        OperatorCounters counters() {
            return counters;
        }

        /** Counts a batch that the operator returned. */
        void batch(int rowCount) {
            rows += rowCount;
            batches++;
        }

        /** Counts time that the operator worked on the current thread, without the time of its inputs. */
        void time(long time) {
            nanos += time;
            Thread current = Thread.currentThread();
            if (current != thread) {
                thread = current;
                counters.threads.add(current.threadId());
            }
        }
    }

    /** Returns a new part of the counters, for a copy of the operator or for work on one thread. */
    Part part() {
        Part part = new Part(this);
        synchronized (parts) {
            parts.add(part);
        }
        return part;
    }

    /** Counts a part that the operator spilled to disk, with its bytes. */
    void spilled(long bytes) {
        spilledParts.increment();
        spilledBytes.add(bytes);
    }

    /** Counts bytes that the operator read back from disk. */
    void readBack(long bytes) {
        readBack.add(bytes);
    }

    /** Adds the threads that the operator worked on to a set. */
    void threads(Set<Long> into) {
        into.addAll(threads);
    }

    /** Returns what was counted so far. */
    OperatorStatistics snapshot() {
        long rows = 0;
        long batches = 0;
        long nanos = 0;
        synchronized (parts) {
            for (Part p : parts) {
                rows += p.rows;
                batches += p.batches;
                nanos += p.nanos;
            }
        }
        return new OperatorStatistics(rows, batches, nanos, threads.size(), spilledParts.sum(), spilledBytes.sum(),
                readBack.sum());
    }
}
