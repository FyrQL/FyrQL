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
