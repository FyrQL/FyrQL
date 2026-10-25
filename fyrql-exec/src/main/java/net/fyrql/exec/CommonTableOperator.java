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
 * Runs a {@link PlanNode.CommonTable}: on the first batch, it runs the
 * definition to its end into the buffer, which the
 * {@link CommonTableScanOperator}s of the input read, and closes it; then
 * it passes on the batches of the input. Closing closes the input first,
 * and then the buffer, which no reader needs any longer.
 */
final class CommonTableOperator extends AbstractOperator {

    private final Operator definition;
    private final CommonTableBuffer buffer;
    private final Operator input;
    private boolean filled;
    private boolean closed;

    CommonTableOperator(String label, PlanNode.CommonTable table, Operator definition, CommonTableBuffer buffer,
            Operator input) {
        super(label, table.schema());
        this.definition = definition;
        this.buffer = buffer;
        this.input = input;
    }

    @Override
    Batch compute() {
        if (!filled) {
            for (Batch b = definition.next(); b != null; b = definition.next()) {
                buffer.add(b);
            }
            buffer.finish();
            definition.close();
            filled = true;
        }
        return input.next();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            input.close();
        } finally {
            try {
                if (!filled) {
                    definition.close();
                }
            } finally {
                buffer.close();
            }
        }
    }
}
