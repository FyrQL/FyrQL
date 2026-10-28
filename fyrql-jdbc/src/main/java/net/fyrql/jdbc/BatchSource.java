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

package net.fyrql.jdbc;

import net.fyrql.QueryResult;
import net.fyrql.SqlStatement;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryContext;

import java.util.List;
import java.util.Optional;

/**
 * The batches behind a result set: those of a running query, or rows the
 * driver made itself, such as a plan or the result of a metadata method.
 */
interface BatchSource {

    /** Returns the columns. */
    Schema schema();

    /**
     * Returns the next batch, or {@code null} after the last one.
     *
     * @throws RuntimeException if the query fails
     */
    Batch next();

    /** Releases what the batches come from; it may be called more than once. */
    void close();

    /**
     * Returns for each column the column of a table it shows as it is, or
     * {@code null}; empty if not known.
     */
    default List<Optional<SqlStatement.ColumnOrigin>> origins() {
        return List.of();
    }

    /**
     * Cancels the query from another thread, so that {@link #next()} fails
     * soon; rows the driver made itself are not canceled.
     */
    default void cancel() {
    }

    /**
     * The batches of a query that a context cancels. Once it is canceled,
     * closing does not fail: a source that was asked to stop may fail to
     * close as well.
     */
    static BatchSource of(QueryResult result, QueryContext context) {
        return of(result, context, List.of());
    }

    /** The batches of a query as {@link #of(QueryResult, QueryContext)}, with the origins of its columns. */
    static BatchSource of(QueryResult result, QueryContext context,
            List<Optional<SqlStatement.ColumnOrigin>> origins) {
        return new BatchSource() {
            @Override
            public Schema schema() {
                return result.schema();
            }

            @Override
            public List<Optional<SqlStatement.ColumnOrigin>> origins() {
                return origins;
            }

            @Override
            public Batch next() {
                return result.nextBatch();
            }

            @Override
            public void close() {
                try {
                    result.close();
                } catch (RuntimeException e) {
                    if (!context.isCancelled()) {
                        throw e;
                    }
                }
            }

            @Override
            public void cancel() {
                context.cancel();
            }
        };
    }

    /** Rows as one batch. */
    static BatchSource of(Schema schema, List<Object[]> rows) {
        return new BatchSource() {
            private Batch batch = rows.isEmpty() ? null : Batch.ofRows(schema, rows);

            @Override
            public Schema schema() {
                return schema;
            }

            @Override
            public Batch next() {
                Batch b = batch;
                batch = null;
                return b;
            }

            @Override
            public void close() {
                batch = null;
            }
        };
    }
}
