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

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryStatistics;
import net.fyrql.exec.SpillStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * The result of a query: batches on demand, or all rows at once as Java
 * values (see {@link net.fyrql.core.type.DataType} for the value of each
 * type). Closing releases the sources and ends the timeout of the query,
 * which counts until then, and its event of Java Flight Recorder, if one
 * is recorded.
 */
public final class QueryResult implements AutoCloseable {

    private final Operator operator;
    private final QueryContext context;
    private final ScheduledFuture<?> timeout;
    private QueryEvent event;
    private final String text;
    private long rows;
    private Throwable failure;

    QueryResult(Operator operator, QueryContext context, ScheduledFuture<?> timeout) {
        this(operator, context, timeout, null, null);
    }

    /**
     * @param event the event of the query, or {@code null} if none is recorded
     * @param text  the text of the statement for the event, or {@code null} for the first line of its plan
     */
    QueryResult(Operator operator, QueryContext context, ScheduledFuture<?> timeout, QueryEvent event,
            String text) {
        this.operator = operator;
        this.context = context;
        this.timeout = timeout;
        this.event = event;
        this.text = text;
    }

    /** Returns the columns of the result. */
    public Schema schema() {
        return operator.schema();
    }

    /** Returns the next batch, or {@code null} after the last one. */
    public Batch nextBatch() {
        try {
            Batch b = operator.next();
            if (b != null) {
                rows += b.rowCount();
            }
            return b;
        } catch (RuntimeException | Error e) {
            if (failure == null) {
                failure = e;
            }
            throw e;
        }
    }

    /** Collects the remaining rows as Java values. */
    public List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        for (Batch b = nextBatch(); b != null; b = nextBatch()) {
            rows.addAll(b.rows());
        }
        return rows;
    }

    /**
     * Returns what the operators of the query have spilled to disk so far,
     * because the memory budget was exhausted, and read back.
     */
    public SpillStatistics spill() {
        return context.spill();
    }

    /**
     * Returns what the query has done so far, or in all once the result is
     * closed: the plan it runs, the rows, batches and time of each of its
     * operators and what each of its scans read from its source, under
     * their labels; see {@link FyrQL#explain(String)} for the plan.
     */
    public QueryStatistics statistics() {
        return context.statistics();
    }

    /**
     * Cancels the query from any thread: the sources are asked to stop, and
     * the next batch, or the one being read, fails with a
     * {@link net.fyrql.exec.QueryCancelledException}. The result still
     * needs to be closed.
     */
    public void cancel() {
        context.cancel();
    }

    @Override
    public void close() {
        try {
            operator.close();
        } finally {
            context.finished();
            Timeouts.stop(timeout);
            if (event != null) {
                QueryEvent e = event;
                event = null;
                e.done(text, context, rows, failure);
            }
        }
    }
}
