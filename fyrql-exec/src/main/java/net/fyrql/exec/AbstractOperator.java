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

import net.fyrql.core.function.ValueBudget;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

/**
 * The common shape of the operators: a label for messages, a schema, and
 * the wrapping of any error of {@link #compute()} into a
 * {@link QueryException} that names the operator. Each batch checks the
 * context of the query before and after it is computed; once the query is
 * canceled, any error is a {@link QueryCancelledException}, since a source
 * that was asked to stop fails with an error of its own.
 * <p>
 * Unless the context is {@link QueryContext#NONE}, each batch also counts
 * for the operator under its label, with the time it took less the time of
 * the inputs, see {@link Timing}.
 */
abstract class AbstractOperator implements Operator {

    /** The rows or steps between two checks of a loop that runs long without a batch, a power of two. */
    static final int CHECK_INTERVAL = 1 << 16;

    private final String label;
    private final Schema schema;
    private QueryContext context = QueryContext.NONE;
    /** Whether the batches are timed, and the counters they count for, {@code null} if for none. */
    private boolean timed;
    private OperatorCounters.Part counters;

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
        timed = context.counting();
        counters = timed && counts() ? context.operator(label).part() : null;
    }

    /**
     * Tells whether the operator counts its batches and time under its
     * label; an operator that passes on the batches of others with the same
     * label counts nothing, and its time counts for no operator.
     */
    boolean counts() {
        return true;
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
        if (!timed) {
            return batch();
        }
        Timing timing = Timing.current();
        long start = timing.begin(counters);
        try {
            Batch b = batch();
            if (b != null && counters != null) {
                counters.batch(b.rowCount());
            }
            return b;
        } finally {
            timing.end(start);
        }
    }

    private Batch batch() {
        context.check(label);
        MemoryBudget budget = context.budget();
        Values values = budget == null ? null : new Values(budget, label);
        ValueBudget.Account previous = values == null ? null : ValueBudget.enter(values);
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
        } finally {
            if (values != null) {
                ValueBudget.leave(previous);
                values.release();
            }
        }
    }

    /**
     * The bytes the functions take for their large values while an operator
     * computes a batch, from the budget of the query until the batch is
     * computed; the operators that keep the batch count it themselves.
     */
    private static final class Values implements ValueBudget.Account {
        private final MemoryBudget budget;
        private final String label;
        private long taken;

        Values(MemoryBudget budget, String label) {
            this.budget = budget;
            this.label = label;
        }

        @Override
        public void take(long bytes, String what) {
            budget.reserve(bytes, label);
            taken += bytes;
        }

        void release() {
            budget.release(taken);
        }
    }

    /** Computes the next batch, or {@code null} at the end; never an empty batch. */
    abstract Batch compute();
}
