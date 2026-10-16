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
 * {@link QueryException} that names the operator.
 */
abstract class AbstractOperator implements Operator {

    private final String label;
    private final Schema schema;

    AbstractOperator(String label, Schema schema) {
        this.label = label;
        this.schema = schema;
    }

    /** Returns the label, e.g. {@code Filter#2}. */
    String label() {
        return label;
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public final Batch next() {
        try {
            return compute();
        } catch (QueryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QueryException(label, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    e);
        }
    }

    /** Computes the next batch, or {@code null} at the end; never an empty batch. */
    abstract Batch compute();
}
