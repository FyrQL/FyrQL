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

package net.fyrql;

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Operator;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a query: batches on demand, or all rows at once as Java
 * values (see {@link net.fyrql.core.type.DataType} for the value of each
 * type). Closing releases the sources.
 */
public final class QueryResult implements AutoCloseable {

    private final Operator operator;

    QueryResult(Operator operator) {
        this.operator = operator;
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

    @Override
    public void close() {
        operator.close();
    }
}
