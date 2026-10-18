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

package net.fyrql.connector.odata;

import net.fyrql.connector.BatchReader;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Reads the one row of {@code count(*)}, once or more often, that the
 * service counts: it asks for the number when the first batch is wanted.
 */
final class CountReader implements BatchReader {

    private final Schema output;
    private final LongSupplier count;
    private boolean done;

    /**
     * @param output the columns of the row, each a {@code BIGINT}
     * @param count asks the service for the number
     */
    CountReader(Schema output, LongSupplier count) {
        this.output = Objects.requireNonNull(output, "output");
        this.count = Objects.requireNonNull(count, "count");
    }

    @Override
    public Batch next() {
        if (done) {
            return null;
        }
        done = true;
        Object[] row = new Object[output.size()];
        long n = count.getAsLong();
        for (int c = 0; c < row.length; c++) {
            row[c] = n;
        }
        return Batch.ofRows(output, List.<Object[]>of(row));
    }
}
