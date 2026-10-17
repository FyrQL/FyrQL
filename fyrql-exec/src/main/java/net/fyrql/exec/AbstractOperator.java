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

package net.fyrql.exec;

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

/**
 * The common shape of the operators: a label for messages, a schema, and
 * the wrapping of any error of {@link #compute()} into a
 * {@link QueryException} that names the operator. Each batch checks the
 * context of the query before and after it is computed; once the query is
 * canceled, any error is a {@link QueryCancelledException}, since a source
 * that was asked to stop fails with an error of its own.
 */
abstract class AbstractOperator implements Operator {

    /** The rows or steps between two checks of a loop that runs long without a batch, a power of two. */
    static final int CHECK_INTERVAL = 1 << 16;

    private final String label;
    private final Schema schema;
    private QueryContext context = QueryContext.NONE;

    AbstractOperator(String label, Schema schema) {
        this.label = label;
        this.schema = schema;
    }

    /** Returns the label, e.g. {@code Filter#2}. */
    String label() {
        return label;
    }

    /** Sets the context of the query, once the operator is built. */
    void attach(QueryContext context) {
        this.context = context;
    }

    /** Returns the context of the query. */
    QueryContext context() {
        return context;
    }

    /**
     * Throws if the query is canceled, for the loops that run long without
     * a batch, every {@link #CHECK_INTERVAL} rows or steps.
     *
     * @throws QueryCancelledException if the query is canceled
     */
    void checkCancelled() {
        context.check(label);
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public final Batch next() {
        context.check(label);
        try {
            Batch b = compute();
            context.check(label);
            return b;
        } catch (QueryCancelledException e) {
            throw e;
        } catch (QueryException e) {
            if (context.isCancelled()) {
                throw new QueryCancelledException(label, context.reason(), e);
            }
            throw e;
        } catch (RuntimeException e) {
            if (context.isCancelled()) {
                throw new QueryCancelledException(label, context.reason(), e);
            }
            throw new QueryException(label, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    e);
        }
    }

    /** Computes the next batch, or {@code null} at the end; never an empty batch. */
    abstract Batch compute();
}
