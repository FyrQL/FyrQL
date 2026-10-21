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

import net.fyrql.connector.BatchReader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * The state of one running query that another thread may cancel. Once
 * canceled, every operator of the query fails with a
 * {@link QueryCancelledException} at its next batch, and the operators
 * that work long without one, such as a sort, check every so many rows;
 * the readers of the sources that the query registered are asked to stop
 * at once, see {@link BatchReader#cancel()}, so that a query that waits on
 * a remote source ends too. Canceling is final: the first reason stays.
 * <p>
 * The context also counts what the operators of the query spill to disk,
 * see {@link #spill()}.
 */
public final class QueryContext {

    /** Why a query was canceled. */
    public enum Reason {
        /** On request, such as {@code Statement.cancel()} of JDBC. */
        CANCEL("The query was canceled"),
        /** Because it ran longer than allowed. */
        TIMEOUT("The query timed out");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /** Returns the message of the failure, such as {@code The query was canceled}. */
        public String message() {
            return message;
        }
    }

    /** The context of the queries that cannot be canceled. */
    public static final QueryContext NONE = new QueryContext(false);

    private final boolean cancellable;
    private final List<BatchReader> readers = new ArrayList<>();
    private volatile Reason reason;
    private final LongAdder spilledParts = new LongAdder();
    private final LongAdder spilledBytes = new LongAdder();
    private final LongAdder readBytes = new LongAdder();

    /** A context that is not canceled yet. */
    public QueryContext() {
        this(true);
    }

    private QueryContext(boolean cancellable) {
        this.cancellable = cancellable;
    }

    /** Cancels the query on request. */
    public void cancel() {
        cancel(Reason.CANCEL);
    }

    /**
     * Cancels the query and asks the registered readers to stop; this
     * returns at once and may be called from any thread. A second call
     * does nothing, and {@link #NONE} ignores it.
     */
    public void cancel(Reason why) {
        List<BatchReader> toStop;
        synchronized (readers) {
            if (!cancellable || reason != null) {
                return;
            }
            reason = why;
            toStop = List.copyOf(readers);
            readers.clear();
        }
        for (BatchReader r : toStop) {
            stop(r);
        }
    }

    /** Returns whether the query is canceled. */
    public boolean isCancelled() {
        return reason != null;
    }

    /** Returns why the query is canceled, or {@code null} if it is not. */
    public Reason reason() {
        return reason;
    }

    /**
     * Throws if the query is canceled.
     *
     * @param operator the label of the operator that checks, e.g. {@code Sort#1}
     * @throws QueryCancelledException if the query is canceled
     */
    public void check(String operator) {
        Reason r = reason;
        if (r != null) {
            throw new QueryCancelledException(operator, r, null);
        }
    }

    /**
     * Registers a reader of the query, which {@link #cancel} asks to stop;
     * if the query is canceled already, it is asked at once. A closed
     * reader may stay registered, since asking it to stop does nothing.
     */
    public void register(BatchReader reader) {
        synchronized (readers) {
            if (!cancellable) {
                return;
            }
            if (reason == null) {
                readers.add(reader);
                return;
            }
        }
        stop(reader);
    }

    /** Counts a part that an operator of the query spilled to disk, with its bytes. */
    void spilled(long bytes) {
        spilledParts.increment();
        spilledBytes.add(bytes);
    }

    /** Counts bytes that an operator of the query read back from disk. */
    void readBack(long bytes) {
        readBytes.add(bytes);
    }

    /**
     * Returns what the operators of the query have spilled to disk so far
     * and read back; all zero if they spilled nothing.
     */
    public SpillStatistics spill() {
        return new SpillStatistics(spilledParts.sum(), spilledBytes.sum(), readBytes.sum());
    }

    private static void stop(BatchReader reader) {
        try {
            reader.cancel();
        } catch (RuntimeException e) {
            // At best effort: the query fails with the reason of its context anyway.
        }
    }
}
