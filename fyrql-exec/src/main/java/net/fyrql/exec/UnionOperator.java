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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;

import java.util.List;

/** Passes the batches of every input, one input after the other. */
final class UnionOperator extends AbstractOperator {

    private final List<Operator> inputs;
    private int current;

    UnionOperator(String label, PlanNode.Union union, List<Operator> inputs) {
        super(label, union.schema());
        this.inputs = List.copyOf(inputs);
    }

    @Override
    Batch compute() {
        while (current < inputs.size()) {
            Batch b = inputs.get(current).next();
            if (b != null) {
                return b.schema().equals(schema()) ? b : new Batch(schema(), b.rowCount(), b.columns());
            }
            current++;
        }
        return null;
    }

    @Override
    public void close() {
        for (Operator in : inputs) {
            in.close();
        }
    }
}
