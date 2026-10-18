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

import net.fyrql.connector.Batches;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Reads all batches of the input of an operator that keeps them, such as a
 * sort, in their order, each reserved in the budget under the label of the
 * operator. Over a segment, the workers keep the batches they make, which
 * the segment then puts in the order of its morsels, see
 * {@link ParallelSegment#collect}, so that no worker waits for the thread of
 * the query; the batches are those one thread would read.
 */
final class Materialize {

    private Materialize() {
    }

    /**
     * Returns the batches of an input.
     *
     * @param label    the label of the operator that keeps them
     * @param reserved counts the bytes reserved in the budget, which the caller releases
     */
    static List<Batch> read(Operator input, String label, MemoryBudget budget, AtomicLong reserved) {
        Function<Batch, Batch> keep = b -> {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, label);
            reserved.addAndGet(bytes);
            return b;
        };
        if (input instanceof ParallelSegment segment) {
            return segment.collect(() -> keep);
        }
        List<Batch> batches = new ArrayList<>();
        for (Batch b = input.next(); b != null; b = input.next()) {
            batches.add(keep.apply(b));
        }
        return batches;
    }
}
