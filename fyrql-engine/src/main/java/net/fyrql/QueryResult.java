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
import net.fyrql.exec.SpillStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * The result of a query: batches on demand, or all rows at once as Java
 * values (see {@link net.fyrql.core.type.DataType} for the value of each
 * type). Closing releases the sources and ends the timeout of the query,
 * which counts until then.
 */
public final class QueryResult implements AutoCloseable {

    private final Operator operator;
    private final QueryContext context;
    private final ScheduledFuture<?> timeout;

    QueryResult(Operator operator, QueryContext context, ScheduledFuture<?> timeout) {
        this.operator = operator;
        this.context = context;
        this.timeout = timeout;
    }

    /** Returns the columns of the result. */
    public Schema schema() {
        return operator.schema();
    }

    /** Returns the next batch, or {@code null} after the last one. */
    public Batch nextBatch() {
        return operator.next();
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
            Timeouts.stop(timeout);
        }
    }
}
