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

/**
 * Reads the rows of the round before of the {@link RecursiveUnionOperator}
 * above, from the start; the operator opens the scan anew with the step of
 * each round.
 */
final class WorkingTableScanOperator extends AbstractOperator {

    private final RecursiveUnionOperator recursion;
    private CommonTableBuffer.Reader reader;

    WorkingTableScanOperator(String label, PlanNode.WorkingTableScan scan, RecursiveUnionOperator recursion) {
        super(label, scan.schema());
        if (recursion == null) {
            throw new IllegalArgumentException("No recursive common table " + scan.name() + " #" + scan.id()
                    + " above its scan " + label);
        }
        this.recursion = recursion;
    }

    @Override
    Batch compute() {
        if (reader == null) {
            reader = recursion.rowsOfRoundBefore().reader();
        }
        return reader.next();
    }

    @Override
    public void close() {
        // The rows belong to the recursion, which closes them after the round.
    }
}
