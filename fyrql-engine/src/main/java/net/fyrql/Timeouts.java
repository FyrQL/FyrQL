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

package net.fyrql;

import net.fyrql.exec.QueryContext;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The timeouts of the queries of an engine: one daemon thread, started
 * with the first timeout and stopped when the engine closes, cancels the
 * queries that run too long. A timeout ends a query from another thread
 * because the thread of the query may wait in a source, where no deadline
 * of the engine would reach it.
 */
final class Timeouts implements AutoCloseable {

    /** The timeout of a query that asks for none, or {@code null}. */
    private final Duration queryTimeout;
    /** The upper bound of every timeout, or {@code null}. */
    private final Duration maxQueryTimeout;
    private ScheduledThreadPoolExecutor timer;
    private boolean closed;

    /**
     * Creates the timeouts.
     *
     * @throws IllegalArgumentException if the timeout is above the upper bound
     */
    Timeouts(Duration queryTimeout, Duration maxQueryTimeout) {
        if (queryTimeout != null && maxQueryTimeout != null && queryTimeout.compareTo(maxQueryTimeout) > 0) {
            throw new IllegalArgumentException("The queryTimeout " + text(queryTimeout) + " is above the"
                    + " maxQueryTimeout " + text(maxQueryTimeout));
        }
        this.queryTimeout = queryTimeout;
        this.maxQueryTimeout = maxQueryTimeout;
    }

    Duration queryTimeout() {
        return queryTimeout;
    }

    Duration maxQueryTimeout() {
        return maxQueryTimeout;
    }

    /**
     * Returns the timeout of a query: the one it asks for, or else the one
     * of the engine, at most the upper bound; {@code null} for none.
     */
    Duration effective(Duration requested) {
        Duration t = requested != null ? requested : queryTimeout;
        return maxQueryTimeout != null && (t == null || t.compareTo(maxQueryTimeout) > 0) ? maxQueryTimeout : t;
    }

    /**
     * Cancels a query with {@link QueryContext.Reason#TIMEOUT} once its
     * timeout has passed since its context was created, at once if it has
     * passed already; the caller stops the returned timeout, see
     * {@link #stop}, when the query ends before.
     *
     * @return the timeout, or {@code null} if the query has none or cannot be canceled
     */
    ScheduledFuture<?> start(QueryContext context) {
        Duration t = effective(context.timeout());
        if (t == null || !context.isCancellable()) {
            return null;
        }
        long delay;
        try {
            delay = Math.max(0, t.minus(context.elapsed()).toNanos());
        } catch (ArithmeticException e) {
            delay = Long.MAX_VALUE;
        }
        ScheduledThreadPoolExecutor executor = timer();
        return executor == null ? null
                : executor.schedule(() -> context.cancel(QueryContext.Reason.TIMEOUT), delay, TimeUnit.NANOSECONDS);
    }

    /** Stops a timeout that {@link #start} returned, which may be {@code null}. */
    static void stop(ScheduledFuture<?> timeout) {
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    /** Returns the timeouts that have not passed or been stopped yet. */
    synchronized int pending() {
        return timer == null ? 0 : timer.getQueue().size();
    }

    private synchronized ScheduledThreadPoolExecutor timer() {
        if (closed) {
            return null;
        }
        if (timer == null) {
            timer = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "fyrql-timeout");
                t.setDaemon(true);
                return t;
            });
            // A query that ends in time takes its timeout off the queue.
            timer.setRemoveOnCancelPolicy(true);
        }
        return timer;
    }

    /** Stops the thread; queries that still run keep running without their timeouts. */
    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** Returns a duration as a setting takes it, such as {@code 30s} or {@code 5m}. */
    static String text(Duration d) {
        if (d.getNano() % 1_000_000 != 0) {
            return d.toString();
        }
        long ms = d.toMillis();
        return ms % 3_600_000 == 0 ? ms / 3_600_000 + "h" : ms % 60_000 == 0 ? ms / 60_000 + "m"
                : ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }
}
