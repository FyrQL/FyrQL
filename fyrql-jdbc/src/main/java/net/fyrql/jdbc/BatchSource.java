/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.jdbc;

import net.fyrql.QueryResult;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryContext;

import java.util.List;

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
        return new BatchSource() {
            @Override
            public Schema schema() {
                return result.schema();
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

    /** The batches of a source that runs an action when it is closed, after closing the source. */
    static BatchSource whenClosed(BatchSource source, Runnable action) {
        return new BatchSource() {
            @Override
            public Schema schema() {
                return source.schema();
            }

            @Override
            public Batch next() {
                return source.next();
            }

            @Override
            public void close() {
                try {
                    source.close();
                } finally {
                    action.run();
                }
            }

            @Override
            public void cancel() {
                source.cancel();
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
