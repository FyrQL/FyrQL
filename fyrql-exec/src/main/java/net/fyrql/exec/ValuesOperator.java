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
