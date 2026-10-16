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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;

import java.util.ArrayList;
import java.util.List;

/** Emits the constant rows of a {@link PlanNode.Values}. */
final class ValuesOperator extends AbstractOperator {

    private final List<Batch> batches;

    ValuesOperator(String label, PlanNode.Values values, int batchSize) {
        super(label, values.schema());
        this.batches = values.rows().isEmpty() ? new ArrayList<>()
                : new ArrayList<>(Batches.split(Batch.ofRows(values.schema(), values.rows()), batchSize));
    }

    @Override
    Batch compute() {
        return batches.isEmpty() ? null : batches.remove(0);
    }

    @Override
    public void close() {
        batches.clear();
    }
}
