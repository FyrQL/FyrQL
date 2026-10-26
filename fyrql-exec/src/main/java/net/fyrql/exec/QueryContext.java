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
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import net.fyrql.core.plan.PlanNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * see {@link #spill()}, and carries the timeout the caller asks for, see
 * {@link #withTimeout(Duration)}; the engine cancels the query with
 * {@link Reason#TIMEOUT} once it has run that long since the context was
 * created.
 * <p>
 * It counts what each operator and scan of the query does under its label,
 * such as {@code Filter#2}: the rows, batches and time of the operators,
 * what the scans read from their sources, see {@link #source(String)}, and
 * the code compiled for the query, see {@link #statistics()}. The shared
 * {@link #NONE} counts nothing.
 * <p>
 * Part of the API; covered by the compatibility promise.
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
    public static final QueryContext NONE = new QueryContext(false, null);

    private final boolean cancellable;
    private final Duration timeout;
    private final long created = System.nanoTime();
    private final List<BatchReader> readers = new ArrayList<>();
    private volatile Reason reason;
    private final LongAdder spilledParts = new LongAdder();
    private final LongAdder spilledBytes = new LongAdder();
    private final LongAdder readBytes = new LongAdder();
    private final Map<String, OperatorCounters> operators = new ConcurrentHashMap<>();
    private final List<String> operatorOrder = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, SourceCounters> sources = new ConcurrentHashMap<>();
    private final List<String> sourceOrder = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder compiled = new LongAdder();
    private final LongAdder compileNanos = new LongAdder();
    private volatile PlanNode plan;
    private volatile long finished;
    private volatile MemoryBudget budget;

    /** A context that is not canceled yet, with the timeout of the engine. */
    public QueryContext() {
        this(true, null);
    }

    private QueryContext(boolean cancellable, Duration timeout) {
        this.cancellable = cancellable;
        this.timeout = timeout;
    }

    /**
     * Returns a context that is not canceled yet, for a query that may run
     * at most as long as given, counted from now; the engine shortens the
     * timeout to its upper bound, if it has one.
     *
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public static QueryContext withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A timeout must be positive: " + timeout);
        }
        return new QueryContext(true, timeout);
    }

    /** Returns the timeout the caller asked for, or {@code null} for the one of the engine. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the time since the context was created. */
    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - created);
    }

    /** Tells whether the query can be canceled, which all but {@link #NONE} can. */
    public boolean isCancellable() {
        return cancellable;
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
    /** Sets the memory budget of the query, which the values of functions count against, see {@link #budget()}. */
    void budget(MemoryBudget budget) {
        this.budget = budget;
    }

    /**
     * Returns the memory budget of the query against which the operators
     * count the large values of functions, see
     * {@link net.fyrql.core.function.ValueBudget}, or {@code null}.
     */
    MemoryBudget budget() {
        return budget;
    }

    void spilled(String operator, long bytes) {
        spilledParts.increment();
        spilledBytes.add(bytes);
        if (counting()) {
            operator(operator).spilled(bytes);
        }
    }

    /** Counts bytes that an operator of the query read back from disk. */
    void readBack(String operator, long bytes) {
        readBytes.add(bytes);
        if (counting()) {
            operator(operator).readBack(bytes);
        }
    }

    /** Tells whether the context counts what the query does, which all but {@link #NONE} do. */
    boolean counting() {
        return cancellable;
    }

    /** Returns the counters of the operators of a label; only for a context that counts. */
    OperatorCounters operator(String label) {
        return operators.computeIfAbsent(label, l -> {
            operatorOrder.add(l);
            return new OperatorCounters();
        });
    }

    /**
     * Returns the counters of the scan or part of the plan that a source
     * runs of a label, which the engine makes current while it reads, see
     * {@link SourceCounters#enter(SourceCounters)}; {@link SourceCounters#NONE}
     * for {@link #NONE}.
     */
    public SourceCounters source(String label) {
        if (!counting()) {
            return SourceCounters.NONE;
        }
        return sources.computeIfAbsent(label, l -> {
            sourceOrder.add(l);
            return new SourceCounters();
        });
    }

    /** Counts code compiled for the query, or taken from the cache, with the time it took. */
    void compiled(long nanos) {
        if (counting()) {
            compiled.increment();
            compileNanos.add(nanos);
        }
    }

    /**
     * Notes the plan that the query runs, after it is optimized, so that
     * the labels of the counters can be found in it; the first plan counts.
     */
    public void plan(PlanNode plan) {
        if (this.plan == null && counting()) {
            synchronized (this) {
                if (this.plan == null) {
                    this.plan = plan;
                }
            }
        }
    }

    /** Ends the time of the query, once its result is closed; the first call counts. */
    public void finished() {
        if (finished == 0) {
            long now = System.nanoTime();
            finished = now == 0 ? 1 : now;
        }
    }

    /**
     * Returns what the query did so far, or until it {@link #finished()}:
     * the operators and scans under their labels, the spilling, the code
     * compiled for it and the time.
     */
    public QueryStatistics statistics() {
        long end = finished;
        Duration elapsed = Duration.ofNanos((end != 0 ? end : System.nanoTime()) - created);
        Map<String, OperatorStatistics> ops = new LinkedHashMap<>();
        Set<Long> threads = new HashSet<>();
        for (String label : List.copyOf(operatorOrder)) {
            OperatorCounters c = operators.get(label);
            ops.put(label, c.snapshot());
            c.threads(threads);
        }
        Map<String, SourceStatistics> read = new LinkedHashMap<>();
        for (String label : List.copyOf(sourceOrder)) {
            read.put(label, sources.get(label).snapshot());
        }
        return new QueryStatistics(elapsed, plan, ops, read, spill(), compiled.sum(), compileNanos.sum(),
                threads.size());
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
