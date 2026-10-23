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

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryStatistics;
import net.fyrql.exec.SpillStatistics;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * The result of a query: batches on demand, or all rows at once as Java
 * values. The values of a batch are in the form of its vectors (see
 * {@link net.fyrql.core.type.DataType}); those of {@link #rows()} have the
 * dates and times of {@code java.time}. Closing releases the sources and
 * ends the timeout of the query, which counts until then, and its event of
 * Java Flight Recorder, if one is recorded.
 */
public final class QueryResult implements AutoCloseable {

    private final Operator operator;
    private final QueryContext context;
    private final ScheduledFuture<?> timeout;
    private final ZoneId zone;
    private QueryEvent event;
    private final String text;
    private long rows;
    private Throwable failure;

    QueryResult(Operator operator, QueryContext context, ScheduledFuture<?> timeout, ZoneId zone) {
        this(operator, context, timeout, zone, null, null);
    }

    /**
     * @param zone  the time zone of the session, in which {@link #rows()} gives a
     *              {@code TIMESTAMP WITH TIME ZONE}
     * @param event the event of the query, or {@code null} if none is recorded
     * @param text  the text of the statement for the event, or {@code null} for the first line of its plan
     */
    QueryResult(Operator operator, QueryContext context, ScheduledFuture<?> timeout, ZoneId zone, QueryEvent event,
            String text) {
        this.operator = operator;
        this.context = context;
        this.timeout = timeout;
        this.zone = zone;
        this.event = event;
        this.text = text;
    }

    /** Returns the columns of the result. */
    public Schema schema() {
        return operator.schema();
    }

    /**
     * Returns the next batch, or {@code null} after the last one. Its values
     * are in the form of its vectors, a {@code DATE} e.g. the days since
     * 1970-01-01, see {@link net.fyrql.core.type.DataType}.
     */
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

    /**
     * Collects the remaining rows as Java values, of the classes of
     * {@link net.fyrql.core.type.DataType} except for dates and times: a
     * {@code DATE} is a {@link java.time.LocalDate}, a {@code TIME} a
     * {@link java.time.LocalTime} ({@code 24:00:00} is
     * {@link java.time.LocalTime#MAX}), a {@code TIMESTAMP} a
     * {@link java.time.LocalDateTime} and a {@code TIMESTAMP WITH TIME ZONE}
     * an {@link java.time.OffsetDateTime} in the time zone of the session,
     * also inside a {@code LIST}, {@code STRUCT} or {@code MAP}; see
     * {@link Values#toJava}. An {@code INTERVAL} is an
     * {@link net.fyrql.core.value.Interval}.
     */
    public List<Object[]> rows() {
        List<DataType> types = schema().types();
        boolean[] convert = new boolean[types.size()];
        boolean any = false;
        for (int i = 0; i < convert.length; i++) {
            convert[i] = Values.hasJavaTime(types.get(i));
            any |= convert[i];
        }
        List<Object[]> rows = new ArrayList<>();
        for (Batch b = nextBatch(); b != null; b = nextBatch()) {
            List<Object[]> batch = b.rows();
            if (any) {
                for (Object[] row : batch) {
                    for (int i = 0; i < convert.length; i++) {
                        if (convert[i]) {
                            row[i] = Values.toJava(row[i], types.get(i), zone);
                        }
                    }
                }
            }
            rows.addAll(batch);
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
