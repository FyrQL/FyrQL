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

    /** The batches of a query. */
    static BatchSource of(QueryResult result) {
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
                result.close();
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
